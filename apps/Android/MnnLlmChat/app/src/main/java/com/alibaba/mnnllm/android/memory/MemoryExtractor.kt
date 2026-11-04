// Spec 005 task 7: 自动记忆抽取调度。
//
// 设计取舍（见 design.md）：
//  - 抽取放 :llm 进程（复用 AssistantJudgeService 的文件交换协议），不在 UI 进程另起会话 ——
//    否则会和当前对话抢同一个 native 实例。
//  - 每 4 轮触发一次，避免每轮都推理导致发热/耗电。
//  - 任何失败（未配置模型 / 超时 / 解析失败）都只是少记一条，绝不影响对话主流程（R9）。
package com.alibaba.mnnllm.android.memory

import android.content.Context
import android.util.Log
import com.alibaba.mnnllm.android.assistant.notify.AssistantLlmClient
import com.alibaba.mnnllm.android.assistant.notify.AssistantNotifyConfig
import com.alibaba.mnnllm.android.chat.chatlist.ChatViewHolders
import com.alibaba.mnnllm.android.chat.model.ChatDataManager
import org.json.JSONArray
import org.json.JSONObject

object MemoryExtractor {

    private const val TAG = "MemoryExtract"

    /** 每多少轮用户/助手往返触发一次抽取。 */
    private const val INTERVAL_TURNS = 4

    /** 抽取比判定轻，给 90s 就够；超时说明设备压力大，本轮直接放弃。 */
    private const val TIMEOUT_MS = 90_000L

    private const val MAX_TURNS = 8

    private val lock = Any()
    private val turnsSinceLast = HashMap<String, Int>()

    /**
     * 一轮生成正常结束后调用（ChatPresenter.requestGenerate 成功路径）。
     * 只在达到轮次阈值时真正发起后台请求。
     */
    fun onTurnFinished(context: Context, sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        if (!MemoryStore.isAutoExtractEnabled(context)) return
        val judgeModel = AssistantNotifyConfig.judgeModelIdStatic(context)
        if (judgeModel.isNullOrBlank()) {
            Log.d(TAG, "skip: judge model not configured")
            return
        }
        val turns = synchronized(lock) {
            val n = (turnsSinceLast[sessionId] ?: 0) + 1
            if (n < INTERVAL_TURNS) {
                turnsSinceLast[sessionId] = n
                return
            }
            turnsSinceLast[sessionId] = 0
            n
        }
        Log.i(TAG, "trigger extraction after $turns turns, session=$sessionId")
        val appContext = context.applicationContext
        Thread({ run(appContext, judgeModel, sessionId) }, "memory-extract").start()
    }

    private fun run(context: Context, modelId: String, sessionId: String) {
        try {
            val turns = recentTurns(context, sessionId)
            if (turns.size < 2) {
                Log.d(TAG, "skip: not enough turns")
                return
            }
            val existing = MemoryStore.all().map { it.content }
            val payload = JSONObject().apply {
                put("mode", "memory")
                put("modelId", modelId)
                put("turns", JSONArray().apply {
                    turns.forEach { (role, text) ->
                        put(JSONObject().apply {
                            put("role", role)
                            put("text", text)
                        })
                    }
                })
                put("existing", JSONArray(existing))
            }
            val result = AssistantLlmClient.request(context, payload, TIMEOUT_MS) ?: run {
                Log.w(TAG, "extraction timed out or service unavailable")
                return
            }
            if (!result.optBoolean("ok", false)) {
                Log.w(TAG, "extraction failed: ${result.optString("error")}")
                return
            }
            val items = result.optJSONArray("items") ?: return
            var added = 0
            var seen = 0
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val content = o.optString("content").trim()
                if (content.isEmpty()) continue
                seen++
                val tags = o.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList()
                if (MemoryStore.upsertAuto(content, tags)) added++
            }
            Log.i(TAG, "extraction done candidates=$seen added=$added store=[${MemoryStore.debugSummary()}]")
        } catch (t: Throwable) {
            // R9: 抽取是旁路能力，任何异常都不能冒泡到对话链路。
            Log.w(TAG, "extraction crashed", t)
        }
    }

    /** 从会话落库记录里取最近若干条，映射成 (role, text)。 */
    private fun recentTurns(context: Context, sessionId: String): List<Pair<String, String>> {
        val data = runCatching {
            ChatDataManager.getInstance(context).getChatDataBySession(sessionId)
        }.getOrNull() ?: return emptyList()
        return data.asSequence()
            .filter { !it.loading }
            .mapNotNull { item ->
                val text = item.text?.trim()
                if (text.isNullOrEmpty()) return@mapNotNull null
                when (item.type) {
                    ChatViewHolders.USER -> "user" to text
                    ChatViewHolders.ASSISTANT -> "assistant" to text
                    else -> null
                }
            }
            .toList()
            .takeLast(MAX_TURNS)
    }

    /** 会话切换/清空时可重置计数（当前由 ChatPresenter 在新建会话时调用）。 */
    fun resetSession(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        synchronized(lock) { turnsSinceLast.remove(sessionId) }
    }
}
