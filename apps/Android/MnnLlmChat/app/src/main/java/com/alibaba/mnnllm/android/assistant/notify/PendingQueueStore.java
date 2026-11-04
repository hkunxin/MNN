package com.alibaba.mnnllm.android.assistant.notify;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Persistent queue of notifications that missed every regex rule (spec-002
 * R1) and therefore await a batched on-device LLM judgment (R2/R3).
 *
 * Design constraints:
 *  - NO inference happens here: enqueue is a disk write plus a log line, safe
 *    to call from the listener while the screen is off (R2 hard rule).
 *  - Must survive process death / reboot: state is a JSON array file,
 *    atomically replaced (tmp + rename) on every mutation.
 *  - Hard cap {@link #MAX_ITEMS} = 500; when full the OLDEST entries are
 *    dropped first so the queue can never grow unbounded during a long
 *    screen-off backlog (design.md risk: overnight backlog).
 *
 * Threading: the listener calls {@link #enqueue} from its binder thread;
 * later the scheduler (task 3) drains from another thread. All public
 * methods are synchronized and each mutation rewrites the file.
 */
public final class PendingQueueStore {

    static final String TAG = "SmartNotify";
    /** R3: single batch is 60; 500 total backlog bound agreed in design. */
    public static final int MAX_ITEMS = 500;

    /** One queued notification awaiting judgment. Immutable. */
    public static final class Item {
        public final String key;      // StatusBarNotification key — needed to cancel later if judged ad
        public final String pkg;
        public final String title;
        public final String text;
        public final long postedAt;

        Item(String key, String pkg, String title, String text, long postedAt) {
            this.key = key;
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.postedAt = postedAt;
        }
    }

    private final File file;
    private final ArrayDeque<Item> items = new ArrayDeque<>();

    public PendingQueueStore(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(),
                "assistant_pending_queue.json");
        load();
    }

    /** Append one notification; drops the oldest entries when over cap. */
    public synchronized void enqueue(Item item) {
        items.addLast(item);
        while (items.size() > MAX_ITEMS) {
            Item dropped = items.pollFirst();
            if (dropped != null) {
                Log.w(TAG, "queue full (" + MAX_ITEMS + ") — dropped oldest pkg="
                        + dropped.pkg + " postedAt=" + dropped.postedAt);
            }
        }
        persist();
    }

    /** Remove and return up to {@code max} oldest items (R3: oldest first). */
    public synchronized List<Item> drainBatch(int max) {
        List<Item> out = new ArrayList<>();
        while (!items.isEmpty() && out.size() < max) {
            out.add(items.pollFirst());
        }
        if (!out.isEmpty()) {
            persist();
        }
        return out;
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized void clear() {
        if (items.isEmpty()) {
            return;
        }
        items.clear();
        persist();
    }

    /** Peek without draining — for the debug/status page. Oldest first. */
    public synchronized List<Item> peekOldest(int max) {
        List<Item> out = new ArrayList<>();
        Iterator<Item> it = items.iterator();
        while (it.hasNext() && out.size() < max) {
            out.add(it.next());
        }
        return out;
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
                items.addLast(new Item(
                        o.optString("key", null),
                        o.optString("pkg", null),
                        o.optString("title", null),
                        o.optString("text", null),
                        o.optLong("postedAt", 0L)));
            }
            Log.i(TAG, "pending queue restored: " + items.size() + " item(s)");
        } catch (Throwable t) {
            // Corrupt file must not brick the assistant; start empty.
            Log.w(TAG, "pending queue restore failed, starting empty", t);
            items.clear();
        }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("key", it.key);
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
                Log.w(TAG, "pending queue replace failed");
            }
        } catch (IOException | org.json.JSONException | RuntimeException t) {
            Log.w(TAG, "pending queue persist failed", t);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
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
