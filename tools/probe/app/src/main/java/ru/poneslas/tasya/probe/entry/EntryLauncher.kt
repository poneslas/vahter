package ru.poneslas.tasya.probe.entry

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import ru.poneslas.tasya.probe.TAG
import ru.poneslas.tasya.probe.assist.Sheet

/**
 * Общий путь «вход → лист» для ярлыков, плитки и уведомлений (П11, П12).
 * В args листа кладём src (откуда вошли), t_entry (uptime входа) и wait_ms (сколько ждали сервис).
 */
object EntryLauncher {
    /** События входов — проверки собирают их в свой лог. */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 64)

    fun note(s: String) {
        Log.i(TAG, "entry $s")
        events.tryEmit(s)
    }

    /** 11.1/11.3/12а/12б: showSession у живого сервиса. Если процесс только поднят — ждём onReady до 1 с. */
    suspend fun viaService(src: String, args: Bundle, tEntry: Long): Boolean {
        val t = SystemClock.uptimeMillis()
        val svc = Sheet.awaitService(1000)
        val waited = SystemClock.uptimeMillis() - t
        if (svc == null) {
            note("$src: сервис не поднялся за 1 с — лист не открыт")
            return false
        }
        args.putString("src", src)
        args.putLong("t_entry", tEntry)
        args.putLong("wait_ms", waited)
        svc.showSession(args, 0)
        note("$src: showSession, ждали сервис $waited мс")
        return true
    }

    /** 11.2/12в: Activity.showAssist — работает только у активности на переднем плане. */
    fun viaShowAssist(activity: Activity, src: String, args: Bundle, tEntry: Long): Boolean {
        args.putString("src", src)
        args.putLong("t_entry", tEntry)
        val ok = runCatching { activity.showAssist(args) }
            .onFailure { note("$src: showAssist бросил ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
        note("$src: showAssist вернул $ok")
        return ok
    }

    /** Аргументы листа из extras интента: mode, camera, collapse, locked. */
    fun argsFrom(extras: Bundle?): Bundle = Bundle().apply {
        extras?.getString("mode")?.let { putString("mode", it) }
        extras?.getString("camera")?.let { putString("camera", it) }
        if (extras?.getBoolean("collapse") == true) putBoolean("collapse", true)
        if (extras?.containsKey("locked") == true) putBoolean("locked", extras.getBoolean("locked"))
    }
}
