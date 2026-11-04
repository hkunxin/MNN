package com.alibaba.mnnllm.android.assistant.notify

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Spec-002 task 4 executor wiring [InferenceScheduler] to the ":llm" process
 * judgment service. Runs in the MAIN process; the model never does.
 *
 * Contract with the scheduler: [execute] takes OWNERSHIP of the batch
 * immediately (returns true) and hands it to a worker thread — the scheduler
 * runs on a broadcast receiver thread and must not block on a multi-minute
 * generation. Any failure (no model set, service crash, timeout, unparsable
 * verdicts) re-enqueues the whole batch: fail-open, nothing is lost, and an
 * un-judged notification stays visible (worst case = current mnblocker
 * behavior, never worse).
 *
 * Verdict application:
 *   ad=1  conf>=threshold -> cancel via the bound listener + BlockLogStore
 *   ad=1  conf< threshold -> keep + log (safety gate; task 6 adds suggest mode)
 *   ad=0                  -> DigestStore (verification codes rejected there)
 *   ad=1  rule!=""        -> appended to assistant_rule_candidates.jsonl for task 6
 */
class LlmJudgeExecutor private constructor(
        private val appContext: Context,
        private val digestStore: DigestStore,
        private val blockLogStore: BlockLogStore,
        private val learnedRules: LearnedRuleStore) : InferenceScheduler.BatchExecutor {

    override fun name(): String {
        val model = AssistantNotifyConfig.judgeModelIdStatic(appContext)
        return if (model.isNullOrEmpty()) "llm-judge(no model set)" else "llm-judge($model)"
    }

    override fun execute(items: List<PendingQueueStore.Item>): Boolean {
        thread(name = "assistant-judge-exec") {
            runBatch(items)
        }
        return true // ownership taken; worker decides re-queue on failure
    }

    private fun runBatch(items: List<PendingQueueStore.Item>) {
        val modelId = AssistantNotifyConfig.judgeModelIdStatic(appContext)
        if (modelId.isNullOrEmpty()) {
            Log.w(TAG, "gated: judge model not set — re-queue "
                    + items.size + " item(s) (set it in Debug > Notify Assistant)")
            requeue(items)
            return
        }
        val configPath = com.alibaba.mnnllm.android.model.ModelUtils
                .getConfigPathForModel(modelId)
        if (configPath == null || !File(configPath).exists()) {
            Log.w(TAG, "gated: judge model '$modelId' not installed — re-queue")
            requeue(items)
            return
        }

        try {
            val req = JSONObject()
            req.put("mode", "judge")
            req.put("modelId", modelId)
            req.put("configPath", configPath)
            val arr = JSONArray()
            for (it in items) {
                arr.put(JSONObject().apply {
                    put("key", it.key); put("pkg", it.pkg)
                    put("title", it.title); put("text", it.text)
                    put("postedAt", it.postedAt)
                })
            }
            req.put("items", arr)
            val result = AssistantLlmClient.request(appContext, req, TIMEOUT_MS)
            if (result == null) {
                Log.w(TAG, "judge request failed/timeout — re-queue " + items.size)
                requeue(items)
                return
            }
            if (!result.optBoolean("ok", false)) {
                // R4 降级: unparsable after retry — fail open as non-ad, but still
                // feed the digest so summarization works without ad judgment.
                Log.w(TAG, "judge failed: " + result.optString("error", "?") + " — fail open")
                failOpen(items)
                return
            }
            applyVerdicts(items, result)
        } catch (t: Throwable) {
            Log.w(TAG, "judge batch crashed — re-queue", t)
            requeue(items)
        }
    }

    private fun applyVerdicts(items: List<PendingQueueStore.Item>, result: JSONObject) {
        val verdicts = result.optJSONArray("verdicts") ?: JSONArray()
        val threshold = AssistantNotifyConfig.adConfidenceThresholdStatic(appContext)
        var cancelled = 0
        var digested = 0
        var lowConf = 0
        val applied = BooleanArray(items.size)
        for (k in 0 until verdicts.length()) {
            val v = verdicts.optJSONObject(k) ?: continue
            val idx = v.optInt("index", -1)
            if (idx < 0 || idx >= items.size || applied[idx]) continue
            applied[idx] = true
            val it = items[idx]
            if (v.optBoolean("ad", false)) {
                val conf = v.optDouble("conf", 0.5)
                val rule = v.optString("rule", "")
                if (conf >= threshold) {
                    if (SmartNotificationListener.cancelByKey(it.key)) {
                        cancelled++
                        // Task 5: record the retract latency (postedAt -> cancel)
                        // alongside the block, for the task 10 audit trail.
                        val delaySec = (System.currentTimeMillis() - it.postedAt) / 1000
                        blockLogStore.record(it.pkg, it.title, it.text,
                                "llm-ad conf=" + "%.2f".format(conf)
                                        + " delay=" + delaySec + "s")
                        if (rule.isNotEmpty()) {
                            appendCandidateRule(rule, it, conf)
                        }
                    } else {
                        // Notification already gone / listener unbound: judged
                        // and dropped — do NOT re-queue (stale notification).
                        cancelled++
                        Log.i(TAG, "cancel skipped (key stale) pkg=" + it.pkg)
                    }
                } else {
                    lowConf++
                    Log.i(TAG, "ad below threshold conf=" + conf
                            + " pkg=" + it.pkg + " kept (task6 suggest mode)")
                }
            } else {
                if (digestStore.add(it.pkg, it.title, it.text, it.postedAt)) digested++
            }
        }
        // Any item WITHOUT a verdict (model skipped it) fails open to digest.
        for (idx in items.indices) {
            if (!applied[idx]) {
                val it = items[idx]
                if (digestStore.add(it.pkg, it.title, it.text, it.postedAt)) digested++
            }
        }
        Log.i(TAG, "verdicts applied n=" + items.size + " cancelled=" + cancelled
                + " digested=" + digested + " lowConf=" + lowConf)
    }

    private fun failOpen(items: List<PendingQueueStore.Item>) {
        var digested = 0
        for (it in items) {
            if (digestStore.add(it.pkg, it.title, it.text, it.postedAt)) digested++
        }
        Log.i(TAG, "fail-open digested=" + digested + "/" + items.size)
    }

    private fun requeue(items: List<PendingQueueStore.Item>) {
        val queue = PendingQueueStore(appContext)
        for (it in items) queue.enqueue(it)
        Log.i(TAG, "re-queued " + items.size + " item(s)")
    }

    private fun appendCandidateRule(rule: String, it: PendingQueueStore.Item, conf: Double) {
        // Task 6: the store decides pending-vs-auto-confirm by the silent
        // threshold; the JSONL append stays as an append-only audit trail.
        learnedRules.addCandidate(rule, it.pkg, it.title, conf,
                AssistantNotifyConfig.ruleAutoConfirmThresholdStatic(appContext))
        try {
            val f = File(appContext.filesDir, CANDIDATES_FILE)
            val entry = JSONObject().apply {
                put("rule", rule)
                put("pkg", it.pkg)
                put("sample", (it.title ?: "").take(60))
                put("conf", conf)
                put("at", System.currentTimeMillis())
            }
            FileOutputStream(f, true).use { fos ->
                fos.write((entry.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "candidate rule append failed", t)
        }
    }

    companion object {
        private const val TAG = "SmartNotify"
        const val CANDIDATES_FILE = "assistant_rule_candidates.jsonl"
        /** Multi-minute generations are normal on small phones; 20 min cap. */
        private const val TIMEOUT_MS = 20L * 60 * 1000

        @Volatile private var instance: LlmJudgeExecutor? = null

        @JvmStatic
        fun attach(context: Context, digestStore: DigestStore, blockLogStore: BlockLogStore,
                   learnedRules: LearnedRuleStore) {
            if (instance == null) {
                instance = LlmJudgeExecutor(
                        context.applicationContext, digestStore, blockLogStore, learnedRules)
            }
            InferenceScheduler.setExecutor(instance)
        }
    }
}
