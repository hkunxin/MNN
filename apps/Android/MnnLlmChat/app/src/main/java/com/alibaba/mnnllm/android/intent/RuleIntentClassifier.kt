// Spec 005 task 8: 规则版意图分类器。
//
// 定位：本期只需「产出可观测的意图」，为后续业务与模型分类器占位。
// 原则：宁可判成 UNKNOWN 也不要误判 —— 后续一旦接业务分支，误判比不判更糟。
package com.alibaba.mnnllm.android.intent

object RuleIntentClassifier : IntentClassifier {

    private val reminder = Regex(
        "提醒我|提醒一下|记得提醒|别忘了|定个?闹钟|叫我起床|remind me",
        RegexOption.IGNORE_CASE
    )
    private val translate = Regex("翻译|译成|译一下|translate", RegexOption.IGNORE_CASE)
    private val search = Regex("搜索|搜一下|搜下|查一下|查查|百度|谷歌|search", RegexOption.IGNORE_CASE)
    private val note = Regex("记一下|记账|花了|支出|收入|备忘|add a note", RegexOption.IGNORE_CASE)
    private val chitchat = Regex(
        "^(你好|您好|hi|hello|嗨|早|早上好|晚上好|晚安|谢谢|多谢|再见|拜拜)[!！。.~～\\s]*$",
        RegexOption.IGNORE_CASE
    )
    private val questionMark = Regex("[?？]")
    private val questionWords = Regex(
        "怎么|怎样|为什么|为何|是什么|什么是|多少|多久|哪里|哪儿|如何|能不能|可不可以|是否|区别"
    )
    private val timeSlot = Regex(
        "(今天|明天|后天|大后天|本周|下周|上午|中午|下午|晚上|凌晨|\\d{1,2}[:：]\\d{2}|\\d{1,2}\\s*点(半)?)"
    )

    override fun classify(text: String): IntentResult {
        val t = text.trim()
        if (t.isEmpty()) return IntentResult(AssistantIntent.UNKNOWN, 0f)

        fun result(intent: AssistantIntent, confidence: Float, slots: Map<String, String> = emptyMap()) =
            IntentResult(intent, confidence, slots, source = "rule")

        // 按「动作性强 → 弱」排序，先命中先返回。
        if (reminder.containsMatchIn(t)) {
            val time = timeSlot.find(t)?.value ?: ""
            return result(
                AssistantIntent.REMINDER,
                if (time.isNotEmpty()) 0.9f else 0.7f,
                if (time.isEmpty()) emptyMap() else mapOf("time" to time)
            )
        }
        if (translate.containsMatchIn(t)) return result(AssistantIntent.TRANSLATE, 0.85f)
        if (search.containsMatchIn(t)) return result(AssistantIntent.SEARCH, 0.8f)
        if (note.containsMatchIn(t)) return result(AssistantIntent.NOTE, 0.75f)
        if (chitchat.containsMatchIn(t)) return result(AssistantIntent.CHITCHAT, 0.9f)
        if (questionMark.containsMatchIn(t) || questionWords.containsMatchIn(t)) {
            return result(AssistantIntent.QA, 0.6f)
        }
        return result(AssistantIntent.UNKNOWN, 0.3f)
    }
}
