package com.alibaba.mnnllm.android.assistant.notify

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Spec-002 task 7/9: single client for the file-exchange protocol against the
 * ":llm" process [AssistantJudgeService]. Used by:
 *   - LlmJudgeExecutor  (mode="judge", task 4 batch ad judgment)
 *   - SummaryActivity   (mode="summary", task 7 digest summarization)
 *   - MeetingActivity   (mode="minutes", task 9 meeting minutes)
 *
 * Every request carries a unique reqId; the service echoes it in the result.
 * A client only consumes a result whose reqId matches, so a stale result from
 * a previous/other request is skipped (and removed) instead of mis-applied.
 *
 * Blocking poll — callers MUST invoke from a background thread.
 */
object AssistantLlmClient {

    private const val TAG = "SmartNotify"

    /** Multi-minute generations are normal on small phones; 20 min cap. */
    const val TIMEOUT_MS = 20L * 60 * 1000
    private const val POLL_MS = 2000L

    private val seq = AtomicLong(0)

    /**
     * Write the request, start the judge service, poll for the result.
     * @return the result JSON with matching reqId, or null on timeout/start failure.
     */
    fun request(context: Context, payload: JSONObject, timeoutMs: Long = TIMEOUT_MS): JSONObject? {
        val appContext = context.applicationContext
        val reqId = System.currentTimeMillis().toString(36) + "-" + seq.incrementAndGet()
        val requestFile = File(appContext.filesDir, AssistantJudgeService.REQUEST_FILE)
        val resultFile = File(appContext.filesDir, AssistantJudgeService.RESULT_FILE)

        payload.put("reqId", reqId)
        // Stale-result protection: our reqId filter tolerates leftovers anyway,
        // but deleting up front keeps the common path fast.
        resultFile.delete()
        try {
            FileOutputStream(requestFile).use { fos ->
                fos.write(payload.toString().toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "llm request write failed", t)
            return null
        }

        val started = try {
            appContext.startService(Intent(appContext, AssistantJudgeService::class.java))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "judge service start failed", t)
            false
        }
        if (!started) return null

        // The service lives in another process; poll the result file.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            if (!resultFile.exists()) continue
            val json: JSONObject = try {
                JSONObject(resultFile.readText())
            } catch (t: Throwable) {
                Log.w(TAG, "result read failed (partial write?)", t)
                continue // may be mid-write; wait for a complete file
            }
            if (json.optString("reqId") != reqId) {
                continue // stale result from another request; leave it be
            }
            resultFile.delete()
            return json
        }
        Log.w(TAG, "llm request timeout (${timeoutMs / 1000}s) reqId=$reqId")
        return null
    }
}
