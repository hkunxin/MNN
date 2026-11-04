package com.alibaba.mnnllm.android.assistant.notify

import org.json.JSONArray
import org.json.JSONObject

/**
 * Spec-002 task 7/9 prompts for the SAME small on-device LLM as the judge
 * (model selectable, quality from EXAMPLES per the user's 2026-11-04 decision).
 *
 * Both prompts use a strict output contract + worked example, and both have a
 * tolerant parser with a raw-text fallback (R5/R8 降级: unparseable output is
 * shown as plain text instead of failing the screen).
 */
object SummarizePrompt {

    // ------------------------------------------------------------ task 7 摘要

    private const val SUMMARY_INSTRUCTION =
        "你是手机通知摘要器。把下面的通知按类别整理罗列。类别固定为：验证码、日程、社交通信、系统、营销、其他。" +
        "每条通知浓缩为一行要点，保留关键信息（时间、地点、金额、人名），去掉客套话。" +
        "空的类别直接省略。\n" +
        "只输出一个 JSON 对象，格式：\n" +
        "{\"categories\":[{\"name\":\"类别名\",\"items\":[\"要点1\",\"要点2\"]}]}\n" +
        "示例：\n" +
        "输入：\n" +
        "[1] app=com.tencent.mm title=妈妈 text=周六上午一起去医院复查\n" +
        "[2] app=com.alibaba.android.rimet title=钉钉 text=明日9:00 产品评审会，请准时参加\n" +
        "[3] app=com.jingdong.app.mall title=京东 text=爆品返场！全场满300减50，仅限今天\n" +
        "输出：\n" +
        "{\"categories\":[{\"name\":\"日程\",\"items\":[\"妈妈：周六上午去医院复查\",\"钉钉：明日9:00产品评审会\"]},{\"name\":\"营销\",\"items\":[\"京东：满300减50返场，仅限今天\"]}]}\n" +
        "现在整理以下通知：\n"

    fun buildSummary(items: List<DigestStore.Item>): String {
        val sb = StringBuilder(SUMMARY_INSTRUCTION)
        for ((idx, it) in items.withIndex()) {
            sb.append('[').append(idx + 1).append("] app=").append(it.pkg)
            if (!it.title.isNullOrEmpty()) sb.append(" title=").append(sanitize(it.title))
            if (!it.text.isNullOrEmpty()) sb.append(" text=").append(sanitize(it.text))
            sb.append('\n')
        }
        sb.append("输出：\n")
        return sb.toString()
    }

    /**
     * Tolerant parse: outermost {...} with a categories array. null = caller
     * falls back to showing the raw text (降级纯文本).
     */
    fun parseSummary(output: String): List<Pair<String, List<String>>>? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(output.substring(start, end + 1))
            val arr = o.optJSONArray("categories") ?: return null
            val out = ArrayList<Pair<String, List<String>>>()
            for (k in 0 until arr.length()) {
                val c = arr.optJSONObject(k) ?: continue
                val name = c.optString("name", "").trim()
                if (name.isEmpty()) continue
                val items = ArrayList<String>()
                val ia = c.optJSONArray("items") ?: JSONArray()
                for (m in 0 until ia.length()) {
                    val s = ia.optString(m, "").trim()
                    if (s.isNotEmpty()) items.add(s)
                }
                if (items.isNotEmpty()) out.add(name to items)
            }
            if (out.isEmpty()) null else out
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ task 9 纪要

    fun buildMinutes(transcript: String): String {
        return "你是会议纪要助手。根据下面的语音转写整理一份中文会议纪要。\n" +
            "格式（纯文本）：\n" +
            "【会议主题】一句话概括\n" +
            "【主要议题】3-6条要点\n" +
            "【结论与决议】已达成一致的事项（没有写\"无明显决议\"）\n" +
            "【待办事项】谁-做什么（无法判断负责人就写事项本身；没有写\"无\"）\n" +
            "只输出纪要本身，不要解释。\n" +
            "会议转写：\n" + sanitize(transcript, 6000) + "\n纪要：\n"
    }

    private fun sanitize(s: String, max: Int = 200): String {
        if (s == null) return ""
        return s.replace("\n", " ").replace("\r", " ").take(max)
    }

    fun errorJson(error: String): String = JSONObject().apply {
        put("ok", false)
        put("error", error)
    }.toString()
}
