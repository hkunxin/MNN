package com.alibaba.mnnllm.android.assistant.notify;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;

/**
 * R2/R3 gating scheduler: notifications are judged in batches, and ONLY when
 * the screen is on, at least {@link #DEFAULT_INTERVAL_MIN} minutes after the
 * previous batch, and the pending queue is non-empty. Hard user constraint
 * (2026-11-03): a screen-off device must never run any inference.
 *
 * Trigger paths:
 *  1. exact alarm every interval ({@link InferenceSchedulerReceiver}); when
 *     it fires with the screen off it is a no-op — R2 says so — and the work
 *     is deferred to path 2;
 *  2. {@link Intent#ACTION_SCREEN_ON} receiver (dynamic; this action cannot
 *     be manifest-registered): catches both the periodic catch-up and the
 *     "alarm fired while asleep" case, and also sidesteps most Chinese-ROM
 *     background-alarm restrictions because the app is in the foreground
 *     path the moment the user unlocks.
 *
 * The actual judgment is delegated to a {@link BatchExecutor}. Until task 4
 * lands the LLM executor, a dry-run executor is installed that logs each
 * batch and re-queues it, so the full gating chain can be verified on device
 * with zero model calls.
 */
public final class InferenceScheduler {

    static final String TAG = "SmartNotify";
    /** R3: single batch cap; overflow stays queued for the next batch. */
    public static final int BATCH_SIZE = 60;
    public static final int DEFAULT_INTERVAL_MIN = 30;

    private static final String SCREEN_STATE_KEY = "inference_scheduler_screen_state";

    /** Consumes one batch. Returns true if the items are consumed; false to re-queue. */
    public interface BatchExecutor {
        String name();
        boolean execute(List<PendingQueueStore.Item> items);
    }

    /** Dry-run executor until task 4 provides the LLM judgment. */
    private static final BatchExecutor DRY_RUN = new BatchExecutor() {
        @Override
        public String name() {
            return "dry-run (task4 LLM pending)";
        }

        @Override
        public boolean execute(List<PendingQueueStore.Item> items) {
            for (PendingQueueStore.Item it : items) {
                Log.i(TAG, "batch(dry) pkg=" + it.pkg + " title=" + it.title);
            }
            return false; // keep everything queued
        }
    };

    private static volatile BatchExecutor executor = DRY_RUN;
    private static volatile boolean screenReceiverRegistered;
    private static volatile long screenOffSince;
    private static volatile long lastScreenOnAt;

    /** Task 4 installs the real executor through this. */
    public static void setExecutor(BatchExecutor impl) {
        executor = (impl == null) ? DRY_RUN : impl;
        Log.i(TAG, "batch executor set: " + executor.name());
    }

    public static String executorName() {
        BatchExecutor e = executor;
        return e == null ? "none" : e.name();
    }

    // ------------------------------------------------------------ lifecycle

    /** Call from SmartNotificationListener.onListenerConnected. */
    public static void start(Context context, PendingQueueStore queue) {
        Context app = context.getApplicationContext();
        if (!screenReceiverRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            app.registerReceiver(new ScreenStateReceiver(), filter);
            screenReceiverRegistered = true;
        }
        scheduleNextAlarm(app);
        tryRun(app, queue, "listener-connected");
    }

    /** Call from SmartNotificationListener.onListenerDisconnected / onDestroy. */
    public static void stop(Context context) {
        // Keep the alarm scheduled: the queue is disk-backed and the manifest
        // receiver re-enters tryRun with a fresh store, so batching resumes
        // after rebind without extra bookkeeping.
    }

    // ------------------------------------------------------------ alarm

    public static void scheduleNextAlarm(Context context) {
        long intervalMs = intervalMinutes(context) * 60_000L;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = alarmPendingIntent(context);
        // setExactAndAllowWhileIdle is one-shot: re-arm after every fire/run.
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMs, pi);
            Log.w(TAG, "exact alarms not permitted — using inexact window");
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMs, pi);
        }
    }

    private static PendingIntent alarmPendingIntent(Context context) {
        Intent i = new Intent(context, InferenceSchedulerReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, 1, i, flags);
    }

    // ------------------------------------------------------------ gating + run

    /**
     * The single entry point for every trigger. Cheap and side-effect free
     * when a gate refuses; all refusals are logged for auditability.
     */
    public static void tryRun(Context context, String reason) {
        tryRun(context.getApplicationContext(),
                new PendingQueueStore(context.getApplicationContext()), reason);
    }

    public static synchronized void tryRun(Context app, PendingQueueStore queue, String reason) {
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        boolean screenOn = pm != null && pm.isInteractive();
        long now = System.currentTimeMillis();
        long since = now - lastRunAt(app);
        int pending = queue.size();

        Log.d(TAG, "tryRun reason=" + reason + " screenOn=" + screenOn
                + " sinceLastMin=" + (since / 60_000) + " pending=" + pending);

        if (!screenOn) {
            // R2: record that work is pending; the SCREEN_ON receiver will
            // catch up the moment the user lights the screen.
            screenState(app, true);
            Log.i(TAG, "gated: screen off — batch deferred (pending=" + pending + ")");
            return;
        }
        if (pending == 0) {
            Log.d(TAG, "gated: queue empty");
            return;
        }
        if (since < intervalMinutes(app) * 60_000L && !"forced".equals(reason)) {
            Log.d(TAG, "gated: interval " + (intervalMinutes(app) - since / 60_000) + "min left");
            return;
        }

        List<PendingQueueStore.Item> batch = queue.drainBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        BatchExecutor exec = executor;
        long t0 = System.currentTimeMillis();
        boolean consumed;
        try {
            consumed = exec.execute(batch);
        } catch (Throwable t) {
            Log.w(TAG, "batch executor failed", t);
            consumed = false;
        }
        long cost = System.currentTimeMillis() - t0;
        if (!consumed) {
            for (PendingQueueStore.Item it : batch) {
                queue.enqueue(it); // dry-run / failure: put everything back
            }
        }
        setLastRunAt(app, now);
        scheduleNextAlarm(app);
        Log.i(TAG, "batch done reason=" + reason + " executor=" + exec.name()
                + " n=" + batch.size() + " costMs=" + cost
                + " consumed=" + consumed + " remaining=" + queue.size());
    }

    // ------------------------------------------------------------ prefs

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(AssistantNotifyConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static long lastRunAt(Context c) {
        return prefs(c).getLong("last_batch_run_at", 0L);
    }

    private static void setLastRunAt(Context c, long t) {
        prefs(c).edit().putLong("last_batch_run_at", t).apply();
    }

    private static int intervalMinutes(Context c) {
        return prefs(c).getInt("batch_interval_min", DEFAULT_INTERVAL_MIN);
    }

    private static void screenState(Context c, boolean off) {
        prefs(c).edit().putLong(SCREEN_STATE_KEY,
                off ? System.currentTimeMillis() : 0L).apply();
    }

    /** ms the screen has been off, for R6 idle-release bookkeeping (task 4). */
    public static long screenOffDurationMs(Context c) {
        long since0 = prefs(c).getLong(SCREEN_STATE_KEY, 0L);
        return since0 == 0L ? 0L : System.currentTimeMillis() - since0;
    }

    /** For debug status page. */
    public static String statusLine(Context c) {
        long since = System.currentTimeMillis() - lastRunAt(c);
        return "interval=" + intervalMinutes(c) + "min, lastRun="
                + (lastRunAt(c) == 0 ? "never" : (since / 60_000) + "min ago")
                + ", screenOff=" + (screenOffDurationMs(c) / 60_000) + "min";
    }

    // ------------------------------------------------------------ receivers

    /** Manifest-registered: fires every interval even after process death. */
    public static class InferenceSchedulerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            tryRun(context, "alarm");
        }
    }

    /** Dynamic: SCREEN_ON catches up deferred batches (R2 pending 补跑). */
    private static class ScreenStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                lastScreenOnAt = System.currentTimeMillis();
                tryRun(context, "screen-on");
            } else {
                screenOffSince = System.currentTimeMillis();
                screenState(context.getApplicationContext(), true);
                Log.d(TAG, "screen off — inference suspended (R2)");
            }
        }
    }

    private InferenceScheduler() {
    }
}
