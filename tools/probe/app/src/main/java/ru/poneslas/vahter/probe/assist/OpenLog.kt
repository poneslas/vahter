package ru.poneslas.vahter.probe.assist

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Журнал всех открытий листа, переживает смерть процесса: П2 считает холодные открытия
 * по записям, сделанным уже в новом процессе.
 */
object OpenLog {
    data class Entry(
        val wall: Long, val seq: Int, val flags: Int, val mode: String?,
        val prepareToFrame: Long, val showToFrame: Long, val callToFrame: Long?,
        val fresh: Boolean, val newSession: Boolean, val locked: Boolean,
        val invokeToFrame: Long?,
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "opens.jsonl")

    fun add(ctx: Context, r: OpenRecord) {
        val o = JSONObject()
            .put("wall", r.wallTime).put("seq", r.seq).put("flags", r.flags).put("mode", r.mode ?: "")
            .put("p2f", r.prepareToFrame).put("s2f", r.showToFrame).put("c2f", r.callToFrame ?: -1)
            .put("fresh", r.freshProcess).put("newSession", r.newSession).put("locked", r.locked)
            .put("i2f", r.invokeToFrame ?: -1)
        runCatching { file(ctx).appendText(o.toString() + "\n") }
    }

    fun since(ctx: Context, wall: Long): List<Entry> = runCatching {
        file(ctx).readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Entry(
                    o.getLong("wall"), o.getInt("seq"), o.getInt("flags"), o.optString("mode").ifEmpty { null },
                    o.getLong("p2f"), o.getLong("s2f"), o.getLong("c2f").takeIf { it >= 0 },
                    o.getBoolean("fresh"), o.getBoolean("newSession"), o.getBoolean("locked"),
                    o.optLong("i2f", -1).takeIf { it >= 0 },
                )
            }.getOrNull()
        }.filter { it.wall >= wall }
    }.getOrDefault(emptyList())
}
