package ru.poneslas.tasya.probe.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import org.json.JSONObject
import ru.poneslas.tasya.probe.TAG
import java.io.File

/** Статусы сессии PackageInstaller (П14). Пишутся в файл: при успехе процесс тут же убивают. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        InstallLog.add(context, "status", "${statusName(status)} ($status) $msg")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            // Запуск из приёмника разрешён, пока пробник на переднем плане [?]; иначе окно не появится.
            runCatching { context.startActivity(confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { InstallLog.add(context, "confirm_failed", "${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}

fun statusName(s: Int) = when (s) {
    PackageInstaller.STATUS_PENDING_USER_ACTION -> "PENDING_USER_ACTION"
    PackageInstaller.STATUS_SUCCESS -> "SUCCESS"
    PackageInstaller.STATUS_FAILURE -> "FAILURE"
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "FAILURE_BLOCKED"
    PackageInstaller.STATUS_FAILURE_ABORTED -> "FAILURE_ABORTED"
    PackageInstaller.STATUS_FAILURE_INVALID -> "FAILURE_INVALID"
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "FAILURE_CONFLICT"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "FAILURE_STORAGE"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILURE_INCOMPATIBLE"
    PackageInstaller.STATUS_FAILURE_TIMEOUT -> "FAILURE_TIMEOUT"
    else -> "UNKNOWN"
}

/** Журнал установок П14: шаги и статусы, переживает замену пакета. */
object InstallLog {
    data class Entry(val wall: Long, val tag: String, val text: String)

    private fun file(ctx: Context) = File(ctx.filesDir, "install.jsonl")

    fun add(ctx: Context, tag: String, text: String) {
        Log.i(TAG, "install $tag $text")
        val o = JSONObject().put("wall", System.currentTimeMillis()).put("tag", tag).put("text", text)
        runCatching { file(ctx).appendText(o.toString() + "\n") }
    }

    fun since(ctx: Context, wall: Long): List<Entry> = runCatching {
        file(ctx).readLines().mapNotNull { l ->
            runCatching { JSONObject(l).let { Entry(it.getLong("wall"), it.getString("tag"), it.getString("text")) } }.getOrNull()
        }.filter { it.wall >= wall }
    }.getOrDefault(emptyList())
}
