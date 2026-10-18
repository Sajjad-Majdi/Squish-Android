package com.artin.pinch.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

@Immutable
class Entry(
    val name: String,
    val inSize: Long,
    val outSize: Long,
    val uri: Uri,
    val video: Boolean,
    val time: Long,
    /** Measured quality, 0–100; −1 when unknown. */
    val score: Float = -1f,
)

/** Past results, newest first, kept in one small JSON file. */
object History {
    val entries = mutableStateListOf<Entry>()
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "history.json")
        runCatching {
            if (!file.exists()) return
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                entries += Entry(
                    o.getString("n"), o.getLong("i"), o.getLong("o"), Uri.parse(o.getString("u")),
                    o.getBoolean("v"), o.getLong("t"), o.optDouble("q", -1.0).toFloat(),
                )
            }
        }
    }

    /** Main thread only. */
    fun add(e: Entry) {
        entries.add(0, e)
        while (entries.size > MAX) entries.removeAt(entries.lastIndex)
        Prefs.addSaved(e.inSize - e.outSize)
        save()
    }

    fun remove(e: Entry) { entries.remove(e); save() }

    fun clear() { entries.clear(); save() }

    private fun save() {
        val snapshot = entries.toList()
        io.execute {
            val a = JSONArray()
            for (e in snapshot) a.put(
                JSONObject().put("n", e.name).put("i", e.inSize).put("o", e.outSize)
                    .put("u", e.uri.toString()).put("v", e.video).put("t", e.time).put("q", e.score.toDouble()),
            )
            runCatching {
                val tmp = File(file.parentFile, "history.tmp")
                tmp.writeText(a.toString())
                tmp.renameTo(file)
            }
        }
    }

    private const val MAX = 500
}
