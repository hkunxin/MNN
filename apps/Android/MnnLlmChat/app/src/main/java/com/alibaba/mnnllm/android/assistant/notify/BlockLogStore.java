package com.alibaba.mnnllm.android.assistant.notify;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content-block log persistence, ported from mnblocker
 * (io.mo.mnblocker.RootFreeBlockLogStore). Same
 * {@code {"perApp":{"pkg":[{"t","ti","tx","r"}]}}} JSON shape; the Entry type
 * is local (the fork has no ContentBlockLogStore to share it with).
 * File: getFilesDir()/assistant_block_log.json (app-private, no lockdown needed).
 */
final class BlockLogStore {

    private static final String FILE_NAME = "assistant_block_log.json";
    private static final int MAX_PER_APP = 100;
    private static final int MAX_APPS = 300;
    private static final int MAX_TEXT_LEN = 500;

    /** One dropped notification detail entry. */
    static final class Entry {
        final long time;
        final String title;
        final String text;
        final String rule;

        Entry(long time, String title, String text, String rule) {
            this.time = time;
            this.title = title;
            this.text = text;
            this.rule = rule;
        }
    }

    private final File file;
    // Insertion-ordered: least-recently-active app first, so eviction is cheap.
    private final LinkedHashMap<String, List<Entry>> perApp = new LinkedHashMap<>();
    private boolean loaded;
    private long fileMtime = Long.MIN_VALUE;

    BlockLogStore(Context context) {
        this(fileFor(context));
    }

    BlockLogStore(File file) {
        this.file = file;
    }

    static File fileFor(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /** Record one dropped content notification and persist. Called from the listener. */
    synchronized void record(String pkg, String title, String text, String rule) {
        syncFromDiskIfNeeded();
        String key = (pkg == null || pkg.isEmpty()) ? "<unknown>" : pkg;

        List<Entry> list = perApp.remove(key); // mark app most-recent
        if (list == null) {
            list = new ArrayList<>();
        }
        list.add(new Entry(System.currentTimeMillis(), clip(title), clip(text), clip(rule)));
        while (list.size() > MAX_PER_APP) {
            list.remove(0);
        }
        perApp.put(key, list);

        while (perApp.size() > MAX_APPS) {
            perApp.remove(perApp.keySet().iterator().next());
        }
        flush();
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN) : s;
    }

    private void syncFromDiskIfNeeded() {
        try {
            long m = file.lastModified();
            if (!loaded || m != fileMtime) {
                perApp.clear();
                perApp.putAll(parse(readNormal(file)));
                loaded = true;
                fileMtime = m;
            }
        } catch (Throwable t) {
            loaded = true;
        }
    }

    private void flush() {
        try {
            String json = serialize(perApp);
            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write(json);
            }
            fileMtime = file.lastModified();
        } catch (Throwable ignored) {
            // Best-effort persistence; a failed write must never crash the app.
        }
    }

    /** Entries for one app, newest first. */
    static List<Entry> readForApp(Context context, String pkg) {
        Map<String, List<Entry>> all = parse(readNormal(fileFor(context)));
        List<Entry> list = all.get(pkg);
        List<Entry> out = new ArrayList<>(list == null ? new ArrayList<>() : list);
        Collections.reverse(out); // stored oldest-first => show newest-first
        return out;
    }

    /** Clear the whole log. */
    static boolean reset(Context context) {
        try {
            try (FileWriter fw = new FileWriter(fileFor(context), false)) {
                fw.write("{}");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readNormal(File f) {
        try {
            if (!f.exists() || !f.canRead()) {
                return null;
            }
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream fis = new FileInputStream(f)) {
                int n = fis.read(buf);
                return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String serialize(Map<String, List<Entry>> perApp) {
        JSONObject apps = new JSONObject();
        try {
            for (Map.Entry<String, List<Entry>> e : perApp.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Entry en : e.getValue()) {
                    JSONObject o = new JSONObject();
                    o.put("t", en.time);
                    o.put("ti", en.title);
                    o.put("tx", en.text);
                    o.put("r", en.rule);
                    arr.put(o);
                }
                apps.put(e.getKey(), arr);
            }
            JSONObject root = new JSONObject();
            root.put("perApp", apps);
            return root.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static Map<String, List<Entry>> parse(String json) {
        LinkedHashMap<String, List<Entry>> out = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONObject apps = new JSONObject(json).optJSONObject("perApp");
            if (apps == null) {
                return out;
            }
            for (Iterator<String> it = apps.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONArray arr = apps.optJSONArray(pkg);
                if (arr == null) {
                    continue;
                }
                List<Entry> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        list.add(new Entry(o.optLong("t", 0L),
                                o.optString("ti", ""), o.optString("tx", ""), o.optString("r", "")));
                    }
                }
                out.put(pkg, list);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
