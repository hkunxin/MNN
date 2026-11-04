// Spec-002 task 9 (R8): meeting record storage — transcript sentences are
// appended to a per-meeting JSON file after EVERY confirmed chunk, so a
// process crash or battery loss never loses more than the in-flight chunk.
// Minutes are cached in the same file once generated.
package com.alibaba.mnnllm.android.assistant.notify.meeting

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MeetingSentence(val atMs: Long, val text: String)

data class Meeting(
    val id: String,
    val title: String,
    val startedAt: Long,
    var endedAt: Long,          // 0 = still open
    val sentences: MutableList<MeetingSentence>,
    var minutes: String         // "" until generated
)

class MeetingStore(private val context: Context) {

    companion object {
        private const val TAG = "MeetingStore"
        private const val DIR = "meetings"
    }

    private fun dir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun fileFor(id: String): File = File(dir(), "$id.json")

    /** Append one sentence and persist immediately (逐句落盘). */
    fun appendSentence(id: String, atMs: Long, text: String) {
        if (text.isBlank()) return
        val m = load(id) ?: return
        m.sentences.add(MeetingSentence(atMs, text.trim()))
        save(m)
    }

    fun create(title: String): Meeting {
        val id = "mtg_" + System.currentTimeMillis()
        val m = Meeting(id, title.ifBlank { "未命名会议" },
                System.currentTimeMillis(), 0L, mutableListOf(), "")
        save(m)
        return m
    }

    fun load(id: String): Meeting? = try {
        val f = fileFor(id)
        if (!f.exists()) null else fromJson(JSONObject(f.readText()))
    } catch (t: Throwable) {
        Log.w(TAG, "load meeting failed: $id", t)
        null
    }

    /** The still-open meeting, if any (crash-recovery / resume). */
    fun loadOpen(): Meeting? {
        val all = list()
        return all.firstOrNull { it.endedAt == 0L }
    }

    fun list(): List<Meeting> {
        val out = ArrayList<Meeting>()
        val files = dir().listFiles { f -> f.name.endsWith(".json") } ?: return out
        for (f in files) {
            try {
                out.add(fromJson(JSONObject(f.readText())))
            } catch (t: Throwable) {
                Log.w(TAG, "skip corrupt meeting file: ${f.name}", t)
            }
        }
        out.sortByDescending { it.startedAt }
        return out
    }

    fun save(m: Meeting) {
        try {
            val tmp = File(dir(), m.id + ".json.tmp")
            tmp.writeText(toJson(m).toString())
            val dst = fileFor(m.id)
            if (!tmp.renameTo(dst)) {
                dst.writeText(toJson(m).toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "save meeting failed: ${m.id}", t)
        }
    }

    fun delete(id: String): Boolean = fileFor(id).delete()

    fun setMinutes(id: String, minutes: String) {
        val m = load(id) ?: return
        m.minutes = minutes
        save(m)
    }

    fun transcript(m: Meeting): String =
        m.sentences.joinToString("\n") { it.text }

    // ------------------------------------------------------------ json

    private fun toJson(m: Meeting): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("title", m.title)
        put("startedAt", m.startedAt)
        put("endedAt", m.endedAt)
        put("minutes", m.minutes)
        val arr = JSONArray()
        for (s in m.sentences) {
            arr.put(JSONObject().put("at", s.atMs).put("text", s.text))
        }
        put("sentences", arr)
    }

    private fun fromJson(o: JSONObject): Meeting {
        val sentences = ArrayList<MeetingSentence>()
        val arr = o.optJSONArray("sentences") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            sentences.add(MeetingSentence(s.optLong("at", 0L), s.optString("text", "")))
        }
        return Meeting(
                o.optString("id", "mtg_unknown"),
                o.optString("title", "未命名会议"),
                o.optLong("startedAt", 0L),
                o.optLong("endedAt", 0L),
                sentences,
                o.optString("minutes", ""))
    }
}
