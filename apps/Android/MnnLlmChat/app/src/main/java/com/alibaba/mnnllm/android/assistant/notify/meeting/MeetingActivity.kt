// Spec-002 task 9 (R8): 会议记录页 — 计时 / 已确认句 / 半句刷新区 / 暂停结束。
// 录音归 MeetingRecordService（前台服务，息屏不中断）；本页是薄视图，销毁不影响录音。
// 崩溃恢复：MeetingStore 逐句落盘，重新打开本页可对未结束会议「继续录音」。
// 纪要生成复用 :llm 进程（AssistantLlmClient mode="minutes"，判定模型可配）。
package com.alibaba.mnnllm.android.assistant.notify.meeting

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.assistant.notify.AssistantLlmClient
import com.alibaba.mnnllm.android.assistant.notify.AssistantNotifyConfig
import com.alibaba.mnnllm.android.utils.SystemBarInsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MeetingActivity"
        private const val REQUEST_RECORD_AUDIO = 3001
    }

    private lateinit var titleEdit: EditText
    private lateinit var timerView: TextView
    private lateinit var stateView: TextView
    private lateinit var partialView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var minutesView: TextView
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var endButton: Button
    private lateinit var minutesButton: Button
    private lateinit var historyContainer: LinearLayout

    private val store by lazy { MeetingStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            updateTimer()
            mainHandler.postDelayed(this, 1000)
        }
    }

    /** 会议被打开查看（历史点选）；null = 新会议状态。 */
    private var viewingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meeting)
        // targetSdk 35：Android 15 强制 edge-to-edge，需显式避让状态栏/导航栏/键盘
        SystemBarInsets.applyPadding(this)
        titleEdit = findViewById(R.id.meetingTitleEdit)
        timerView = findViewById(R.id.meetingTimer)
        stateView = findViewById(R.id.meetingStateText)
        partialView = findViewById(R.id.meetingPartial)
        transcriptView = findViewById(R.id.meetingTranscript)
        minutesView = findViewById(R.id.meetingMinutes)
        startButton = findViewById(R.id.btnMeetingStart)
        pauseButton = findViewById(R.id.btnMeetingPause)
        endButton = findViewById(R.id.btnMeetingEnd)
        minutesButton = findViewById(R.id.btnMeetingMinutes)
        historyContainer = findViewById(R.id.meetingHistoryContainer)

        startButton.setOnClickListener { onStartClicked() }
        pauseButton.setOnClickListener {
            val svc = MeetingRecordService
            if (svc.state == MeetingRecordService.STATE_PAUSED) {
                svc.instanceFor()?.resumeRecording()
            } else {
                svc.instanceFor()?.pauseRecording()
            }
        }
        endButton.setOnClickListener { onEndClicked() }
        minutesButton.setOnClickListener { generateMinutes() }

        MeetingRecordService.updateListener = serviceListener
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(timerTick)
        renderHistory()
        syncFromService()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(timerTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (MeetingRecordService.updateListener === serviceListener) {
            MeetingRecordService.updateListener = null
        }
    }

    private val serviceListener: () -> Unit = { syncFromService() }

    // ------------------------------------------------------------ 录音控制

    private fun onStartClicked() {
        if (MeetingRecordService.isRecording()) {
            Toast.makeText(this, "录音已在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        // 崩溃恢复：有未结束会议时，直接在该会议上继续录。
        val open = store.loadOpen()
        if (open != null && viewingId == null) {
            viewingId = open.id
            titleEdit.setText(open.title)
            Toast.makeText(this, "检测到未结束的会议，继续录音", Toast.LENGTH_LONG).show()
        }
        val id = viewingId ?: store.create(titleEdit.text.toString()).also { viewingId = it.id }.id
        val title = titleEdit.text.toString().ifBlank { "未命名会议" }
        val ok = MeetingRecordService.startNew(this, title, id)
        if (!ok) {
            Toast.makeText(this, "启动录音服务失败", Toast.LENGTH_SHORT).show()
        }
        transcriptView.text = ""
        minutesView.text = ""
    }

    private fun onEndClicked() {
        val id = viewingId
        if (id == null) {
            Toast.makeText(this, "没有进行中的会议", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "已保存；最后的分块仍在转写，稍候自动补入", Toast.LENGTH_LONG).show()
        // 先停止采集（尾部 chunk 由 worker 排空后逐句落盘），再关前台服务。
        MeetingRecordService.instanceFor()?.endRecording()
        val m = store.load(id)
        if (m != null) {
            m.endedAt = System.currentTimeMillis()
            store.save(m)
        }
        MeetingRecordService.stopService(this)
        viewingId = null
        minutesButton.isEnabled = true
        renderHistory()
        syncFromService()
    }

    // ------------------------------------------------------------ 纪要生成

    private fun generateMinutes() {
        val id = viewingId ?: run {
            Toast.makeText(this, "先开始或选择一个会议", Toast.LENGTH_SHORT).show()
            return
        }
        val meeting = store.load(id) ?: return
        val transcript = store.transcript(meeting)
        if (transcript.isBlank()) {
            Toast.makeText(this, "转写为空，无法生成纪要", Toast.LENGTH_SHORT).show()
            return
        }
        val modelId = AssistantNotifyConfig.judgeModelIdStatic(this)
        if (modelId.isNullOrEmpty()) {
            Toast.makeText(this, "请先设置判定/摘要模型", Toast.LENGTH_LONG).show()
            return
        }
        minutesButton.isEnabled = false
        minutesView.text = "纪要生成中…（端侧模型）"
        CoroutineScope(Dispatchers.IO).launch {
            var result: JSONObject? = null
            try {
                val req = JSONObject().apply {
                    put("mode", "minutes")
                    put("modelId", modelId)
                    put("configPath",
                        com.alibaba.mnnllm.android.model.ModelUtils.getConfigPathForModel(modelId))
                    put("text", transcript)
                }
                result = AssistantLlmClient.request(applicationContext, req)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "minutes request failed", t)
            }
            withContext(Dispatchers.Main) {
                minutesButton.isEnabled = true
                val text = result?.optString("text", "") ?: ""
                if (result != null && result.optBoolean("ok", false) && text.isNotBlank()) {
                    store.setMinutes(id, text)
                    minutesView.text = text
                    Toast.makeText(this@MeetingActivity, "纪要已生成并保存", Toast.LENGTH_SHORT).show()
                } else {
                    minutesView.text = "生成失败" +
                        (result?.optString("error", "")?.let { ": $it" } ?: "（超时/服务异常）")
                }
                renderHistory()
            }
        }
    }

    // ------------------------------------------------------------ UI 同步

    private fun updateTimer() {
        val ms = if (MeetingRecordService.isRecording()) MeetingRecordService.currentElapsedMs()
                 else 0
        val totalSec = ms / 1000
        timerView.text = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun syncFromService() {
        val recording = MeetingRecordService.isRecording()
        when {
            recording && MeetingRecordService.state == MeetingRecordService.STATE_PAUSED -> {
                stateView.text = "已暂停"
                pauseButton.text = "继续"
            }
            recording -> {
                stateView.text = "录音中"
                pauseButton.text = "暂停"
            }
            else -> {
                stateView.text = if (viewingId != null) "已结束/已保存" else "未开始"
                pauseButton.text = "暂停"
            }
        }
        startButton.isEnabled = !recording
        pauseButton.isEnabled = recording
        endButton.isEnabled = recording
        partialView.text = if (MeetingRecordService.asrBusy) "识别中…" else ""
        if (recording) {
            val committed = MeetingRecordService.committedText.toString()
            if (committed.isNotBlank()) transcriptView.text = committed
        }
    }

    private fun renderHistory() {
        historyContainer.removeAllViews()
        val meetings = store.list()
        if (meetings.isEmpty()) {
            historyContainer.addView(TextView(this).apply {
                text = "（暂无历史会议）"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
            })
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        for (m in meetings) {
            val open = m.endedAt == 0L
            val row = TextView(this).apply {
                text = (if (open) "● 进行中  " else "") + m.title +
                    "  " + fmt.format(Date(m.startedAt)) +
                    "  (" + m.sentences.size + "句)"
                textSize = 13f
                setPadding(dp8, dp8, dp8, dp8)
                setOnClickListener {
                    viewingId = m.id
                    titleEdit.setText(m.title)
                    transcriptView.text = m.sentences.joinToString("\n") { it.text }
                    minutesView.text = m.minutes
                    minutesButton.isEnabled = !open
                    stateView.text = if (open) "进行中(可继续)" else "已结束"
                    Toast.makeText(context,
                        if (open) "进行中的会议：点「开始」继续录音" else "已加载",
                        Toast.LENGTH_SHORT).show()
                }
                setOnLongClickListener {
                    store.delete(m.id)
                    if (viewingId == m.id) viewingId = null
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    renderHistory()
                    true
                }
            }
            historyContainer.addView(row)
        }
    }

    private val dp8: Int
        get() = (8 * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            Toast.makeText(this, "需要录音权限", Toast.LENGTH_LONG).show()
        }
    }
}
