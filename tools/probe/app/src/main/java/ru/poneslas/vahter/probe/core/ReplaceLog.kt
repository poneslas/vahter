package ru.poneslas.vahter.probe.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import ru.poneslas.vahter.probe.TAG
import java.io.File

/** Журнал обновлений пробника: версия и роль после замены пакета. Переживает процесс. */
object ReplaceLog {
    data class Entry(val wall: Long, val versionCode: Long, val versionName: String, val stage: String, val roleHeld: Boolean, val visActive: Boolean, val installer: String)

    private fun file(ctx: Context) = File(ctx.filesDir, "replaced.jsonl")

    fun add(ctx: Context, stage: String, roleHeld: Boolean, visActive: Boolean) {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val installer = runCatching { ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName }.getOrNull() ?: "?"
        val o = JSONObject().put("wall", System.currentTimeMillis()).put("vc", pi.longVersionCode).put("vn", pi.versionName)
            .put("stage", stage).put("role", roleHeld).put("vis", visActive).put("installer", installer)
        Log.i(TAG, "replaced $o")
        file(ctx).appendText(o.toString() + "\n")
    }

    fun since(ctx: Context, wall: Long): List<Entry> = runCatching {
        file(ctx).readLines().mapNotNull { l ->
            runCatching {
                val o = JSONObject(l)
                Entry(o.getLong("wall"), o.getLong("vc"), o.getString("vn"), o.getString("stage"), o.getBoolean("role"), o.getBoolean("vis"), o.optString("installer"))
            }.getOrNull()
        }.filter { it.wall >= wall }
    }.getOrDefault(emptyList())
}
