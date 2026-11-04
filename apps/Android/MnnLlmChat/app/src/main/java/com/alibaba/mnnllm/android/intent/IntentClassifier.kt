// Spec 005 task 8: 意图识别骨架。
//
// 本期只要求「有统一入口 + 可替换实现 + 可注册业务处理器」，不落任何业务动作。
// 后续接 LLM 分类器时，调用 IntentRouter.installClassifier(...) 即可，页面代码不用动。
package com.alibaba.mnnllm.android.intent

import java.util.concurrent.CopyOnWriteArrayList

/** 助理可识别的意图类型；新增类型只在这里加，不散落在业务代码里。 */
enum class AssistantIntent(val label: String) {
    QA("qa"),
    REMINDER("reminder"),
    TRANSLATE("translate"),
    SEARCH("search"),
    NOTE("note"),
    CHITCHAT("chitchat"),
    UNKNOWN("unknown")
}

/**
 * 一次分类结果。
 * @param slots 轻量槽位（如提醒时间），本期规则版只做时间粗提取。
 * @param source 产生结果的分类器标识，便于日志区分 rule / llm。
 */
data class IntentResult(
    val intent: AssistantIntent,
    val confidence: Float,
    val slots: Map<String, String> = emptyMap(),
    val source: String = "rule"
)

interface IntentClassifier {
    fun classify(text: String): IntentResult
}

/** 业务处理器。返回 true 表示已消费该消息，不再走普通对话。 */
interface IntentHandler {
    val supported: Set<AssistantIntent>
    fun handle(text: String, result: IntentResult): Boolean
}

object IntentRouter {

    @Volatile
    private var classifier: IntentClassifier = RuleIntentClassifier

    private val handlers = CopyOnWriteArrayList<IntentHandler>()

    fun classifier(): IntentClassifier = classifier

    /** 后续替换为模型分类器时的唯一入口。 */
    fun installClassifier(newClassifier: IntentClassifier) {
        classifier = newClassifier
    }

    fun register(handler: IntentHandler) {
        handlers.addIfAbsent(handler)
    }

    fun unregister(handler: IntentHandler) {
        handlers.remove(handler)
    }

    /**
     * 把消息派发给已注册的处理器。
     * 本期没有注册任何处理器，dispatch 恒返回 false —— 对话行为与改造前完全一致。
     */
    fun dispatch(text: String, result: IntentResult): Boolean =
        handlers.any { handler ->
            result.intent in handler.supported &&
                runCatching { handler.handle(text, result) }.getOrDefault(false)
        }
}
