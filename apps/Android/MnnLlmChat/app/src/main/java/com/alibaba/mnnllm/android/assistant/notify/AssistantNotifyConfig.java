package com.alibaba.mnnllm.android.assistant.notify;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Notification-assistant rule config, adapted from mnblocker's
 * RootFreeConfig. Simplifications vs the original (spec-002 task 1):
 *
 *  - No XSharedPreferences / config.json / rules.txt bridge: the fork has no
 *    Xposed path, the listener and future settings UI share the same
 *    process/uid, so {@link #PREFS_NAME} is the single source of truth.
 *  - No root/rootfree operating-mode gate: the listener is always the active
 *    path in the assistant, so the master switch alone governs it.
 *
 * Rebuilt whenever the underlying SharedPreferences changes, via a listener.
 */
public final class AssistantNotifyConfig {

    public static final String PREFS_NAME = "assistant_notify_prefs";
    static final String KEY_MASTER_ENABLED = "master_enabled";
    static final String KEY_MATCH_DESC = "match_description";
    static final String KEY_CONTENT_ENABLED = "content_enabled";
    static final String KEY_RULES = "rules";
    static final String KEY_ALLOW_RULES = "allow_rules";
    static final String KEY_CONTENT_RULES = "content_rules";
    static final String KEY_OVERRIDES = "overrides";
    static final String KEY_APP_WHITELIST = "app_whitelist";
    static final String KEY_JUDGE_MODEL_ID = "judge_model_id";
    static final String KEY_AD_CONF_THRESHOLD = "ad_conf_threshold";

    private final SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (sp, key) -> reload();

    private volatile RuleMatcher channelMatcher = RuleMatcher.compile(null, null);
    private volatile RuleMatcher contentMatcher = RuleMatcher.compileContent(null, null);
    private volatile boolean masterEnabled = true;
    private volatile boolean matchDescription = true;
    private volatile boolean contentEnabled = false;
    private final Map<String, Boolean> overrides = new HashMap<>();
    private final Set<String> appWhitelist = new LinkedHashSet<>();

    AssistantNotifyConfig(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        reload();
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    /** Stop reacting to pref changes. Call from the listener's onDestroy. */
    void close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    private synchronized void reload() {
        masterEnabled = prefs.getBoolean(KEY_MASTER_ENABLED, true);
        matchDescription = prefs.getBoolean(KEY_MATCH_DESC, true);
        contentEnabled = prefs.getBoolean(KEY_CONTENT_ENABLED, false);

        Set<String> blockRaw = splitLines(prefs.getString(KEY_RULES, ""));
        Set<String> allowRaw = splitLines(prefs.getString(KEY_ALLOW_RULES, ""));
        Set<String> contentRaw = splitLines(prefs.getString(KEY_CONTENT_RULES, ""));
        channelMatcher = RuleMatcher.compile(blockRaw, allowRaw);
        // Content engine reuses the SAME allow whitelist but not the built-in
        // default rule, same as mnblocker.
        contentMatcher = RuleMatcher.compileContent(contentRaw, allowRaw);

        appWhitelist.clear();
        appWhitelist.addAll(splitLines(prefs.getString(KEY_APP_WHITELIST, "")));

        overrides.clear();
        parseOverrides(prefs.getString(KEY_OVERRIDES, ""));
    }

    private void parseOverrides(String json) {
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                overrides.put(k, o.optBoolean(k, false));
            }
        } catch (Throwable ignored) {
            // Malformed overrides JSON: fall back to whatever we had.
        }
    }

    private static Set<String> splitLines(String blob) {
        Set<String> out = new LinkedHashSet<>();
        if (blob == null) {
            return out;
        }
        for (String line : blob.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    boolean isMasterEnabled() {
        return masterEnabled;
    }

    boolean isMatchDescription() {
        return matchDescription;
    }

    boolean isContentEnabled() {
        return contentEnabled;
    }

    boolean isAppWhitelisted(String pkg) {
        if (pkg == null) {
            return false;
        }
        synchronized (this) {
            return appWhitelist.contains(pkg);
        }
    }

    Boolean overrideFor(String pkg, String channelId) {
        synchronized (this) {
            return overrides.get(pkg + "|" + channelId);
        }
    }

    RuleMatcher channelMatcher() {
        return channelMatcher;
    }

    RuleMatcher contentMatcher() {
        return contentMatcher;
    }

    /** Task 4: which installed chat model judges batches (user-selectable). */
    public static String judgeModelIdStatic(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_JUDGE_MODEL_ID, "");
    }

    public static void setJudgeModelId(Context context, String modelId) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_JUDGE_MODEL_ID, modelId == null ? "" : modelId).apply();
    }

    /** Ads at/above this self-reported confidence are cancelled (task 6 refines). */
    public static double adConfidenceThresholdStatic(Context context) {
        return (double) context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_AD_CONF_THRESHOLD, 0.5f);
    }

    public static void setAdConfidenceThreshold(Context context, double v) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_AD_CONF_THRESHOLD, (float) v).apply();
    }
}
