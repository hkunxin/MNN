// Spec-002 task 9 (R8): production streaming ASR session for meeting records.
//
// Engine reality (design.md 降级决策): qwen3_asr has NO incremental audio API
// (omni.cpp), so "streaming" is 5s non-overlapping chunked re-inference via
// [AsrTranscriber]. Each finished chunk is a CONFIRMED sentence; while a chunk
// is in flight the UI shows the 半句刷新区 as "识别中…". sherpa-mnn true
// streaming remains the documented fallback/contrast path (task 8 probe).
package com.alibaba.mnnllm.android.assistant.notify.meeting

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.alibaba.mnnllm.android.llm.AsrTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamingAsrSession(
    private val context: Context,
    private val onSentence: (text: String, elapsedMs: Long) -> Unit,
    private val onBusyChanged: (busy: Boolean) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val TAG = "MeetingAsr"
        private const val SAMPLE_RATE = 16000
        /** R8 定稿：5s 无重叠切片。 */
        private const val CHUNK_SEC = 5
    }

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val workerScope = CoroutineScope(Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val backlog = ByteArrayOutputStream()
    private val backlogLock = Any()
    private val chunkQueue = LinkedBlockingQueue<File>()
    private val queueClosed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    /** Wall-clock anchor for sentence timestamps (meeting elapsed time). */
    var startedAtMs: Long = 0
        private set
    private var pausedTotalMs = 0L
    private var pausedAtMs = 0L
    private var lastStopAtMs = 0L

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.get()) return
        if (androidx.core.app.ActivityCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onError("缺少录音权限")
            return
        }
        if (!AsrTranscriber.hasAsrModel()) {
            onError("未导入 ASR 模型（需要 Qwen3-ASR 音频模型）")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            onError("AudioRecord 初始化失败")
            return
        }
        startedAtMs = System.currentTimeMillis()
        pausedTotalMs = 0
        queueClosed.set(false)
        workerScope.launch { workerLoop() }
        audioRecord?.startRecording()
        running.set(true)
        paused.set(false)
        captureThread = Thread { captureLoop() }.apply { start() }
        Log.i(TAG, "session started (chunk=${CHUNK_SEC}s)")
    }

    fun pause() {
        if (!running.get() || paused.get()) return
        paused.set(true)
        pausedAtMs = System.currentTimeMillis()
        Log.d(TAG, "paused")
    }

    fun resume() {
        if (!running.get() || !paused.get()) return
        pausedTotalMs += System.currentTimeMillis() - pausedAtMs
        paused.set(false)
        Log.d(TAG, "resumed")
    }

    /** Stop capture, flush the tail chunk, and drain pending transcription. */
    fun stop() {
        if (!running.get()) return
        running.set(false)
        if (paused.get()) pausedTotalMs += System.currentTimeMillis() - pausedAtMs
        lastStopAtMs = System.currentTimeMillis()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "audioRecord release", e)
        }
        audioRecord = null
        flushChunk(final = true)
        queueClosed.set(true) // worker drains then exits
        Log.i(TAG, "session stopped")
    }

    fun elapsedMs(): Long {
        if (startedAtMs == 0L) return 0
        val now = when {
            paused.get() -> pausedAtMs
            running.get() -> System.currentTimeMillis()
            else -> lastStopAtMs
        }
        return (now - startedAtMs - pausedTotalMs).coerceAtLeast(0)
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun captureLoop() {
        val intervalBytes = CHUNK_SEC * SAMPLE_RATE * 2
        val buffer = ShortArray(SAMPLE_RATE / 10) // 100ms reads
        while (running.get()) {
            if (paused.get()) {
                Thread.sleep(100)
                continue
            }
            val ret = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (ret <= 0) continue
            val pcm = ByteArray(ret * 2)
            for (i in 0 until ret) {
                pcm[i * 2] = (buffer[i].toInt() and 0xff).toByte()
                pcm[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xff).toByte()
            }
            var boundary = false
            synchronized(backlogLock) {
                backlog.write(pcm)
                boundary = backlog.size() >= intervalBytes
            }
            if (boundary) flushChunk(final = false)
        }
    }

    private fun flushChunk(final: Boolean) {
        val bytes: ByteArray = synchronized(backlogLock) {
            val out = backlog.toByteArray()
            backlog.reset()
            out
        }
        if (bytes.size < SAMPLE_RATE) return // <0.5s 尾巴：丢弃
        val dir = File(context.cacheDir, "meeting_chunks").apply { mkdirs() }
        val wav = File(dir, "chunk_" + System.nanoTime() + ".wav")
        writeWav(wav, bytes)
        chunkQueue.put(wav)
        if (!final) {
            busy.set(true)
            main.post { onBusyChanged(true) }
        }
    }

    private suspend fun workerLoop() {
        while (!queueClosed.get() || !chunkQueue.isEmpty()) {
            val wav = chunkQueue.poll(300, TimeUnit.MILLISECONDS) ?: continue
            try {
                val t0 = System.currentTimeMillis()
                val text = AsrTranscriber.transcribe(wav.absolutePath).trim()
                wav.delete()
                if (text.isNotEmpty()) {
                    val elapsed = t0 - startedAtMs
                    Log.d(TAG, "sentence: $text (${elapsed}ms)")
                    main.post { onSentence(text, elapsed) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "transcribe chunk failed", e)
                main.post { onError("转写失败: ${e.message}") }
            } finally {
                busy.set(false)
                main.post { onBusyChanged(false) }
            }
        }
    }

    /** Minimal 44-byte WAV header + PCM16 mono data. */
    private fun writeWav(file: File, pcm: ByteArray) {
        val header = ByteArray(44)
        val totalDataLen = pcm.size + 36
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
            header[off + 2] = ((v shr 16) and 0xff).toByte()
            header[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        putInt(4, totalDataLen)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2)
        putShort(32, 2)
        putShort(34, 16)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        putInt(40, pcm.size)
        file.outputStream().use { out ->
            out.write(header)
            out.write(pcm)
        }
    }
}
