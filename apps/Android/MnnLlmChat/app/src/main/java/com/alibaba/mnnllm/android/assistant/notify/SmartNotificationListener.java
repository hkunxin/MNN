package com.alibaba.mnnllm.android.assistant.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Spec-002 notification assistant interception entry point, ported from
 * mnblocker's RootFreeNotificationListener (io.mo.mnblocker).
 *
 * There is no pre-post interception API available to a non-privileged listener,
 * so every regex hit goes through {@link #cancelNotification(String)} AFTER the
 * notification has already briefly appeared. Learned/LLM rules (later tasks)
 * close that gap by becoming instant regex rules.
 *
 * Differences vs mnblocker original (task 1 scope):
 *  - no root/rootfree operating-mode gate (always active when bound)
 *  - every decision and action is logged under {@link #TAG} so the
 *    override > whitelist > allow > block > no-match chain is auditable
 *    via logcat (task 1 completion criterion)
 *  - per-post stats are logged, not persisted (a stats store belongs to the
 *    assistant UI task)
 *  - the no-match path enqueues into {@link PendingQueueStore} (task 2) for
 *    batched LLM judgment; nothing here ever runs a model (R1/R2)
 *
 * Hard rules mirrored from the original: never touch this app's own
 * notifications, never touch a foreground-service notification, and never let
 * a failure here crash the process.
 */
public final class SmartNotificationListener extends NotificationListenerService {

    static final String TAG = "SmartNotify";

    /**
     * Whether the system currently has this listener BOUND — not the same thing
     * as the user having granted notification access. Static because debug UI
     * has no handle on the service instance.
     */
    private static volatile boolean connected;

    private volatile AssistantNotifyConfig config;
    private ObservedChannelStore channelStore;
    private BlockLogStore blockLogStore;
    private PendingQueueStore pendingQueue;
    private DigestStore digestStore;

    /** @return true while the system has this listener bound and delivering. */
    public static boolean isConnected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        // The system can disconnect then reconnect without destroying the service;
        // drop the previous config's pref-change listener before making a new one.
        AssistantNotifyConfig old = config;
        if (old != null) {
            old.close();
        }
        config = new AssistantNotifyConfig(this);
        channelStore = new ObservedChannelStore(this);
        blockLogStore = new BlockLogStore(this);
        pendingQueue = new PendingQueueStore(this);
        digestStore = new DigestStore(this);
        InferenceScheduler.start(this, pendingQueue);
        connected = true;
        Log.i(TAG, "listener connected — assistant notify chain active");
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        Log.w(TAG, "listener disconnected");
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        connected = false;
        InferenceScheduler.stop(this);
        AssistantNotifyConfig cfg = config;
        if (cfg != null) {
            cfg.close();
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        try {
            AssistantNotifyConfig cfg = config;
            if (cfg == null) {
                return;
            }

            String pkg = sbn.getPackageName();
            if (getPackageName().equals(pkg)) {
                return; // never touch — or even record — our own notifications
            }

            Notification n = sbn.getNotification();
            if (n == null) {
                return;
            }

            NotificationChannel channel = null; // Ranking.getChannel() needs API 26+
            if (Build.VERSION.SDK_INT >= 26 && rankingMap != null) {
                Ranking r = new Ranking();
                if (rankingMap.getRanking(sbn.getKey(), r)) {
                    channel = r.getChannel();
                }
            }

            // Observe the channel BEFORE any gate below so the future channel
            // list stays complete even while the master switch is off.
            recordObserved(cfg, pkg, channel);

            if (!cfg.isMasterEnabled()) {
                Log.d(TAG, "pkg=" + pkg + " -> skip (master off)");
                return;
            }
            if (cfg.isAppWhitelisted(pkg)) {
                Log.d(TAG, "pkg=" + pkg + " -> allow (app-whitelist)");
                return;
            }
            // NEVER suppress a foreground-service notification: the platform
            // requires it and killing it can crash the owning service.
            if (sbn.isOngoing() || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                Log.d(TAG, "pkg=" + pkg + " -> allow (foreground/ongoing)");
                return;
            }

            // ---- channel-level judgment ----
            BlockDecision.Result d = BlockDecision.decide(
                    cfg.channelMatcher(),
                    channel != null ? cfg.overrideFor(pkg, channel.getId()) : null,
                    false, // whole-app whitelist already handled by the early return above
                    channelCandidates(channel, cfg.isMatchDescription()));
            Log.d(TAG, "pkg=" + pkg + " channel=" + (channel == null ? "?" : channel.getId())
                    + " channel-level=" + d.reason);

            boolean contentBlock = false;
            // ---- content-level judgment (only if channel-level missed and enabled) ----
            // The channel override deliberately does NOT carry over here: it answers
            // "blanket-block this channel?", and content rules exist precisely for
            // channels whose answer is no. Content's safety valves are the shared
            // allow rules (applied inside BlockDecision) and the app whitelist.
            if (!d.block && cfg.isContentEnabled()) {
                String[] candidates = extractContentCandidates(n);
                if (candidates.length > 0) {
                    d = BlockDecision.decide(cfg.contentMatcher(), null, false, candidates);
                    if (d.block) {
                        contentBlock = true;
                        // Only content-level blocks leave a per-notification detail entry.
                        blockLogStore.record(pkg, extractField(n, Notification.EXTRA_TITLE),
                                firstNonEmpty(new String[]{
                                        extractField(n, Notification.EXTRA_TEXT),
                                        extractField(n, Notification.EXTRA_BIG_TEXT)}),
                                d.reason);
                    }
                    Log.d(TAG, "pkg=" + pkg + " content-level=" + d.reason);
                }
            }

            if (d.block) {
                cancelNotification(sbn.getKey());
                Log.i(TAG, "pkg=" + pkg + " key=" + sbn.getKey() + " CANCELLED"
                        + " level=" + (contentBlock ? "content" : "channel")
                        + " reason=" + d.reason);
            } else {
                // R1: no-match -> persistent queue for batched LLM judgment
                // (task 3 scheduler drains it; zero inference on this path).
                // The sbn key is kept so a later ad verdict can cancel it.
                if (pendingQueue != null) {
                    String title = extractField(n, Notification.EXTRA_TITLE);
                    String body = firstNonEmpty(new String[]{
                            extractField(n, Notification.EXTRA_TEXT),
                            extractField(n, Notification.EXTRA_BIG_TEXT)});
                    pendingQueue.enqueue(new PendingQueueStore.Item(
                            sbn.getKey(), pkg, title, body, sbn.getPostTime()));
                    Log.d(TAG, "pkg=" + pkg + " key=" + sbn.getKey() + " allow (no-match)"
                            + " key-queued=yes queueSize=" + pendingQueue.size());
                } else {
                    Log.d(TAG, "pkg=" + pkg + " key=" + sbn.getKey() + " allow (no-match)"
                            + " key-queued=no (store not ready)");
                }
            }
        } catch (Throwable t) {
            // A failure here must never crash this app's process.
            Log.w(TAG, "onNotificationPosted failed", t);
        }
    }

    /** Always record the channel for the future settings UI list, regardless of verdict. */
    private void recordObserved(AssistantNotifyConfig cfg, String pkg, NotificationChannel channel) {
        if (channel == null || channelStore == null) {
            return;
        }
        boolean matched = cfg.channelMatcher().shouldBlock(
                channelCandidates(channel, cfg.isMatchDescription()));
        channelStore.record(new ChannelRecord(pkg, channel.getId(),
                channel.getName() == null ? null : channel.getName().toString(),
                channel.getDescription(), channel.getImportance(), matched,
                System.currentTimeMillis()));
    }

    private static String[] channelCandidates(NotificationChannel channel, boolean matchDescription) {
        if (channel == null) {
            return new String[0];
        }
        String name = channel.getName() == null ? null : channel.getName().toString();
        String desc = matchDescription ? channel.getDescription() : null;
        return new String[]{channel.getId(), name, desc};
    }

    /** Pull every user-visible text field out of a notification for matching. */
    private static String[] extractContentCandidates(Notification n) {
        List<String> out = new ArrayList<>();
        try {
            Bundle b = n.extras;
            if (b != null) {
                addText(out, b.getCharSequence(Notification.EXTRA_TITLE));
                addText(out, b.getCharSequence(Notification.EXTRA_TEXT));
                addText(out, b.getCharSequence(Notification.EXTRA_BIG_TEXT));
                addText(out, b.getCharSequence(Notification.EXTRA_SUB_TEXT));
                addText(out, b.getCharSequence(Notification.EXTRA_TITLE_BIG));
                addText(out, b.getCharSequence(Notification.EXTRA_INFO_TEXT));
                addText(out, b.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            }
            addText(out, n.tickerText);
        } catch (Throwable ignored) {
            // extras access can throw on odd ROMs; match on whatever we got.
        }
        return out.toArray(new String[0]);
    }

    private static void addText(List<String> out, CharSequence cs) {
        if (cs != null && cs.length() > 0) {
            out.add(cs.toString());
        }
    }

    /** Read one extras field as a String; null-safe on odd ROMs. */
    private static String extractField(Notification n, String key) {
        try {
            Bundle b = n.extras;
            if (b != null) {
                CharSequence cs = b.getCharSequence(key);
                if (cs != null) {
                    return cs.toString();
                }
            }
        } catch (Throwable ignored) {
            // extras access can throw on some ROMs; treat as absent.
        }
        return null;
    }

    private static String firstNonEmpty(String[] arr) {
        for (String s : arr) {
            if (s != null && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
