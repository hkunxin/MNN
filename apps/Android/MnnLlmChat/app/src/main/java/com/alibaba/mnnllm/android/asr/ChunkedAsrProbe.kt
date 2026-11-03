// Created for Spec 002 (task 8 流式预研): chunked re-inference probe for
// meeting-record (R8) feasibility testing.
//
// Engine finding (omni.cpp): qwen3_asr is one-shot whole-audio — mel fbank +
// encoder + prefill fix audio embeddings into the KV cache; there is no
// incremental audio API (no acceptWaveform/feedAudio anywhere in the engine).
// So "Path A" here emulates streaming by re-running AsrTranscriber.transcribe
// on every intervalSec slice of mic PCM and reporting per-chunk latency (RTF).
// Chunks are cut WITHOUT overlap on purpose: overlap would duplicate text and
// pollute the research data; boundary word cuts are expected and quantified.
// Path B (true streaming contrast) is sherpa-mnn AsrService, wired in
// DebugActivity directly — no changes here.
package com.alibaba.mnnllm.android.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.alibaba.mnnllm.android.llm.AsrTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ChunkedAsrProbe(
    private val context: android.content.Context,
    private val intervalSec: Int,
    private val onChunkResult: (index: Int, audioSec: Double, latencyMs: Long, text: String) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val TAG = "ChunkedAsrProbe"
        private const val SAMPLE_RATE = 16000
    }

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    private val pcmBacklog = java.io.ByteArrayOutputStream()
    private val backlogLock = Any()
    private val chunkIndex = AtomicInteger(0)
    // Plain unbounded BlockingQueue instead of kotlinx Channel: the app's
    // coroutines version predates trySend, and ordering guarantees suffice.
    private val chunkQueue = LinkedBlockingQueue<File>()
    private val queueClosed = AtomicBoolean(false)
    private val workerScope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (isRecording.get()) return
        if (androidx.core.app.ActivityCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onError("RECORD_AUDIO permission not granted")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        )
        if (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
            onError("AudioRecord init failed")
            return
        }
        // Single worker: chunks are transcribed strictly in order. If one chunk
        // takes longer than the interval, later chunks queue up (never dropped)
        // and the growing queue itself is a research signal.
        workerScope.launch {
            while (!queueClosed.get() || !chunkQueue.isEmpty()) {
                val wav = chunkQueue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                try {
                    val t0 = System.currentTimeMillis()
                    val text = AsrTranscriber.transcribe(wav.absolutePath)
                    val latency = System.currentTimeMillis() - t0
                    val audioSec = wav.length().let { it - 44 } / 2.0 / SAMPLE_RATE
                    Log.d(TAG, "chunk #" + chunkIndex.get() + " audio=" + audioSec + "s latency=" + latency + "ms text=" + text)
                    onChunkResult(chunkIndex.get(), audioSec, latency, text)
                    wav.delete()
                    chunkIndex.incrementAndGet()
                } catch (e: Exception) {
                    Log.e(TAG, "transcribe chunk failed", e)
                    onError("chunk transcribe failed: ${e.message}")
                }
            }
        }
        audioRecord?.startRecording()
        isRecording.set(true)
        captureThread = Thread { captureLoop() }.apply { start() }
        Log.i(TAG, "probe started, interval=${intervalSec}s")
    }

    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "audioRecord release", e)
        }
        audioRecord = null
        // Flush a final partial chunk if there is leftover audio.
        flushChunk(final = true)
        queueClosed.set(true) // worker drains remaining chunks then exits
        Log.i(TAG, "probe stopped after ${chunkIndex.get()} chunks")
    }

    /** Number of chunks handed to transcription so far (for caller logging). */
    fun completedChunks(): Int = chunkIndex.get()

    @SuppressLint("MissingPermission") // checked in start()
    private fun captureLoop() {
        val intervalBytes = intervalSec * SAMPLE_RATE * 2
        val buffer = ShortArray(SAMPLE_RATE / 10) // 100ms reads
        while (isRecording.get()) {
            val ret = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (ret <= 0) continue
            val pcm = ByteArray(ret * 2)
            for (i in 0 until ret) {
                pcm[i * 2] = (buffer[i].toInt() and 0xff).toByte()
                pcm[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xff).toByte()
            }
            var boundary = false
            synchronized(backlogLock) {
                pcmBacklog.write(pcm)
                boundary = pcmBacklog.size() >= intervalBytes
            }
            if (boundary) flushChunk(final = false)
        }
    }

    private fun flushChunk(final: Boolean) {
        val bytes: ByteArray = synchronized(backlogLock) {
            val out = pcmBacklog.toByteArray()
            pcmBacklog.reset()
            out
        }
        if (bytes.size < SAMPLE_RATE) return // < 0.5s: discard, too short to be useful
        val index = chunkIndex.get()
        val dir = File(context.cacheDir, "stream_probe").apply { mkdirs() }
        val wav = File(dir, "chunk_$index.wav")
        writeWav(wav, bytes, SAMPLE_RATE)
        chunkQueue.put(wav) // unbounded queue: never overflows
        if (final) {
            Log.d(TAG, "final flush chunk #$index (${bytes.size / 2.0 / SAMPLE_RATE}s)")
        }
    }

    /** Minimal 44-byte WAV header + PCM16 mono data. */
    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int) {
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
        putInt(16, 16) // PCM chunk size
        putShort(20, 1) // audio format = PCM
        putShort(22, 1) // mono
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2) // byte rate = rate * 2 bytes
        putShort(32, 2) // block align
        putShort(34, 16) // bits per sample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        putInt(40, pcm.size)
        file.outputStream().use { out ->
            out.write(header)
            out.write(pcm)
        }
    }
}
