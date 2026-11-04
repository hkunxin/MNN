// Created by ruoyi.sjd on 2025/3/12.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.android.debug

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.asr.AsrService
import com.alibaba.mnnllm.android.asr.ChunkedAsrProbe
import com.alibaba.mnnllm.android.audio.AudioChunksPlayer
import com.alibaba.mnnllm.android.utils.VoiceModelPathUtils
import com.alibaba.mnnllm.android.utils.PreferenceUtils
import com.alibaba.mnnllm.android.BuildConfig
import com.alibaba.mnnllm.android.modelist.ModelListManager
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.taobao.meta.avatar.tts.TtsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TestCase(
    val id: String,
    val title: String,
    val layoutResId: Int
)

class DebugActivity : AppCompatActivity() {

    companion object {
        const val TAG = "DebugActivity"
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 1001
        private const val KEY_SHOW_MODEL_INFO_ENABLED = "debug_show_model_info_enabled"
        private const val KEY_ALLOW_NETWORK_MARKET_DATA = "debug_allow_network_market_data"
        private const val KEY_ENABLE_NETWORK_DELAY = "debug_enable_network_delay"

        @JvmStatic
        fun isShowModelInfoEnabled(context: android.content.Context): Boolean {
            return PreferenceUtils.getBoolean(context, KEY_SHOW_MODEL_INFO_ENABLED, false)
        }

        @JvmStatic
        fun isNetworkDelayEnabled(context: android.content.Context): Boolean {
            return PreferenceUtils.getBoolean(context, KEY_ENABLE_NETWORK_DELAY, false)
        }

        @JvmStatic
        fun checkRepoUpdates(context: android.content.Context, callback: (Boolean, String?) -> Unit) {
            CoroutineScope(Dispatchers.IO).launch {
//                delay(300)
            }
        }
    }

    private lateinit var scrollView: ScrollView
    private lateinit var logTextView: TextView
    private lateinit var testCaseSpinner: Spinner
    private lateinit var testCaseContainer: FrameLayout
    private lateinit var clearLogButton: Button
    private lateinit var copyLogButton: Button
    private lateinit var homeCrashlyticsNonFatalButton: Button
    private lateinit var homeCrashlyticsFatalButton: Button

    // Test case views - will be initialized when layouts are loaded
    private var asrTestButton: Button? = null
    private var ttsTestButton: Button? = null
    private var ttsInputText: EditText? = null
    private var ttsProcessButton: Button? = null
    private var showModelInfoSwitch: Switch? = null
    private var allowNetworkSwitch: Switch? = null
    private var networkDelaySwitch: Switch? = null
    private var closeDebugModeButton: Button? = null
    private var testCrashlyticsNonFatalButton: Button? = null
    private var testCrashlyticsFatalButton: Button? = null

    private var recognizeService: AsrService? = null
    private var isRecording = false

    // Spec 002 task 8 流式预研: dual-path probe state
    private var chunkedProbe: ChunkedAsrProbe? = null
    private var probeBService: AsrService? = null
    private var ttsService: TtsService? = null
    private var audioPlayer: AudioChunksPlayer? = null
    private var isTtsInitialized = false

    private val testCases = listOf(
        TestCase("asr", "ASR Test", R.layout.debug_test_asr),
        TestCase("streamAsr", "Streaming ASR Research", R.layout.debug_test_stream_asr),
        TestCase("notify", "Notify Assistant", R.layout.debug_test_notify),
        TestCase("tts", "TTS Test", R.layout.debug_test_tts),
        TestCase("scan", "Model Scan Test", R.layout.debug_test_scan),
        TestCase("settings", "Debug Settings", R.layout.debug_test_settings)
    )

    private var scanModelButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        initViews()
        setupSpinner()
        loadDebugSettings()
        log("Debug Activity started")
    }

    private fun initViews() {
        scrollView = findViewById(R.id.scrollView)
        logTextView = findViewById(R.id.logTextView)
        testCaseSpinner = findViewById(R.id.testCaseSpinner)
        testCaseContainer = findViewById(R.id.testCaseContainer)
        clearLogButton = findViewById(R.id.clearLogButton)
        copyLogButton = findViewById(R.id.copyLogButton)
        homeCrashlyticsNonFatalButton = findViewById(R.id.homeCrashlyticsNonFatalButton)
        homeCrashlyticsFatalButton = findViewById(R.id.homeCrashlyticsFatalButton)
        
        val titleTextView = findViewById<TextView>(R.id.titleTextView)
        val baseTitle = getString(R.string.debug_activity_title)
        val buildType = if (BuildConfig.DEBUG) "Debug" else "Release"
        titleTextView.text = "$baseTitle ($buildType)"

        setupClickListeners()
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            testCases.map { it.title }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        testCaseSpinner.adapter = adapter

        testCaseSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                loadTestCaseLayout(testCases[position])
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                // Do nothing
            }
        }

        // Load first test case by default
        if (testCases.isNotEmpty()) {
            loadTestCaseLayout(testCases[0])
        }
    }

    private fun loadTestCaseLayout(testCase: TestCase) {
        log("Loading test case: ${testCase.title}")
        
        // Clear previous layout
        testCaseContainer.removeAllViews()
        
        // Inflate new layout
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(testCase.layoutResId, testCaseContainer, false)
        testCaseContainer.addView(view)
        
        // Initialize views for this test case
        when (testCase.id) {
            "asr" -> initAsrViews(view)
            "streamAsr" -> initStreamAsrViews(view)
            "notify" -> initNotifyViews(view)
            "tts" -> initTtsViews(view)
            "scan" -> initScanViews(view)
            "settings" -> initSettingsViews(view)
        }
    }

    private fun initScanViews(parentView: View) {
        scanModelButton = parentView.findViewById(R.id.scanModelButton)
        scanModelButton?.setOnClickListener {
            startModelScanTest()
        }
    }

    private fun startModelScanTest() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                log("=== Calling ModelListManager.debugScanModels ===")
                val result = ModelListManager.debugScanModels(this@DebugActivity)
                
                withContext(Dispatchers.Main) {
                   logTextView.append(result)
                   scrollView.post {
                        scrollView.fullScroll(View.FOCUS_DOWN)
                   }
                }
                
                log("=== Scan Completed ===")
                
            } catch (e: Exception) {
                log("Scan failed: ${e.message}")
                Log.e(TAG, "Scan failed", e)
            }
        }
    }

    private fun initAsrViews(parentView: View) {
        asrTestButton = parentView.findViewById(R.id.asrTestButton)
        asrTestButton?.setOnClickListener {
            if (isRecording) {
                stopAsrTest()
            } else {
                startAsrTest()
            }
        }
    }

    private fun initTtsViews(parentView: View) {
        ttsTestButton = parentView.findViewById(R.id.ttsTestButton)
        ttsInputText = parentView.findViewById(R.id.ttsInputText)
        ttsProcessButton = parentView.findViewById(R.id.ttsProcessButton)

        ttsTestButton?.setOnClickListener {
            if (isTtsInitialized) {
                stopTtsTest()
            } else {
                startTtsTest()
            }
        }

        ttsProcessButton?.setOnClickListener {
            processTtsText()
        }
    }

    private fun initSettingsViews(parentView: View) {
        showModelInfoSwitch = parentView.findViewById(R.id.showModelInfoSwitch)
        allowNetworkSwitch = parentView.findViewById(R.id.allowNetworkSwitch)
        networkDelaySwitch = parentView.findViewById(R.id.networkDelaySwitch)
        closeDebugModeButton = parentView.findViewById(R.id.closeDebugModeButton)
        testCrashlyticsNonFatalButton = parentView.findViewById(R.id.testCrashlyticsNonFatalButton)
        testCrashlyticsFatalButton = parentView.findViewById(R.id.testCrashlyticsFatalButton)

        // Load current settings
        val isModelInfoEnabled = PreferenceUtils.getBoolean(this, KEY_SHOW_MODEL_INFO_ENABLED, false)
        showModelInfoSwitch?.isChecked = isModelInfoEnabled

        val isAllowNetwork = PreferenceUtils.getBoolean(this, KEY_ALLOW_NETWORK_MARKET_DATA, true)
        allowNetworkSwitch?.isChecked = isAllowNetwork

        val isNetworkDelayEnabled = PreferenceUtils.getBoolean(this, KEY_ENABLE_NETWORK_DELAY, false)
        networkDelaySwitch?.isChecked = isNetworkDelayEnabled

        // Setup listeners
        showModelInfoSwitch?.setOnCheckedChangeListener { _, isChecked ->
            PreferenceUtils.setBoolean(this, KEY_SHOW_MODEL_INFO_ENABLED, isChecked)
            log("Model info menu visibility: ${if (isChecked) "enabled" else "disabled"}")
        }

        allowNetworkSwitch?.setOnCheckedChangeListener { _, isChecked ->
            PreferenceUtils.setBoolean(this, KEY_ALLOW_NETWORK_MARKET_DATA, isChecked)
            log("Allow network to fetch model market data: ${if (isChecked) "enabled" else "disabled"}")
        }

        networkDelaySwitch?.setOnCheckedChangeListener { _, isChecked ->
            PreferenceUtils.setBoolean(this, KEY_ENABLE_NETWORK_DELAY, isChecked)
            log("Network delay simulation: ${if (isChecked) "enabled" else "disabled"}")
        }

        closeDebugModeButton?.setOnClickListener {
            closeDebugMode()
        }

        testCrashlyticsNonFatalButton?.setOnClickListener {
            testCrashlyticsNonFatal()
        }

        testCrashlyticsFatalButton?.setOnClickListener {
            confirmAndCrashForCrashlytics()
        }
    }

    private fun setupClickListeners() {
        clearLogButton.setOnClickListener {
            clearLog()
        }
        copyLogButton.setOnClickListener {
            copyLog()
        }
        homeCrashlyticsNonFatalButton.setOnClickListener {
            testCrashlyticsNonFatal()
        }
        homeCrashlyticsFatalButton.setOnClickListener {
            confirmAndCrashForCrashlytics()
        }
    }

    private fun loadDebugSettings() {
        log("Debug settings loaded")
    }


    private fun startAsrTest() {
        if (checkRecordAudioPermission()) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    log("Starting ASR test...")
                    val modelDir = "/data/local/tmp/asr_models/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20"
                    log("Using ASR model path: $modelDir")
                    recognizeService = AsrService(this@DebugActivity, modelDir)
                    
                    withContext(Dispatchers.IO) {
                        recognizeService?.initRecognizer()
                    }
                    recognizeService?.onRecognizeText = { text ->
                        runOnUiThread {
                            log("ASR Result: $text")
                        }
                    }

                    recognizeService?.startRecord()
                    isRecording = true
                    asrTestButton?.text = getString(R.string.stop_asr_test)
                    log("ASR test started successfully")
                    
                } catch (e: Exception) {
                    log("ASR test failed: ${e.message}")
                    Log.e(TAG, "ASR test failed", e)
                }
            }
        }
    }

    private fun stopAsrTest() {
        try {
            recognizeService?.stopRecord()
            recognizeService = null
            isRecording = false
            asrTestButton?.text = getString(R.string.start_asr_test)
            log("ASR test stopped")
        } catch (e: Exception) {
            log("Error stopping ASR test: ${e.message}")
            Log.e(TAG, "Error stopping ASR test", e)
        }
    }

    // ---- Spec 002 task 8: dual-path streaming ASR research ----

    private var probeATextView: TextView? = null
    private var probeBTextView: TextView? = null
    private var probeStatusView: TextView? = null
    private var probeIntervalSpinner: Spinner? = null
    private var probeStartedAtMs = 0L

    private fun initStreamAsrViews(parentView: View) {
        probeATextView = parentView.findViewById(R.id.textProbeA)
        probeBTextView = parentView.findViewById(R.id.textProbeB)
        probeStatusView = parentView.findViewById(R.id.textProbeStatus)
        probeIntervalSpinner = parentView.findViewById(R.id.spinnerChunkInterval)

        val intervals = listOf("3 秒", "5 秒", "10 秒")
        probeIntervalSpinner?.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, intervals
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        probeIntervalSpinner?.setSelection(1)

        parentView.findViewById<Button>(R.id.btnProbeAStart).setOnClickListener { startProbeA() }
        parentView.findViewById<Button>(R.id.btnProbeAStop).setOnClickListener { stopProbeA() }
        parentView.findViewById<Button>(R.id.btnProbeBStart).setOnClickListener { startProbeB() }
        parentView.findViewById<Button>(R.id.btnProbeBStop).setOnClickListener { stopProbeB() }
    }

    private fun probeAppend(view: TextView?, line: String) {
        runOnUiThread {
            view?.append(line + "\n")
        }
    }

    private fun probeStatus(msg: String) {
        runOnUiThread { probeStatusView?.text = msg }
    }

    private fun startProbeA() {
        if (chunkedProbe != null) {
            log("Path A already running")
            return
        }
        if (!checkRecordAudioPermission()) return
        val interval = when (probeIntervalSpinner?.selectedItemPosition ?: 1) {
            0 -> 3; 2 -> 10; else -> 5
        }
        probeStartedAtMs = System.currentTimeMillis()
        probeATextView?.text = ""
        log("Path A (Qwen3-ASR chunked) starting, interval=${interval}s")
        chunkedProbe = ChunkedAsrProbe(
            this, interval,
            onChunkResult = { index, audioSec, latencyMs, text ->
                val rtf = if (audioSec > 0) latencyMs / 1000.0 / audioSec else 0.0
                probeAppend(
                    probeATextView,
                    "[#$index ${"%.1f".format(audioSec)}s | ${latencyMs}ms | RTF ${"%.2f".format(rtf)}] $text"
                )
                probeStatus("A 路运行中 · 已完成 ${index + 1} 个 chunk · 累计 ${"%.0f".format((System.currentTimeMillis() - probeStartedAtMs) / 1000.0)}s")
            },
            onError = { msg -> log("Path A error: $msg") }
        )
        chunkedProbe?.start()
        if (chunkedProbe != null) log("Path A started (interval=${interval}s)")
    }

    private fun stopProbeA() {
        val probe = chunkedProbe ?: return
        val chunks = probe.completedChunks()
        probe.stop()
        chunkedProbe = null
        log("Path A stopped after $chunks chunks")
    }

    private fun startProbeB() {
        if (probeBService != null) {
            log("Path B already running")
            return
        }
        if (!checkRecordAudioPermission()) return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                log("Path B (sherpa-mnn true streaming) starting...")
                val service = AsrService(this@DebugActivity, null) // dynamic model path
                withContext(Dispatchers.IO) { service.initRecognizer() }
                service.onRecognizeText = { text ->
                    probeAppend(probeBTextView, text)
                }
                service.onSpeechDetected = {
                    // interim signal only; AsrService emits finals per endpoint
                }
                service.startRecord()
                probeBService = service
                probeBTextView?.text = ""
                log("Path B started")
            } catch (e: Exception) {
                log("Path B failed: ${e.message}")
                Log.e(TAG, "Path B failed", e)
            }
        }
    }

    private fun stopProbeB() {
        try {
            probeBService?.stopRecord()
            probeBService = null
            log("Path B stopped")
        } catch (e: Exception) {
            log("Error stopping Path B: ${e.message}")
        }
    }

    private fun checkRecordAudioPermission(): Boolean {
        return when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                true
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Toast.makeText(this, R.string.recording_permission_denied, Toast.LENGTH_LONG).show()
                false
            }
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO_PERMISSION
                )
                false
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            REQUEST_RECORD_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    log("Record audio permission granted")
                    startAsrTest()
                } else {
                    log("Record audio permission denied")
                    Toast.makeText(this, R.string.recording_permission_denied, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logMessage = "[$timestamp] $message\n"
        
        runOnUiThread {
            logTextView.append(logMessage)
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
        
        Log.d(TAG, message)
    }

    private fun clearLog() {
        logTextView.text = ""
        log("Log cleared")
    }

    private fun copyLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Debug Log", logTextView.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.log_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun closeDebugMode() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.close_debug_mode_title)
            .setMessage(R.string.close_debug_mode_message)
            .setPositiveButton(R.string.close_debug_mode) { _, _ ->
                // Save debug mode deactivation state to SharedPreferences
                val sharedPreferences = getSharedPreferences("com.alibaba.mnnllm.android_preferences", MODE_PRIVATE)
                sharedPreferences.edit().putBoolean("debug_mode_activated", false).apply()
                
                log("Debug mode deactivated")
                Toast.makeText(this, getString(R.string.debug_mode_closed_message), Toast.LENGTH_LONG).show()
                
                // Close the activity
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun testCrashlyticsNonFatal() {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.log("Debug panel non-fatal test")
        crashlytics.setCustomKey("debug_mode_activated", true)
        crashlytics.setCustomKey("build_type", if (BuildConfig.DEBUG) "debug" else "release")
        crashlytics.setCustomKey("application_id", BuildConfig.APPLICATION_ID)
        crashlytics.recordException(
            IllegalStateException("Debug non-fatal Crashlytics test ${System.currentTimeMillis()}")
        )
        log("Crashlytics non-fatal event sent. Check Firebase console in a few minutes.")
        Toast.makeText(this, R.string.debug_test_crashlytics_non_fatal_sent, Toast.LENGTH_SHORT).show()
    }

    private fun confirmAndCrashForCrashlytics() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.debug_test_crashlytics_fatal_confirm_title)
            .setMessage(R.string.debug_test_crashlytics_fatal_confirm_message)
            .setPositiveButton(R.string.debug_test_crashlytics_fatal_confirm_action) { _, _ ->
                val crashlytics = FirebaseCrashlytics.getInstance()
                crashlytics.log("Debug panel fatal test")
                crashlytics.setCustomKey("debug_mode_activated", true)
                crashlytics.setCustomKey("build_type", if (BuildConfig.DEBUG) "debug" else "release")
                crashlytics.setCustomKey("application_id", BuildConfig.APPLICATION_ID)
                throw RuntimeException("Debug fatal Crashlytics test ${System.currentTimeMillis()}")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startTtsTest() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                log("Starting TTS test...")
                ttsService = TtsService()
                audioPlayer = AudioChunksPlayer()
                
                withContext(Dispatchers.IO) {
                    try {
                        val modelDir = VoiceModelPathUtils.getTtsModelPath(this@DebugActivity)
                        val sampleRate = VoiceModelPathUtils.getTtsSampleRate(modelDir)
                        val language = VoiceModelPathUtils.getTtsLanguage(this@DebugActivity)
                        log("Using TTS model path: $modelDir")
                        log("Using TTS sample rate: $sampleRate")
                        log("Using TTS language: $language")
                        audioPlayer?.sampleRate = sampleRate
                        ttsService?.setLanguage(language)
                        log("Initializing TTS with model directory: $modelDir")
                        val initResult = ttsService?.init(modelDir)
                        if (initResult == true) {
                            log("TTS Service initialized successfully")
                            isTtsInitialized = true
                            withContext(Dispatchers.Main) {
                                ttsTestButton?.text = getString(R.string.stop_tts_test)
                                ttsInputText?.isEnabled = true
                                ttsProcessButton?.isEnabled = true
                                ttsInputText?.setText("Hello, this is a test of the TTS system.")
                            }
                            log("TTS test started successfully")
                        } else {
                            log("TTS Service initialization failed")
                            Log.e(TAG, "TTS Service initialization failed")
                        }
                    } catch (e: Exception) {
                        log("TTS initialization error: ${e.message}")
                        Log.e(TAG, "TTS initialization error", e)
                    }
                }
                
            } catch (e: Exception) {
                log("TTS test failed: ${e.message}")
                Log.e(TAG, "TTS test failed", e)
            }
        }
    }

    private fun stopTtsTest() {
        try {
            audioPlayer?.destroy()
            ttsService?.destroy()
            ttsService = null
            audioPlayer = null
            isTtsInitialized = false
            ttsTestButton?.text = getString(R.string.start_tts_test)
            ttsInputText?.isEnabled = false
            ttsProcessButton?.isEnabled = false
            log("TTS test stopped")
        } catch (e: Exception) {
            log("Error stopping TTS test: ${e.message}")
            Log.e(TAG, "Error stopping TTS test", e)
        }
    }

    private fun processTtsText() {
        val text = ttsInputText?.text.toString().trim()
        if (text.isEmpty()) {
            log("Please enter some text")
            return
        }

        if (!isTtsInitialized || ttsService == null) {
            log("TTS Service not initialized. Please start TTS test first.")
            return
        }

        lifecycleScope.launch {
            try {
                log("Processing TTS text: $text")
                
                // Wait for TTS service to be ready
                val isReady = ttsService?.waitForInitComplete()
                if (isReady != true) {
                    log("TTS Service not ready")
                    return@launch
                }

                log("TTS Service is ready, processing text...")

                // Process text with TTS
                val audioData = ttsService?.process(text, 0)
                if (audioData != null && audioData.isNotEmpty()) {
                    log("Generated audio data with ${audioData.size} samples")
                    
                    // Update UI with results
                    log("Generated ${audioData.size} audio samples. Playing...")

                    // Initialize audio player if needed
                    if (audioPlayer == null) {
                        audioPlayer = AudioChunksPlayer()
                    }
                    audioPlayer!!.sampleRate = VoiceModelPathUtils.getTtsSampleRate(
                        VoiceModelPathUtils.getTtsModelPath(this@DebugActivity)
                    )
                    audioPlayer?.start()

                    // Play the audio
                    audioPlayer?.playChunk(audioData)
                    
                    // Wait for playback to complete
                    audioPlayer?.waitStop()
                    
                    log("Playback completed. Generated ${audioData.size} samples.")
                } else {
                    log("Failed to generate audio data or audio data is empty")
                }

            } catch (e: Exception) {
                log("Error processing TTS: ${e.message}")
                Log.e(TAG, "Error processing TTS", e)
            }
        }
    }

    // ---- Spec-002 task 1: notification assistant chain status ----

    private var notifyStatusText: TextView? = null

    private fun initNotifyViews(parentView: View) {
        notifyStatusText = parentView.findViewById(R.id.notifyStatusText)
        parentView.findViewById<Button>(R.id.notifyAccessButton).setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                log("无法打开通知使用权设置: ${e.message}")
            }
        }
        parentView.findViewById<Button>(R.id.notifyRefreshButton).setOnClickListener {
            refreshNotifyStatus()
        }
        parentView.findViewById<Button>(R.id.notifyClearQueueButton).setOnClickListener {
            try {
                val store = com.alibaba.mnnllm.android.assistant.notify.PendingQueueStore(this)
                store.clear()
                log("待判队列已清空")
                refreshNotifyStatus()
            } catch (e: Exception) {
                log("清空队列失败: ${e.message}")
            }
        }
        parentView.findViewById<Button>(R.id.notifyClearDigestButton).setOnClickListener {
            try {
                val store = com.alibaba.mnnllm.android.assistant.notify.DigestStore(this)
                store.clear()
                log("摘要库已清空")
                refreshNotifyStatus()
            } catch (e: Exception) {
                log("清空摘要库失败: ${e.message}")
            }
        }
        parentView.findViewById<Button>(R.id.notifyRunBatchButton).setOnClickListener {
            try {
                com.alibaba.mnnllm.android.assistant.notify.InferenceScheduler.tryRun(this, "forced")
                log("批处理已触发(强制): adb logcat -s SmartNotify 看 batch done/gated")
            } catch (e: Exception) {
                log("触发失败: ${e.message}")
            }
            refreshNotifyStatus()
        }
        parentView.findViewById<Button>(R.id.notifyJudgeModelSetButton).setOnClickListener {
            val v = parentView.findViewById<EditText>(R.id.notifyJudgeModelEdit).text.toString().trim()
            if (v.isEmpty()) {
                log("判定模型 modelId 不能为空")
            } else {
                com.alibaba.mnnllm.android.assistant.notify.AssistantNotifyConfig.setJudgeModelId(this, v)
                log("判定模型已设置: $v")
                refreshNotifyStatus()
            }
        }
        refreshNotifyStatus()
    }

    private fun refreshNotifyStatus() {
        val text = notifyStatusText ?: return
        val sb = StringBuilder()
        sb.append("listener bound: ").append(
            com.alibaba.mnnllm.android.assistant.notify.SmartNotificationListener.isConnected())
        sb.append("\n\nlogcat 过滤:  adb logcat -s SmartNotify\n")
        sb.append("\n规则 (assistant_notify_prefs):\n")
        try {
            val sp = getSharedPreferences(
                com.alibaba.mnnllm.android.assistant.notify.AssistantNotifyConfig.PREFS_NAME,
                android.content.Context.MODE_PRIVATE)
            sb.append("master_enabled = ").append(sp.getBoolean("master_enabled", true)).append("\n")
            sb.append("content_enabled = ").append(sp.getBoolean("content_enabled", false)).append("\n")
            sb.append("rules(渠道屏蔽) = [").append(sp.getString("rules", "")?.take(200)).append("]\n")
            sb.append("allow_rules(白名单) = [").append(sp.getString("allow_rules", "")?.take(200)).append("]\n")
            sb.append("content_rules(内容屏蔽) = [").append(sp.getString("content_rules", "")?.take(200)).append("]\n")
        } catch (e: Exception) {
            sb.append("读取失败: ").append(e.message)
        }
        sb.append("\n默认渠道规则: .*营销.*  (内置, 总是生效)\n")
        sb.append("观察到的渠道存储: filesDir/assistant_channels.json\n")
        sb.append("内容拦截日志: filesDir/assistant_block_log.json\n")
        try {
            val queue = com.alibaba.mnnllm.android.assistant.notify.PendingQueueStore(this)
            val digest = com.alibaba.mnnllm.android.assistant.notify.DigestStore(this)
            sb.append("\n待判队列 (上限500, 重启保留): ").append(queue.size()).append(" 条\n")
            queue.peekOldest(3).forEach {
                sb.append("  · [").append(it.pkg).append("] ")
                    .append(it.title?.take(30)).append(" | ")
                    .append(it.text?.take(40)).append("\n")
            }
            sb.append("\n摘要库 (24h 环形, 验证码排除): ").append(digest.size()).append(" 条")
            digest.recent().takeLast(3).forEach {
                sb.append("\n  · [").append(it.pkg).append("] ")
                    .append(it.title?.take(30)).append(" | ")
                    .append(it.text?.take(40))
            }
            sb.append("\n\n存储: filesDir/assistant_pending_queue.json, assistant_digest.json\n")
            sb.append("调度: ").append(
                com.alibaba.mnnllm.android.assistant.notify.InferenceScheduler.statusLine(this))
                .append("\n执行器: ").append(
                com.alibaba.mnnllm.android.assistant.notify.InferenceScheduler.executorName())
                .append("\n判定模型: ").append(
                com.alibaba.mnnllm.android.assistant.notify.AssistantNotifyConfig.judgeModelIdStatic(this)
                    ?.ifEmpty { "(未设置)" })
        } catch (e: Exception) {
            sb.append("\n存储读取失败: ").append(e.message)
        }
        text.text = sb.toString()
        log("notify status refreshed: bound=" +
                com.alibaba.mnnllm.android.assistant.notify.SmartNotificationListener.isConnected())
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAsrTest()
        stopTtsTest()
        chunkedProbe?.stop()
        chunkedProbe = null
        try {
            probeBService?.stopRecord()
        } catch (e: Exception) {
            Log.w(TAG, "probeB stop on destroy", e)
        }
        probeBService = null
    }
}
