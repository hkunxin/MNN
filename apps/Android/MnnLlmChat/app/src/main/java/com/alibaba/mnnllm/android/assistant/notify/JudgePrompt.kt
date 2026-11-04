package com.alibaba.mnnllm.android.assistant.notify

import org.json.JSONArray
import org.json.JSONObject

/**
 * Few-shot batch-ad-classification prompt for a SMALL on-device instruct LLM
 * (user decision 2026-11-04: model is selectable in settings and likely tiny,
 * so judgment quality comes from EXAMPLES, not parameter count).
 *
 * Output contract: a strict JSON array, one object per input line:
 *   [{"i":1,"ad":1,"conf":0.95,"rule":"限时秒杀"}, ...]
 *   i    = 1-based input index
 *   ad   = 1 advertisement/promotion, 0 normal notification
 *   conf = 0..1 self-reported confidence
 *   rule = a GENERALIZABLE Chinese keyword rule for ads ("" otherwise) —
 *          consumed by task 6 rule-learning, must match content not brand
 */
object JudgePrompt {

    data class Verdict(val index: Int, val ad: Boolean, val conf: Double, val rule: String)

    private const val INSTRUCTION =
        "你是手机通知分类器。判断每条通知是否为广告/营销推广（带货、促销、拉新、活动推送）。\n" +
        "注意：银行/快递/日历/聊天/系统提醒不是广告；验证码不是广告。\n" +
        "只输出一个 JSON 数组，不要输出任何解释。每个输入条目对应一个对象，格式：\n" +
        "[{\"i\":序号,\"ad\":0或1,\"conf\":0到1,\"rule\":\"广告则给一条可泛化的内容关键词规则,否则空字符串\"}]\n" +
        "示例1：\n" +
        "输入：\n" +
        "[1] app=com.taobao title=淘宝特价 text=限时秒杀！全场1折起，手慢无\n" +
        "[2] app=com.tencent.mm title=妈妈 text=今晚回家吃饭吗？\n" +
        "输出：\n" +
        "[{\"i\":1,\"ad\":1,\"conf\":0.95,\"rule\":\"限时秒杀\"},{\"i\":2,\"ad\":0,\"conf\":0.98,\"rule\":\"\"}]\n" +
        "示例2：\n" +
        "输入：\n" +
        "[3] app=com.bank title=建设银行 text=您的验证码是123456，10分钟内有效\n" +
        "[4] app=com.cainiao title=菜鸟裹裹 text=您的快递已到菜鸟驿站，请凭取件码3-2-1001领取\n" +
        "输出：\n" +
        "[{\"i\":3,\"ad\":0,\"conf\":0.99,\"rule\":\"\"},{\"i\":4,\"ad\":0,\"conf\":0.97,\"rule\":\"\"}]\n" +
        "示例3：\n" +
        "输入：\n" +
        "[5] app=com.pinduoduo title=拼多多 text=好友帮你砍了一刀，快去提现100元！\n" +
        "输出：\n" +
        "[{\"i\":5,\"ad\":1,\"conf\":0.99,\"rule\":\"砍一刀|提现\"}]\n" +
        "现在分类以下通知：\n"

    fun build(items: List<PendingQueueStore.Item>): String {
        val sb = StringBuilder(INSTRUCTION)
        for ((idx, it) in items.withIndex()) {
            val title = sanitize(it.title)
            val text = sanitize(it.text)
            sb.append('[').append(idx + 1).append("] app=").append(it.pkg)
            if (!title.isNullOrEmpty()) sb.append(" title=").append(title)
            if (!text.isNullOrEmpty()) sb.append(" text=").append(text)
            sb.append('\n')
        }
        sb.append("输出：\n")
        return sb.toString()
    }

    private fun sanitize(s: String?): String {
        if (s == null) return ""
        // Newlines would break the one-line-per-item contract the examples set.
        return s.replace("\n", " ").replace("\r", " ").take(200)
    }

    /**
     * Parse the model output. Tolerant: extracts the outermost JSON array,
     * maps entries by "i". Returns null when nothing usable — caller retries
     * once, then fails open (treat un-judged as non-ad, R4 降级).
     */
    fun parse(output: String, expectedCount: Int): List<Verdict>? {
        val start = output.indexOf('[')
        val end = output.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            val arr = JSONArray(output.substring(start, end + 1))
            val out = ArrayList<Verdict>(arr.length())
            for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                val i = o.optInt("i", -1)
                if (i < 1 || i > expectedCount) continue
                val conf = when (val c = o.opt("conf")) {
                    is Number -> c.toDouble().coerceIn(0.0, 1.0)
                    else -> 0.5
                }
                out.add(Verdict(
                        index = i - 1,
                        ad = o.optInt("ad", 0) == 1,
                        conf = conf,
                        rule = o.optString("rule", "").take(100)))
            }
            if (out.isEmpty()) null else out
        } catch (t: Throwable) {
            null
        }
    }

    /** Debug helper: show what we would send for a batch. */
    fun preview(items: List<PendingQueueStore.Item>): String = build(items)

    fun errorJson(error: String): String = JSONObject().apply {
        put("ok", false)
        put("error", error)
    }.toString()
}
