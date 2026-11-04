// Spec 005 task 1: 默认对话模型存储 + 可用模型枚举。
//
// 设计要点（见 specs/005-对话直入与记忆/design.md）：
//  - SharedPreferences 只存 modelId，显示名一律由 ModelUtils.getModelName 推导，
//    避免「存了两份、改名后不一致」。
//  - 可用模型 = 下载历史表 + 本地扫描目录，统一过 config.json 存在校验，
//    因为下载历史表会残留已删除的模型。
//  - resolve() 实现 R3/R4 兜底：默认值失效 → 最近使用过的可用模型 → 唯一可用模型。
package com.alibaba.mnnllm.android.chat

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.alibaba.mnnllm.android.chat.model.ChatDataManager
import com.alibaba.mnnllm.android.model.ModelUtils
import com.alibaba.mnnllm.android.modelist.LocalModelsProvider
import java.io.File

/** 一个可选的对话模型（已确认磁盘上 config.json 存在）。 */
data class ChatModelOption(
    val modelId: String,
    val displayName: String,
    val lastChatTime: Long
)

object DefaultChatModelStore {

    private const val TAG = "DefaultChatModel"
    private const val KEY_DEFAULT_MODEL = "default_chat_model_id"

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** 用户在设置里选定的默认模型 id；未设置返回 null。 */
    fun getDefaultModelId(context: Context): String? =
        prefs(context).getString(KEY_DEFAULT_MODEL, null)?.takeIf { it.isNotBlank() }

    /** 写入默认模型 id；传 null/空白等于清除。 */
    fun setDefaultModelId(context: Context, modelId: String?) {
        prefs(context).edit().apply {
            if (modelId.isNullOrBlank()) remove(KEY_DEFAULT_MODEL) else putString(KEY_DEFAULT_MODEL, modelId)
        }.apply()
        Log.d(TAG, "default model set to: $modelId")
    }

    /** 模型是否仍可用：config.json 存在（R4 的前置校验）。 */
    fun isUsable(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        val configPath = runCatching { ModelUtils.getConfigPathForModel(modelId) }.getOrNull() ?: return false
        return File(configPath).exists()
    }

    /**
     * 可用模型列表，聊天过的排在前面。
     * 来源：下载历史（可能是已删除模型的残留，靠 isUsable 兜掉）+ 本地/导入目录。
     */
    fun availableModels(context: Context): List<ChatModelOption> {
        val map = LinkedHashMap<String, ChatModelOption>()

        fun add(modelId: String?, lastChatTime: Long) {
            if (modelId.isNullOrBlank()) return
            if (map.containsKey(modelId)) return
            if (!isUsable(modelId)) {
                Log.d(TAG, "skip unusable model: $modelId")
                return
            }
            val name = ModelUtils.getModelName(modelId)?.takeIf { it.isNotBlank() } ?: modelId
            map[modelId] = ChatModelOption(modelId, name, lastChatTime)
        }

        runCatching {
            ChatDataManager.getInstance(context).getAllDownloadedModels()
        }.onSuccess { list ->
            list.forEach { add(it.modelId, it.lastChatTime) }
        }.onFailure { Log.w(TAG, "read downloaded models failed", it) }

        runCatching {
            LocalModelsProvider.getLocalModels()
        }.onSuccess { list ->
            list.forEach { add(it.modelId, 0L) }
        }.onFailure { Log.w(TAG, "read local models failed", it) }

        return map.values.sortedWith(
            compareByDescending<ChatModelOption> { it.lastChatTime }
                .thenBy { it.displayName }
        )
    }

    sealed class Resolution {
        /** [fromDefault] = true 表示命中用户设置的默认模型；false 表示走了兜底。 */
        data class Ready(val modelId: String, val fromDefault: Boolean) : Resolution()

        /** 磁盘上没有任何可用模型，需要引导用户去模型管理。 */
        object Empty : Resolution()
    }

    /**
     * 决定本次新对话用哪个模型（R3/R4）。
     * 注意：兜底结果**不回写**设置 —— 临时场景（如换机后模型未下载）不该悄悄改掉用户的选择。
     */
    fun resolve(context: Context): Resolution {
        val configured = getDefaultModelId(context)
        if (configured != null && isUsable(configured)) {
            return Resolution.Ready(configured, fromDefault = true)
        }
        if (configured != null) {
            Log.w(TAG, "configured default model is gone, falling back: $configured")
        }
        val fallback = availableModels(context).firstOrNull()
            ?: return Resolution.Empty
        Log.i(TAG, "fallback model selected: ${fallback.modelId}")
        return Resolution.Ready(fallback.modelId, fromDefault = false)
    }
}
