package com.alibaba.mnnllm.android.assistant.notify;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Spec-002 task 6: rule learning ("规则沉淀 + 建议模式").
 *
 * The LLM judge (task 4) attaches a GENERALIZABLE content rule to every
 * high-confidence ad verdict. Those candidates land here as PENDING
 * suggestions ("建议模式"). The user either CONFIRMS a suggestion — at which
 * point it becomes an instant regex rule consulted by the listener chain
 * (BEFORE the batched LLM, i.e. zero inference for repeats) — or DISMISSES it.
 *
 * Auto-confirm ("高置信静默阈值"): candidates whose self-reported confidence
 * is at/above {@link AssistantNotifyConfig#ruleAutoConfirmThresholdStatic}
 * skip the suggestion list and become confirmed immediately, silently.
 *
 * Allow-priority regression (mnblocker invariant): a learned BLOCK rule never
 * overrides the user's allow rules. The listener checks the shared allow
 * matcher first; this store additionally re-checks allow rules itself when
 * consulted through {@link #firstMatch(AssistantNotifyConfig, String, String, String)}.
 *
 * Persistence: one JSON file, atomic replace, same pattern as DigestStore.
 */
public final class LearnedRuleStore {

    static final String TAG = "SmartNotify";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_DISMISSED = "dismissed";

    /** Cap so a runaway suggestion stream can never bloat memory/disk. */
    public static final int MAX_RULES = 200;

    /** One learned rule. Immutable; mutations replace the entry. */
    public static final class Rule {
        public final long id;
        public final String pattern;   // regex fragment over title/text
        public final String pkg;       // "" = any app
        public final String sample;    // first seen ad title (context for the user)
        public final double conf;      // LLM confidence of the originating verdict
        public final String status;    // pending / confirmed / dismissed
        public final long createdAt;
        public final long decidedAt;   // 0 while pending

        Rule(long id, String pattern, String pkg, String sample,
             double conf, String status, long createdAt, long decidedAt) {
            this.id = id;
            this.pattern = pattern;
            this.pkg = pkg;
            this.sample = sample;
            this.conf = conf;
            this.status = status;
            this.createdAt = createdAt;
            this.decidedAt = decidedAt;
        }
    }

    private final File file;
    private final List<Rule> rules = new ArrayList<>();
    private long nextId = 1;

    public LearnedRuleStore(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(),
                "assistant_learned_rules.json");
        load();
    }

    /**
     * Ingest an LLM candidate rule (task 4 verdict "rule" field). Returns the
     * stored rule, or null when the candidate is unusable. Auto-confirms when
     * conf >= the configurable silent threshold.
     */
    public synchronized Rule addCandidate(String pattern, String pkg, String sample,
                                          double conf, double autoConfirmThreshold) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return null;
        }
        // Same pattern from the same app: refresh confidence instead of duplicating.
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (r.pattern.equals(pattern.trim()) && r.pkg.equals(pkg == null ? "" : pkg)) {
                Rule updated = new Rule(r.id, r.pattern, r.pkg,
                        sample != null && !sample.isEmpty() ? sample : r.sample,
                        Math.max(r.conf, conf), r.status, r.createdAt, r.decidedAt);
                rules.set(i, updated);
                persist();
                return updated;
            }
        }
        boolean auto = conf >= autoConfirmThreshold;
        Rule r = new Rule(nextId++, pattern.trim(), pkg == null ? "" : pkg,
                sample == null ? "" : sample, conf,
                auto ? STATUS_CONFIRMED : STATUS_PENDING,
                System.currentTimeMillis(), auto ? System.currentTimeMillis() : 0L);
        rules.add(r);
        trimToCap();
        persist();
        Log.i(TAG, "learned-rule candidate id=" + r.id + " pattern=" + r.pattern
                + " conf=" + conf + " auto-confirmed=" + auto);
        return r;
    }

    /**
     * Instant-chain lookup used by the listener: the first CONFIRMED rule
     * matching the notification text. Allow rules WIN (regression guard):
     * when the shared allow matcher hits, this returns null even if a learned
     * rule would have matched.
     */
    public synchronized String firstMatch(AssistantNotifyConfig cfg, String pkg,
                                          String title, String text) {
        String[] candidates = {title, text};
        for (Rule r : rules) {
            if (!STATUS_CONFIRMED.equals(r.status)) {
                continue;
            }
            if (!r.pkg.isEmpty() && pkg != null && !r.pkg.equals(pkg)) {
                continue;
            }
            if (matches(r.pattern, candidates)) {
                // Allow rules always beat learned block rules.
                if (cfg != null && cfg.allowMatcher().firstAllowMatch(candidates) != null) {
                    Log.d(TAG, "learned-rule " + r.pattern + " suppressed by allow rule");
                    return null;
                }
                return r.pattern;
            }
        }
        return null;
    }

    private static boolean matches(String pattern, String[] candidates) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            for (String c : candidates) {
                if (c != null && !c.isEmpty() && p.matcher(c).find()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // A bad learned pattern must never break the chain; drop it here.
        }
        return false;
    }

    public synchronized Rule confirm(long id) {
        return setStatus(id, STATUS_CONFIRMED);
    }

    public synchronized Rule dismiss(long id) {
        return setStatus(id, STATUS_DISMISSED);
    }

    /** Remove a confirmed rule entirely (撤销). */
    public synchronized boolean remove(long id) {
        Iterator<Rule> it = rules.iterator();
        while (it.hasNext()) {
            if (it.next().id == id) {
                it.remove();
                persist();
                return true;
            }
        }
        return false;
    }

    private Rule setStatus(long id, String status) {
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (r.id == id && !status.equals(r.status)) {
                Rule updated = new Rule(r.id, r.pattern, r.pkg, r.sample,
                        r.conf, status, r.createdAt, System.currentTimeMillis());
                rules.set(i, updated);
                persist();
                Log.i(TAG, "learned-rule id=" + id + " -> " + status);
                return updated;
            }
        }
        return null;
    }

    public synchronized List<Rule> pending() {
        return byStatus(STATUS_PENDING);
    }

    public synchronized List<Rule> confirmed() {
        return byStatus(STATUS_CONFIRMED);
    }

    public synchronized List<Rule> all() {
        return new ArrayList<>(rules);
    }

    private List<Rule> byStatus(String status) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (status.equals(r.status)) {
                out.add(r);
            }
        }
        return out;
    }

    private void trimToCap() {
        // Drop oldest dismissed/pending first; confirmed rules are the point.
        while (rules.size() > MAX_RULES) {
            int victim = -1;
            for (int i = 0; i < rules.size(); i++) {
                String s = rules.get(i).status;
                if (STATUS_DISMISSED.equals(s) || STATUS_PENDING.equals(s)) {
                    victim = i;
                    break;
                }
            }
            if (victim < 0) {
                victim = 0; // all confirmed: drop oldest confirmed
            }
            rules.remove(victim);
        }
    }

    // ------------------------------------------------------------ persistence

    private void load() {
        rules.clear();
        try {
            if (!file.exists()) {
                return;
            }
            JSONArray arr = new JSONArray(new String(readAll(file), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                rules.add(new Rule(
                        o.optLong("id", 0L),
                        o.optString("pattern", null),
                        o.optString("pkg", ""),
                        o.optString("sample", ""),
                        o.optDouble("conf", 0.0),
                        o.optString("status", STATUS_PENDING),
                        o.optLong("createdAt", 0L),
                        o.optLong("decidedAt", 0L)));
            }
            long max = 0;
            for (Rule r : rules) {
                max = Math.max(max, r.id);
            }
            nextId = max + 1;
            Log.i(TAG, "learned rules restored: " + rules.size());
        } catch (Throwable t) {
            Log.w(TAG, "learned rules restore failed, starting empty", t);
            rules.clear();
        }
    }

    private synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (Rule r : rules) {
                arr.put(new JSONObject()
                        .put("id", r.id)
                        .put("pattern", r.pattern)
                        .put("pkg", r.pkg)
                        .put("sample", r.sample)
                        .put("conf", r.conf)
                        .put("status", r.status)
                        .put("createdAt", r.createdAt)
                        .put("decidedAt", r.decidedAt));
            }
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            if (!tmp.renameTo(file) && file.exists() && !file.delete()) {
                Log.w(TAG, "learned rules replace failed");
            }
        } catch (IOException | org.json.JSONException | RuntimeException t) {
            Log.w(TAG, "learned rules persist failed", t);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] out = new byte[(int) f.length()];
            int off = 0;
            while (off < out.length) {
                int n = fis.read(out, off, out.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return out;
        }
    }
}
