// Created for Spec 001 (R3): dual-model pipeline — transcribe recorded WAV with a
// dedicated Qwen3-ASR session and return the text for the chat input box.
package com.alibaba.mnnllm.android.llm

import android.util.Log
import com.alibaba.mnnllm.android.model.ModelTypeUtils
import com.alibaba.mnnllm.android.modelist.LocalModelsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object AsrTranscriber {

    private const val TAG = "AsrTranscriber"

    /**
     * Memory strategy (Spec 001 内存风险项):
     * 1. use_mmap=true → weights are file-backed pages; the OS can reclaim them
     *    under pressure, so "resident" cost is soft page cache rather than hard RSS.
     * 2. use_cached_mmap (engine default on) + mmap_dir → the first load writes a
     *    preprocessed cache, so subsequent loads are fast (no cold full re-parse).
     * 3. Idle auto-release → after IDLE_RELEASE_MS without a transcription the
     *    session is freed; next use reloads from the mmap cache (fast).
     */
    private const val IDLE_RELEASE_MS = 5 * 60 * 1000L

    /** Cached ASR model: modelId to model directory path. */
    @Volatile
    private var cachedAsrModel: Pair<String, String>? = null

    /** Persistent helper session for the ASR model (loaded on first use). */
    private var asrSession: LlmSession? = null

    private val releaseScope = CoroutineScope(Dispatchers.Default)
    private var idleReleaseJob: Job? = null

    fun hasAsrModel(): Boolean = findAsrModel() != null

    fun refreshAsrModel() {
        synchronized(this) { cachedAsrModel = null }
    }

    /**
     * Find an imported local audio model usable for ASR. Prefers directories whose
     * name contains "asr" (e.g. Qwen3-ASR-0.6B-MNN-audio). Returns modelId to dir path.
     */
    private fun findAsrModel(): Pair<String, String>? {
        cachedAsrModel?.let { return it }
        synchronized(this) {
            cachedAsrModel?.let { return it }
            val candidates = try {
                LocalModelsProvider.getLocalModels()
            } catch (e: Exception) {
                Log.w(TAG, "findAsrModel: cannot list local models", e)
                return null
            }.filter { item ->
                val path = item.localPath ?: return@filter false
                File(path, "config.json").exists()
            }.filter { item ->
                ModelTypeUtils.isAudioModel(item.modelId ?: "")
            }.sortedByDescending { item ->
                (item.modelId ?: "").lowercase().contains("asr")
            }
            val first = candidates.firstOrNull() ?: return null
            val pair = (first.modelId ?: "") to (first.localPath ?: return null)
            Log.d(TAG, "findAsrModel: using ${pair.first} at ${pair.second}")
            cachedAsrModel = pair
            return pair
        }
    }

    /**
     * Transcribe a WAV file with the ASR session. Blocks until the full text is
     * produced; call from a background dispatcher.
     */
    suspend fun transcribe(wavPath: String): String = withContext(Dispatchers.IO) {
        val (modelId, modelPath) = findAsrModel()
            ?: throw IllegalStateException("No imported ASR model found")
        val session = ensureSession(modelId, modelPath)
        val collected = StringBuilder()
        val result = session.generate(
            "<audio>$wavPath</audio>",
            mapOf(),
            object : GenerateProgressListener {
                override fun onProgress(progress: String?): Boolean {
                    progress?.let { collected.append(it) }
                    return false
                }
            }
        )
        @Suppress("UNCHECKED_CAST")
        val response = result["response"] as? String
        val text = (response ?: collected.toString()).trim()
        Log.d(TAG, "transcribe: wav=$wavPath text=$text")
        scheduleIdleRelease()
        return@withContext text
    }

    private fun ensureSession(modelId: String, modelPath: String): LlmSession {
        synchronized(this) {
            asrSession?.let { existing ->
                if (existing.isModelLoaded()) {
                    return existing
                }
                existing.release()
                asrSession = null
            }
            Log.d(TAG, "ensureSession: loading ASR model $modelId")
            val session = LlmSession(
                modelId,
                "asr_helper_" + System.currentTimeMillis(),
                modelPath + "/config.json",
                null,
                null,
                useCustomConfig = false,
                configOverrides = mapOf("use_mmap" to true)
            )
            session.load()
            asrSession = session
            return session
        }
    }

    private fun scheduleIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = releaseScope.launch {
            delay(IDLE_RELEASE_MS)
            Log.d(TAG, "idle release: freeing ASR session after ${IDLE_RELEASE_MS / 1000}s idle")
            release()
        }
    }

    fun release() {
        synchronized(this) {
            idleReleaseJob?.cancel()
            idleReleaseJob = null
            asrSession?.release()
            asrSession = null
        }
    }
}
