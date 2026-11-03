package com.alibaba.mnnllm.android.assistant.notify;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Observed-channel persistence, ported from mnblocker
 * (io.mo.mnblocker.RootFreeChannelStore). Plain app-private file
 * (getFilesDir()/assistant_channels.json).
 */
final class ObservedChannelStore {

    private static final String FILE_NAME = "assistant_channels.json";
    /** Same cap as mnblocker's store, for the same reason. */
    private static final int MAX_ENTRIES = 1000;

    private final File file;
    private final LinkedHashMap<String, ChannelRecord> live = new LinkedHashMap<>();
    private boolean loadedFromDisk;

    ObservedChannelStore(Context context) {
        this(fileFor(context));
    }

    ObservedChannelStore(File file) {
        this.file = file;
    }

    static File fileFor(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /** Record (insert or update) a channel and persist. Called from the listener. */
    synchronized void record(ChannelRecord r) {
        if (!loadedFromDisk) {
            for (ChannelRecord prev : readAll(file)) {
                live.put(prev.key(), prev);
            }
            loadedFromDisk = true;
        }
        // Skip no-op updates: most posts are repeat notifications from a channel
        // we already hold at the same importance/name/verdict — only lastSeen
        // would move, and a full JSONArray rewrite per notification is exactly
        // the main-thread I/O to avoid (the listener runs on its main thread).
        ChannelRecord prev = live.get(r.key());
        if (prev != null && sameSubstance(prev, r)) {
            return;
        }
        live.remove(r.key());
        live.put(r.key(), r);
        while (live.size() > MAX_ENTRIES) {
            live.remove(live.keySet().iterator().next());
        }
        flush();
    }

    /** True when two records differ only in {@code lastSeen}. */
    private static boolean sameSubstance(ChannelRecord a, ChannelRecord b) {
        return a.importance == b.importance
                && a.regexMatched == b.regexMatched
                && a.name.equals(b.name)
                && a.desc.equals(b.desc);
    }

    private void flush() {
        try {
            JSONArray arr = new JSONArray();
            for (ChannelRecord r : live.values()) {
                arr.put(r.toJson());
            }
            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write(arr.toString());
            }
        } catch (Throwable ignored) {
            // Best-effort persistence; a failed write must never crash the app.
        }
    }

    static List<ChannelRecord> readAll(File file) {
        return parse(readNormal(file));
    }

    static List<ChannelRecord> readAll(Context context) {
        return readAll(fileFor(context));
    }

    static boolean clear(Context context) {
        try {
            try (FileWriter fw = new FileWriter(fileFor(context), false)) {
                fw.write("[]");
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

    private static List<ChannelRecord> parse(String json) {
        List<ChannelRecord> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(ChannelRecord.fromJson(o));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
