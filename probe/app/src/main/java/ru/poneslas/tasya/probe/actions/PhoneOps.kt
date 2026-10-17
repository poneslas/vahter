package ru.poneslas.tasya.probe.actions

import android.Manifest
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.tasya.probe.core.ProbeScope
import java.util.TimeZone
import kotlin.coroutines.resume

/* Действия класса A/C (Архитектура, 7). Каждое возвращает телефон в исходное состояние. */

fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

// ---------- Фонарик ----------

/** Включить → дождаться TorchCallback → вернуть исходное. Возвращает «включился за N мс» или null. */
suspend fun ProbeScope.torchCycle(tag: String): Long? {
    val cm = ctx.getSystemService(CameraManager::class.java)
    val id = cm.cameraIdList.firstOrNull {
        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
    if (id == null) {
        log("torch_$tag", "вспышки нет (эмулятор?)")
        return null
    }
    var current: Boolean? = null
    var waiter: CompletableDeferred<Boolean>? = null
    val cb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != id) return
            current = enabled
            waiter?.complete(enabled)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == id) log("torch_$tag", "unavailable — камера занята?")
        }
    }
    cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
    try {
        // Первый колбэк сразу после регистрации сообщает текущее состояние — это и есть «как было».
        withTimeoutOrNull(500) { while (current == null) delay(20) }
        val initial = current ?: false
        log("torch_$tag", "исходно=$initial")
        waiter = CompletableDeferred()
        val t0 = SystemClock.uptimeMillis()
        try {
            cm.setTorchMode(id, true)
        } catch (e: Exception) {
            error(e, "torch on $tag")
            return null
        }
        val on = withTimeoutOrNull(1500) { waiter!!.await() }
        val dt = SystemClock.uptimeMillis() - t0
        log("torch_$tag", "включился=$on за $dt мс")
        delay(600)
        try {
            cm.setTorchMode(id, initial)
        } catch (e: Exception) {
            error(e, "torch restore $tag")
        }
        return if (on == true) dt else null
    } finally {
        cm.unregisterTorchCallback(cb)
    }
}

// ---------- Громкость ----------

val streams = listOf(
    AudioManager.STREAM_MUSIC to "music", AudioManager.STREAM_RING to "ring",
    AudioManager.STREAM_ALARM to "alarm", AudioManager.STREAM_NOTIFICATION to "notification",
    AudioManager.STREAM_VOICE_CALL to "voice_call", AudioManager.STREAM_SYSTEM to "system",
)

/** Шаг громкости каждого потока и возврат. Возвращает потоки, где изменение или возврат не прошли. */
fun ProbeScope.volumeMatrix(tag: String): List<String> {
    val am = ctx.getSystemService(AudioManager::class.java)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    log("volume_$tag", "ringerMode=${am.ringerMode} фильтр=${nm.currentInterruptionFilter} DND-доступ=${nm.isNotificationPolicyAccessGranted}")
    val failed = mutableListOf<String>()
    for ((s, name) in streams) {
        val v0 = am.getStreamVolume(s)
        val max = am.getStreamMaxVolume(s)
        val min = am.getStreamMinVolume(s)
        val target = if (v0 < max) v0 + 1 else (v0 - 1).coerceAtLeast(min)
        try {
            am.setStreamVolume(s, target, 0)
            val v1 = am.getStreamVolume(s)
            am.setStreamVolume(s, v0, 0)
            val v2 = am.getStreamVolume(s)
            log("volume_$tag", "$name: $v0 → $target (стало $v1) → вернули $v2; диапазон $min..$max")
            if (v1 != target || v2 != v0) failed += "$name(стало $v1, вернули $v2)"
        } catch (e: SecurityException) {
            log("volume_$tag", "$name: SecurityException ${e.message}")
            failed += "$name(SecurityException)"
        }
    }
    return failed
}

// ---------- Место ----------

/** Одна точка места без GMS. Возвращает (точка или null, мс; -1 — не запрашивали). */
suspend fun ProbeScope.oneLocation(tag: String, timeoutMs: Long = 15_000): Pair<Location?, Long> {
    if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) {
        log("location_$tag", "нет разрешения на место")
        return null to -1
    }
    val lm = ctx.getSystemService(LocationManager::class.java)
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { lm.allProviders.contains(it) && lm.isProviderEnabled(it) }
    if (provider == null) {
        log("location_$tag", "ни один провайдер не включён: ${lm.allProviders}; место выключено?")
        return null to -1
    }
    val t0 = SystemClock.uptimeMillis()
    val signal = CancellationSignal()
    val loc = try {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { c ->
                c.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, ctx.mainExecutor) { c.resume(it) }
            }
        }
    } catch (e: SecurityException) {
        error(e, "location $tag")
        null
    }
    val dt = SystemClock.uptimeMillis() - t0
    val desc = loc?.let { "точность ${it.accuracy} м, возраст ${(SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000} мс" } ?: "null"
    log("location_$tag", "провайдер=$provider: $desc за $dt мс")
    return loc to dt
}

// ---------- Календарь ----------

data class Cal(val id: Long, val name: String, val account: String, val type: String, val access: Int, val visible: Boolean)

fun ProbeScope.calendars(): List<Cal> {
    val out = mutableListOf<Cal>()
    ctx.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE,
        ), null, null, null,
    )?.use { c ->
        while (c.moveToNext()) out += Cal(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getInt(4), c.getInt(5) == 1)
    }
    out.forEach { log("calendar", "#${it.id} «${it.name}» ${it.account} [${it.type}] access=${it.access} visible=${it.visible}") }
    return out
}

/** Google-календарь в приоритете: его видно и в Samsung Calendar, и в Google [?]. */
fun pickWritableCalendar(list: List<Cal>) =
    list.firstOrNull { it.visible && it.access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR && it.type == "com.google" }
        ?: list.firstOrNull { it.visible && it.access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR }

/** Тестовое событие через час, 15 минут. Возвращает id события или null. */
fun ProbeScope.insertEvent(cal: Cal, title: String): Long? {
    val start = System.currentTimeMillis() + 3_600_000
    val v = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, cal.id)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DTSTART, start)
        put(CalendarContract.Events.DTEND, start + 15 * 60_000)
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
    }
    return try {
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
        log("calendar_insert", "в «${cal.name}» → $uri")
        uri?.let { ContentUris.parseId(it) }
    } catch (e: Exception) {
        error(e, "calendar insert")
        null
    }
}

/** Удалить событие; true — удалено (синхронизируемые календари до синхронизации держат DELETED=1 — это тоже удалено). */
fun ProbeScope.deleteEvent(id: Long): Boolean {
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
    val rows = try {
        ctx.contentResolver.delete(uri, null, null)
    } catch (e: Exception) {
        error(e, "calendar delete")
        0
    }
    val stillVisible = ctx.contentResolver.query(uri, arrayOf(CalendarContract.Events.DELETED), null, null, null)?.use { c ->
        c.moveToFirst() && c.getInt(0) == 0
    } ?: false
    log("calendar_delete", "строк=$rows, осталось видимым=$stillVisible")
    return rows > 0 && !stillVisible
}
