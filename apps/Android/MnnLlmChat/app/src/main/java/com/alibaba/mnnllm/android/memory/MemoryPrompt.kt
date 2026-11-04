// Spec 005 task 7: 自动记忆抽取的 prompt 与容错解析。
//
// 与 JudgePrompt / SummarizePrompt 同一套约定：few-shot、严格 JSON 契约、解析失败即降级。
// 抽取的是「跨会话仍然成立」的用户事实与偏好，不是本轮任务内容。
package com.alibaba.mnnllm.android.memory

import org.json.JSONArray
import org.json.JSONObject

/** 抽取结果的一条候选记忆。 */
data class MemoryCandidate(val content: String, val tags: List<String>)

object MemoryPrompt {

    private const val MAX_CONTENT_CHARS = 80
    private const val MAX_ITEMS = 5
    private const val MAX_TURN_CHARS = 400

    /**
     * @param turns 最近若干轮对话（role = "user"/"assistant"）
     * @param existing 已记住的内容，用于让模型避开重复
     */
    fun build(turns: List<Pair<String, String>>, existing: List<String>): String {
        val sb = StringBuilder()
        sb.append("你是一个记忆抽取器。从对话中提取值得长期记住的用户事实与偏好。\n")
        sb.append("只提取跨会话依然成立的内容，例如称呼、身份、地点、作息、饮食禁忌、沟通偏好。\n")
        sb.append("不要提取：本次任务的具体内容、临时情绪、助手自己说的话、密码/验证码/银行卡等凭据。\n")
        sb.append("每条不超过 ${MAX_CONTENT_CHARS} 字，用客观陈述句。没有可记的内容就输出 []。\n")
        sb.append("只输出 JSON 数组，不要解释，不要代码块。\n\n")
        sb.append("已有记忆（不要重复）：\n")
        if (existing.isEmpty()) {
            sb.append("- （空）\n")
        } else {
            existing.take(40).forEach { sb.append("- ").append(it).append('\n') }
        }
        sb.append("\n对话：\n")
        turns.takeLast(8).forEach { (role, text) ->
            val who = if (role == "user") "用户" else "助手"
            val body = text.replace("\n", " ").take(MAX_TURN_CHARS)
            sb.append(who).append("：").append(body).append('\n')
        }
        sb.append("\n示例输出：\n")
        sb.append("""[{"content":"用户希望被称呼为老板","tags":["称呼"]},{"content":"用户偏好简短直接的回答","tags":["偏好"]}]""")
        sb.append('\n')
        return sb.toString()
    }

    /**
     * 容错解析：模型常带 ```json 包裹或前后废话，取首个 '[' 到最后一个 ']' 再解析；
     * 失败返回空列表（调用方按「本轮无新记忆」处理，不影响对话）。
     */
    fun parse(raw: String): List<MemoryCandidate> {
        if (raw.isBlank()) return emptyList()
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val body = raw.substring(start, end + 1)
        val arr = try {
            JSONArray(body)
        } catch (t: Throwable) {
            return emptyList()
        }
        val out = ArrayList<MemoryCandidate>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("content").trim()
                .replace("\n", " ")
                .take(MAX_CONTENT_CHARS)
            if (content.length < 3) continue
            val tags = o.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).map { tags.optString(it).trim() }.filter { it.isNotEmpty() }
            } ?: emptyList()
            out.add(MemoryCandidate(content, tags))
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    /** 服务端把候选转成回包 JSON。 */
    fun toJson(candidates: List<MemoryCandidate>): JSONArray = JSONArray().apply {
        candidates.forEach { c ->
            put(JSONObject().apply {
                put("content", c.content)
                put("tags", JSONArray(c.tags))
            })
        }
    }
}
