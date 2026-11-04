package com.alibaba.mnnllm.android.assistant.notify;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spec-002 task 7 (R5): cache of the LAST successful summary so the summary
 * screen shows the previous result instantly (and with the screen off / before
 * any model is installed) instead of an empty page. Regenerated on demand by
 * the user — inference only happens when the screen is on and they ask for it.
 *
 * One JSON file, atomic replace.
 */
public final class SummaryStore {

    static final String TAG = "SmartNotify";

    public static final class Snapshot {
        public final String text;        // raw model output (fallback display)
        public final String modelId;
        public final long generatedAt;

        Snapshot(String text, String modelId, long generatedAt) {
            this.text = text;
            this.modelId = modelId;
            this.generatedAt = generatedAt;
        }
    }

    private final File file;

    public SummaryStore(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(),
                "assistant_summary_cache.json");
    }

    public Snapshot get() {
        try {
            if (!file.exists()) {
                return null;
            }
            JSONObject o = new JSONObject(new String(readAll(file), StandardCharsets.UTF_8));
            return new Snapshot(o.optString("text", null),
                    o.optString("modelId", ""), o.optLong("generatedAt", 0L));
        } catch (Throwable t) {
            Log.w(TAG, "summary cache read failed", t);
            return null;
        }
    }

    public void put(String text, String modelId) {
        try {
            JSONObject o = new JSONObject();
            o.put("text", text);
            o.put("modelId", modelId);
            o.put("generatedAt", System.currentTimeMillis());
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(o.toString().getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            if (!tmp.renameTo(file) && file.exists() && !file.delete()) {
                Log.w(TAG, "summary cache replace failed");
            }
        } catch (IOException | org.json.JSONException | RuntimeException t) {
            Log.w(TAG, "summary cache write failed", t);
        }
    }

    public void clear() {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "summary cache delete failed");
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
