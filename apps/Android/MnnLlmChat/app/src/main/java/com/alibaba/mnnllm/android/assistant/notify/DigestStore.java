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
 * Summary store for notifications that PASSED the rule chain and the LLM ad
 * check (spec-002 R3: "非广告条目写入摘要库"), feeding the digest screen
 * (R5) and the batched summarization prompt.
 *
 * Invariants (design.md + R7):
 *  - Ring buffer of {@link #WINDOW_MS} = 24h; expired entries are pruned on
 *    every mutation/read.
 *  - Verification-code notifications are NEVER stored (R7, see
 *    {@link VerificationCodes}) — enforced here so no caller can forget.
 *  - Local-only persistence, one JSON file, atomic replace; a one-tap
 *    {@link #clear()} is the R7 wipe entry point.
 */
public final class DigestStore {

    static final String TAG = "SmartNotify";
    public static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    /** Safety cap beyond the time window (long screen-off backlog). */
    public static final int MAX_ITEMS = 500;

    /** One summarized notification. Immutable. */
    public static final class Item {
        public final String pkg;
        public final String title;
        public final String text;
        public final long postedAt;

        Item(String pkg, String title, String text, long postedAt) {
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.postedAt = postedAt;
        }
    }

    private final File file;
    private final List<Item> items = new ArrayList<>();

    public DigestStore(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(),
                "assistant_digest.json");
        load();
    }

    /**
     * Add a passed notification. Verification-code content is rejected here
     * (returns false) so the privacy rule holds regardless of call site.
     */
    public synchronized boolean add(String pkg, String title, String text, long postedAt) {
        if (VerificationCodes.isVerificationCode(title, text)) {
            Log.d(TAG, "digest skip (verification code) pkg=" + pkg);
            return false;
        }
        items.add(new Item(pkg, title, text, postedAt));
        prune(System.currentTimeMillis());
        persist();
        return true;
    }

    /** All entries within the 24h window, oldest first. */
    public synchronized List<Item> recent() {
        long now = System.currentTimeMillis();
        prune(now);
        return new ArrayList<>(items);
    }

    public synchronized int size() {
        prune(System.currentTimeMillis());
        return items.size();
    }

    public synchronized void clear() {
        if (items.isEmpty()) {
            return;
        }
        items.clear();
        persist();
    }

    private void prune(long now) {
        boolean changed = false;
        Iterator<Item> it = items.iterator();
        while (it.hasNext()) {
            if (now - it.next().postedAt > WINDOW_MS) {
                it.remove();
                changed = true;
            }
        }
        while (items.size() > MAX_ITEMS) {
            items.remove(0);
            changed = true;
        }
        if (changed) {
            persist();
        }
    }

    private void load() {
        items.clear();
        try {
            if (!file.exists()) {
                return;
            }
            byte[] raw = readAll(file);
            JSONArray arr = new JSONArray(new String(raw, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                items.add(new Item(
                        o.optString("pkg", null),
                        o.optString("title", null),
                        o.optString("text", null),
                        o.optLong("postedAt", 0L)));
            }
            prune(System.currentTimeMillis());
            Log.i(TAG, "digest restored: " + items.size() + " item(s) in window");
        } catch (Throwable t) {
            Log.w(TAG, "digest restore failed, starting empty", t);
            items.clear();
        }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("pkg", it.pkg);
                o.put("title", it.title);
                o.put("text", it.text);
                o.put("postedAt", it.postedAt);
                arr.put(o);
            }
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            if (!tmp.renameTo(file) && file.exists() && !file.delete()) {
                Log.w(TAG, "digest replace failed");
            }
        } catch (IOException | org.json.JSONException | RuntimeException t) {
            Log.w(TAG, "digest persist failed", t);
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
