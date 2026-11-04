// Spec 005 task 4: 记忆持久化层。
//
// 约束（见 design.md）：
//  - 单文件 JSON，tmp + rename 原子落盘，进程被杀不会留半截文件；
//  - 总条数上限 200，超出优先淘汰最旧的「自动」记忆，手动记忆尽量保留；
//  - 规范去重：忽略大小写、空白与常见标点，"我是一个程序员" 与 "我是一个程序员。"
//    视为同一条，自动抽取不会刷屏；
//  - buildPromptBlock() 受字符预算约束，防止 system prompt 膨胀拖慢首字。
package com.alibaba.mnnllm.android.memory

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.alibaba.mls.api.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object MemoryStore {

    private const val TAG = "MemoryStore"
    private const val DIR_NAME = "memory"
    private const val FILE_NAME = "memories.json"
    private const val PREF_AUTO_EXTRACT = "memory_auto_extract_enabled"

    /** 记忆条数硬上限。超出后淘汰最旧的自动记忆。 */
    const val MAX_ITEMS = 200

    /** 注入 system prompt 的字符预算（约 600 token，端侧首字延迟可控）。 */
    const val PROMPT_BUDGET_CHARS = 1200

    /** 自动抽取入口的开关；默认开启，但实际是否执行还取决于抽取模型是否配置。 */
    private const val DEFAULT_AUTO_EXTRACT = true

    private val lock = Any()
    private var loaded = false
    private val items = mutableListOf<MemoryItem>()

    // ------------------------------------------------------------ 读写

    private fun storeFile(): File {
        val dir = File(ApplicationProvider.get().filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = storeFile()
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val content = o.optString("content", "")
                if (content.isBlank()) continue
                items.add(
                    MemoryItem(
                        id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                        content = content,
                        tags = o.optJSONArray("tags")?.let { tags ->
                            (0 until tags.length()).map { tags.optString(it) }.filter { it.isNotBlank() }
                        } ?: emptyList(),
                        source = o.optString("source", MemoryItem.SOURCE_MANUAL),
                        enabled = o.optBoolean("enabled", true),
                        createdAt = o.optLong("createdAt", 0L),
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                )
            }
            Log.i(TAG, "loaded ${items.size} memories from disk")
        } catch (t: Throwable) {
            // 文件损坏不应让记忆功能不可用：留空重建，旧文件改名留档便于排查。
            Log.w(TAG, "load memories failed, starting empty", t)
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.bad")) }
            items.clear()
        }
    }

    private fun persistLocked() {
        val root = JSONObject().apply {
            put("version", 1)
            put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("content", item.content)
                        put("tags", JSONArray(item.tags))
                        put("source", item.source)
                        put("enabled", item.enabled)
                        put("createdAt", item.createdAt)
                        put("updatedAt", item.updatedAt)
                    })
                }
            })
        }
        val target = storeFile()
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(root.toString().toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    Log.w(TAG, "persist memories failed: rename returned false")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "persist memories failed", t)
        }
    }

    // ------------------------------------------------------------ 查询

    /** 按最近更新倒序返回全部条目（含已禁用的，管理页要展示）。 */
    fun all(): List<MemoryItem> = synchronized(lock) {
        ensureLoaded()
        items.sortedByDescending { it.updatedAt }
    }

    fun enabledCount(): Int = synchronized(lock) {
        ensureLoaded()
        items.count { it.enabled }
    }

    fun isEmpty(): Boolean = synchronized(lock) {
        ensureLoaded()
        items.isEmpty()
    }

    // ------------------------------------------------------------ 写入

    /** 手动新增；内容规范化后与已有条目重复则不新增，改为刷新该条时间。 */
    fun addManual(content: String): MemoryItem? = synchronized(lock) {
        ensureLoaded()
        val text = content.trim()
        if (text.isEmpty()) return null
        val norm = normalize(text)
        val existing = items.firstOrNull { normalize(it.content) == norm }
        val now = System.currentTimeMillis()
        if (existing != null) {
            existing.content = text
            existing.enabled = true
            existing.updatedAt = now
            persistLocked()
            return existing
        }
        val item = MemoryItem(
            id = UUID.randomUUID().toString(),
            content = text,
            source = MemoryItem.SOURCE_MANUAL,
            enabled = true,
            createdAt = now,
            updatedAt = now
        )
        items.add(item)
        enforceLimitLocked()
        persistLocked()
        Log.i(TAG, "manual memory added: ${text.take(40)}")
        item
    }

    /**
     * 自动抽取写入；重复事实只刷新 updatedAt，不产生新条目（R8 去重）。
     * 已禁用条目保持禁用 —— 用户关掉的记忆不该被一次重复抽取悄悄打开。
     */
    fun upsertAuto(content: String, tags: List<String> = emptyList()): Boolean = synchronized(lock) {
        ensureLoaded()
        val text = content.trim()
        if (text.isEmpty()) return false
        val norm = normalize(text)
        val now = System.currentTimeMillis()
        val existing = items.firstOrNull { normalize(it.content) == norm }
        if (existing != null) {
            existing.updatedAt = now
            if (tags.isNotEmpty()) existing.tags = (existing.tags + tags).distinct()
            persistLocked()
            return false
        }
        items.add(
            MemoryItem(
                id = UUID.randomUUID().toString(),
                content = text,
                tags = tags,
                source = MemoryItem.SOURCE_AUTO,
                enabled = true,
                createdAt = now,
                updatedAt = now
            )
        )
        enforceLimitLocked()
        persistLocked()
        Log.i(TAG, "auto memory added: ${text.take(40)}")
        return true
    }

    fun update(id: String, content: String, enabled: Boolean = true): Boolean = synchronized(lock) {
        ensureLoaded()
        val item = items.firstOrNull { it.id == id } ?: return false
        val text = content.trim()
        if (text.isEmpty()) return false
        item.content = text
        item.enabled = enabled
        item.updatedAt = System.currentTimeMillis()
        persistLocked()
        true
    }

    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            ensureLoaded()
            items.firstOrNull { it.id == id }?.let {
                it.enabled = enabled
                it.updatedAt = System.currentTimeMillis()
                persistLocked()
            }
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            ensureLoaded()
            if (items.removeAll { it.id == id }) persistLocked()
        }
    }

    fun clear() = synchronized(lock) {
        ensureLoaded()
        items.clear()
        persistLocked()
        Log.i(TAG, "all memories cleared")
    }

    // ------------------------------------------------------------ 注入

    data class PromptBlock(val text: String, val count: Int)

    /**
     * 生成注入块；没有启用中的记忆时返回空块，调用方据此跳过注入。
     * 预算内的条目按 updatedAt 倒序取，超预算即截断（不做半句切割）。
     */
    fun buildPromptBlock(): PromptBlock = synchronized(lock) {
        ensureLoaded()
        val enabled = items.filter { it.enabled }.sortedByDescending { it.updatedAt }
        if (enabled.isEmpty()) return PromptBlock("", 0)
        val sb = StringBuilder()
        sb.append("以下是关于用户的长期记忆，回答时自然使用，不要逐条复述：\n")
        var count = 0
        for (item in enabled) {
            val line = "- " + item.content.replace("\n", " ").trim() + "\n"
            if (sb.length + line.length > PROMPT_BUDGET_CHARS) break
            sb.append(line)
            count++
        }
        if (count == 0) return PromptBlock("", 0)
        PromptBlock(sb.toString().trimEnd(), count)
    }

    // ------------------------------------------------------------ 设置项

    fun isAutoExtractEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .getBoolean(PREF_AUTO_EXTRACT, DEFAULT_AUTO_EXTRACT)

    fun setAutoExtractEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit().putBoolean(PREF_AUTO_EXTRACT, enabled).apply()
    }

    // ------------------------------------------------------------ 内部

    private fun enforceLimitLocked() {
        while (items.size > MAX_ITEMS) {
            val victim = items.filter { it.source == MemoryItem.SOURCE_AUTO }.minByOrNull { it.updatedAt }
                ?: items.minByOrNull { it.updatedAt }
                ?: return
            items.remove(victim)
            Log.d(TAG, "evicted memory over limit: ${victim.content.take(24)}")
        }
    }

    /** 忽略大小写/空白/标点的规范化文本，用于去重比较。 */
    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[\\s\\p{Punct}，。！？、；：\"\"''《》（）【】…—]"), "")

    /** 调试/验收用：当前状态摘要。 */
    fun debugSummary(): String = synchronized(lock) {
        ensureLoaded()
        "total=${items.size} enabled=${items.count { it.enabled }} auto=${items.count { it.source == MemoryItem.SOURCE_AUTO }}"
    }
}
