// Spec-002 task 9 (R8): foreground service that OWNS the StreamingAsrSession
// so recording survives screen-off and activity recreation (design.md: 用户
// 主动发起的录音不属于 R2「后台偷跑」约束，会议中断丢内容的代价更高).
//
// The service appends each confirmed sentence to MeetingStore immediately
// (逐句落盘可恢复). The activity is a thin view: it binds to static state and
// registers an update listener; if the activity dies the recording continues.
package com.alibaba.mnnllm.android.assistant.notify.meeting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class MeetingRecordService : Service() {

    companion object {
        private const val TAG = "MeetingRecord"
        private const val CHANNEL_ID = "meeting_record"
        private const val NOTIFICATION_ID = 2001

        const val STATE_IDLE = 0
        const val STATE_RECORDING = 1
        const val STATE_PAUSED = 2

        @Volatile private var instance: MeetingRecordService? = null

        @Volatile var state: Int = STATE_IDLE
            private set
        @Volatile var meetingId: String? = null
            private set
        @Volatile var meetingTitle: String = ""
            private set

        /** UI hook: notified on every state/sentence change (main thread). */
        @Volatile var updateListener: (() -> Unit)? = null

        /** True while a chunk is being transcribed (半句刷新区 signal). */
        @Volatile var asrBusy: Boolean = false

        fun isRecording(): Boolean = state != STATE_IDLE && instance != null

        /** Activity access to pause/resume/end. Null when the service is down. */
        @JvmStatic fun instanceFor(): MeetingRecordService? = instance

        fun startNew(context: Context, title: String, meetingIdNew: String): Boolean {
            if (isRecording()) return false
            meetingId = meetingIdNew
            meetingTitle = title
            state = STATE_RECORDING
            val intent = Intent(context, MeetingRecordService::class.java)
            context.startForegroundService(intent)
            return true
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, MeetingRecordService::class.java))
        }

        /** Sentences recorded so far (read-only snapshot of committed text). */
        @Volatile var committedText: StringBuilder = StringBuilder()
            private set

        /** Current recording elapsed time for the activity timer (0 when idle). */
        @JvmStatic fun currentElapsedMs(): Int = instance?.sessionElapsed()?.toInt() ?: 0
    }

    private var session: StreamingAsrSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        instance = this
        val store = MeetingStore(this)
        val id = meetingId ?: run {
            Log.w(TAG, "no meetingId — stopping")
            stopSelf()
            return
        }
        committedText = StringBuilder()
        session = StreamingAsrSession(
            this,
            onSentence = { text, _ ->
                committedText.append(text).append('\n')
                store.appendSentence(id, System.currentTimeMillis(), text)
                notifyUpdate()
            },
            onBusyChanged = { busy ->
                asrBusy = busy
                notifyUpdate()
            },
            onError = { msg -> Log.w(TAG, "asr error: $msg") }
        )
        session?.start()
        if (session?.isRunning != true) {
            Log.w(TAG, "session failed to start — stopping service")
            state = STATE_IDLE
            stopSelf()
            return
        }
        notifyUpdate()
        Log.i(TAG, "foreground recording started, meeting=$id")
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "会议记录", NotificationManager.IMPORTANCE_LOW))
        }
        // Tap notification -> back to the meeting screen.
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MeetingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("会议记录进行中")
            .setContentText("息屏录音不中断，点击返回")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyUpdate() {
        updateListener?.let { mainHandler.post(it) }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Called by the activity on pause/resume/end buttons. */
    fun pauseRecording() {
        session?.pause()
        state = STATE_PAUSED
        notifyUpdate()
    }

    fun resumeRecording() {
        session?.resume()
        state = STATE_RECORDING
        notifyUpdate()
    }

    /** Ends the session; drains remaining chunks, then the activity saves. */
    fun endRecording() {
        session?.stop()
    }

    private fun sessionElapsed(): Long = session?.elapsedMs() ?: 0L

    override fun onDestroy() {
        // Safety net: if the activity never called endRecording (crash path),
        // stop the mic here; committed sentences are already on disk.
        session?.stop()
        session = null
        instance = null
        state = STATE_IDLE
        notifyUpdate()
        super.onDestroy()
    }
}
