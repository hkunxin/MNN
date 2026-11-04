package com.alibaba.mnnllm.android.assistant.notify

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.alibaba.mnnllm.android.llm.ChatService
import com.alibaba.mnnllm.android.llm.LlmSession
import com.alibaba.mnnllm.android.model.ModelTypeUtils
import com.alibaba.mnnllm.android.model.ModelUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Spec-002 task 4: batch ad judgment running in the dedicated ":llm" process
 * (design.md: the ~1GB model must never live in the resident notification
 * listener process — root-cause isolation, not a workaround).
 *
 * Protocol (file-based, survives either process dying mid-flight):
 *   main process writes  filesDir/assistant_judge_request.json
 *   this service writes  filesDir/assistant_judge_result.json
 *
 * Lifecycle & memory (R6):
 *   - session is created lazily on the first request and REUSED across
 *     batches (prefill dominates on small models);
 *   - a model change tears the old session down before the new one loads;
 *   - SCREEN_OFF schedules a release in 10 min (user idle assumption),
 *     SCREEN_ON cancels the pending release. Next batch lazily reloads.
 */
class AssistantJudgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Single-flight: a second start while busy is dropped; the executor
        // polls the result file and will simply time out — acceptable since
        // the scheduler only fires every >=30min.
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "judge request dropped — already running")
            return START_NOT_STICKY
        }
        thread(name = "assistant-judge") {
            try {
                runOnce()
            } catch (t: Throwable) {
                Log.w(TAG, "judge run failed", t)
                writeResult(JudgePrompt.errorJson("service crash: ${t.message}"))
            } finally {
                running.set(false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runOnce() {
        val requestFile = File(filesDir, REQUEST_FILE)
        val resultFile = File(filesDir, RESULT_FILE)
        if (!requestFile.exists()) {
            writeResult(JudgePrompt.errorJson("no request file"))
            return
        }
        val req = try {
            JSONObject(requestFile.readText())
        } catch (t: Throwable) {
            writeResult(JudgePrompt.errorJson("bad request json: ${t.message}"))
            return
        }
        val modelId = req.optString("modelId", "")
        if (modelId.isEmpty()) {
            writeResult(JudgePrompt.errorJson("empty modelId"))
            return
        }
        val items = req.optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) {
            writeResult(JudgePrompt.errorJson("empty items"))
            return
        }

        val t0 = System.currentTimeMillis()
        val session = ensureSession(modelId) ?: run {
            writeResult(JudgePrompt.errorJson("session init failed for $modelId"))
            return
        }

        // Rebuild the input items for the prompt (index-aligned with request).
        val list = ArrayList<PendingQueueStore.Item>(items.length())
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            list.add(PendingQueueStore.Item(
                    o.optString("key"), o.optString("pkg"),
                    o.optString("title"), o.optString("text"),
                    o.optLong("postedAt")))
        }

        var raw = generateText(session, JudgePrompt.build(list))
        var verdicts = JudgePrompt.parse(raw, list.size)
        if (verdicts == null) {
            // R4: one retry after a hard reset of the (keepHistory=false
            // anyway) context, then degrade — executor fails open.
            Log.w(TAG, "verdict parse failed — retry once")
            runCatching { session.reset() }
            raw = generateText(session, JudgePrompt.build(list))
            verdicts = JudgePrompt.parse(raw, list.size)
        }

        val out = JSONObject()
        out.put("ok", verdicts != null)
        out.put("modelId", modelId)
        out.put("costMs", System.currentTimeMillis() - t0)
        out.put("raw", raw.take(4000))
        val arr = JSONArray()
        verdicts?.forEach { v ->
            arr.put(JSONObject().apply {
                put("index", v.index)
                put("ad", v.ad)
                put("conf", v.conf)
                put("rule", v.rule)
            })
        }
        out.put("verdicts", arr)
        out.put("parsed", verdicts != null)
        resultFile.writeText(out.toString())
        requestFile.delete()
        Log.i(TAG, "judge done model=$modelId n=" + list.size
                + " costMs=" + out.getLong("costMs") + " parsed=" + (verdicts != null))
    }

    private fun writeResult(json: String) {
        try {
            File(filesDir, RESULT_FILE).writeText(json)
        } catch (t: Throwable) {
            Log.w(TAG, "result write failed", t)
        }
    }

    private fun generateText(session: LlmSession, prompt: String): String {
        val sb = StringBuilder()
        val listener = object : com.alibaba.mnnllm.android.llm.GenerateProgressListener {
            override fun onProgress(progress: String?): Boolean {
                if (progress != null) sb.append(progress)
                return false // never stop early
            }
        }
        val result = session.generate(prompt, emptyMap(), listener)
        val ok = result["success"]
        if (ok == false) Log.w(TAG, "generate reported failure: $result")
        return sb.toString()
    }

    // ------------------------------------------------------- session mgmt

    private fun ensureSession(modelId: String): LlmSession? {
        synchronized(sessionLock) {
            if (session != null && sessionModelId == modelId && session?.isModelLoaded() == true) {
                return session
            }
            session?.runCatching { release() }
            session = null
            val configPath = ModelUtils.getConfigPathForModel(modelId)
            if (configPath == null || !File(configPath).exists()) {
                Log.w(TAG, "judge model config not found: $modelId")
                return null
            }
            return try {
                val s = ChatService.provide().createLlmSession(
                        modelId, configPath,
                        sessionIdParam = "assistant_judge_${System.currentTimeMillis()}",
                        chatDataItemList = null,
                        supportOmni = ModelTypeUtils.isOmni(modelId),
                        useCustomConfig = true)
                s.setKeepHistory(false) // stateless batches
                s.load()
                session = s
                sessionModelId = modelId
                Log.i(TAG, "judge session loaded: $modelId")
                s
            } catch (t: Throwable) {
                Log.w(TAG, "judge session load failed: $modelId", t)
                null
            }
        }
    }

    private fun releaseSession() {
        synchronized(sessionLock) {
            session?.runCatching { release() }
            session = null
            sessionModelId = null
        }
        Log.i(TAG, "judge session released (R6 idle)")
    }

    // ------------------------------------------------------- R6 idle release

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenReceiver, filter)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        releaseSession()
        super.onDestroy()
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.postDelayed({ releaseSession() }, IDLE_RELEASE_MS)
                    Log.d(TAG, "screen off — release scheduled in ${IDLE_RELEASE_MS / 60000}min")
                }
                Intent.ACTION_SCREEN_ON -> handler.removeCallbacksAndMessages(null)
            }
        }
    }

    companion object {
        private const val TAG = "SmartNotify"
        const val REQUEST_FILE = "assistant_judge_request.json"
        const val RESULT_FILE = "assistant_judge_result.json"
        /** R6: default idle timeout before dropping the model from memory. */
        const val IDLE_RELEASE_MS = 10L * 60 * 1000

        private val sessionLock = Any()
        @Volatile private var session: LlmSession? = null
        @Volatile private var sessionModelId: String? = null
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)
        private val handler = Handler(Looper.getMainLooper())
    }
}
