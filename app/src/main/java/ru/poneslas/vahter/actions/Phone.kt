package ru.poneslas.vahter.actions

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * Исполнение действий на телефоне: только системные API и интенты, без своих серверов.
 * Каждое возвращает Result — ошибки превращаются в понятный мозгу и человеку текст в [PhoneTools].
 */
object Phone {
    private val ru = Locale("ru")

    // ── Часы ─────────────────────────────────────────────────────────────

    /** Ближайшее срабатывание будильника ЧЧ:ММ — для «прозвенит через …». */
    fun nextOccurrence(h: Int, m: Int): Long {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Выключить ближайшее срабатывание: по времени, по подписи или просто ближайший. Удалить будильник API не даёт. */
    fun dismissAlarm(ctx: Context, h: Int?, m: Int?, label: String?): Result<Unit> {
        val i = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        when {
            h != null -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m ?: 0).putExtra(AlarmClock.EXTRA_IS_PM, h >= 12)
            label != null -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL).putExtra(AlarmClock.EXTRA_MESSAGE, label)
            else -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        }
        // «Часы» могут показать список, если подходящих несколько, — поэтому через Launch (поверх листа).
        return Launch.activity(ctx, i)
    }

    /** Выключить звенящий таймер. Идущий таймер другому приложению не остановить — только показать таймеры. */
    fun dismissTimer(ctx: Context): Result<Unit> = Launch.activity(ctx, Intent(AlarmClock.ACTION_DISMISS_TIMER))
    fun showTimers(ctx: Context): Result<Unit> = Launch.activity(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS))

    /** Ближайший будильник любого приложения (системный). */
    fun nextAlarm(ctx: Context): Long? = ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime

    // ── Календарь ────────────────────────────────────────────────────────

    data class Event(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?, val calendar: String?)

    fun events(ctx: Context, from: Long, to: Long): List<Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val proj = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val out = mutableListOf<Event>()
        ctx.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 50) out += Event(c.getLong(0), c.getString(1) ?: tr(R.string.phone_untitled), c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5), c.getString(6))
        }
        return out
    }

    /** Основной календарь, куда можно писать: помеченный основным, иначе первый с правом записи. */
    private fun writableCalendar(ctx: Context): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE)
        var first: Long? = null
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || c.getInt(3) == 0) continue
                if (c.getInt(1) == 1) return c.getLong(0)
                if (first == null) first = c.getLong(0)
            }
        }
        return first
    }

    fun addEvent(ctx: Context, title: String, begin: Long, end: Long, allDay: Boolean, location: String?): Result<Long> = runCatching {
        val cal = writableCalendar(ctx) ?: error("no_calendar")
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal); put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin); put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: error("insert_failed")
        ContentUris.parseId(uri)
    }

    fun deleteEvent(ctx: Context, id: Long) = runCatching {
        ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
    }

    fun openEvent(ctx: Context, id: Long) = Launch.activity(ctx, Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)))

    // ── Телефон ──────────────────────────────────────────────────────────

    fun torch(ctx: Context, on: Boolean): Result<Unit> = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: error("no_flash")
        cm.setTorchMode(id, on)
    }

    val streams = mapOf("media" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING, "alarm" to AudioManager.STREAM_ALARM, "notification" to AudioManager.STREAM_NOTIFICATION)
    val streamTitles = mapOf("media" to tr(R.string.phone_stream_media), "ring" to tr(R.string.phone_stream_call), "alarm" to tr(R.string.phone_stream_alarm), "notification" to tr(R.string.phone_stream_notif))

    /** Громкость в процентах: возвращает (было, стало). */
    fun volume(ctx: Context, stream: String, percent: Int?, step: Int?): Result<Pair<Int, Int>> = runCatching {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = streams[stream] ?: AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s); val min = am.getStreamMinVolume(s)
        val before = am.getStreamVolume(s)
        val target = when {
            percent != null -> (percent.coerceIn(0, 100) * max + 50) / 100
            step != null -> before + step * maxOf(1, max / 10)
            else -> before
        }.coerceIn(min, max)
        am.setStreamVolume(s, target, 0)
        (before * 100 / max) to (target * 100 / max)
    }

    fun volumeRaw(ctx: Context, stream: String, percent: Int) = runCatching {
        val am = ctx.getSystemService(AudioManager::class.java); val s = streams[stream] ?: AudioManager.STREAM_MUSIC
        am.setStreamVolume(s, (percent * am.getStreamMaxVolume(s) + 50) / 100, 0)
    }

    fun dndGranted(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /** Включить «Не беспокоить» (только важные) или выключить. Возвращает прежний режим — для «Отменить». */
    fun dnd(ctx: Context, on: Boolean): Result<Int> = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) error("no_access")
        val before = nm.currentInterruptionFilter
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        before
    }

    fun dndRestore(ctx: Context, filter: Int) = runCatching { ctx.getSystemService(NotificationManager::class.java).setInterruptionFilter(filter) }

    fun openDndAccess(ctx: Context) = Launch.activity(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

    fun media(ctx: Context, action: String): Result<Unit> = runCatching {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Текущее местоположение: свежая точка за 10 с, иначе последняя известная. */
    @SuppressLint("MissingPermission")
    suspend fun location(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { lm.isProviderEnabled(it) }
            ?: return null
        val fresh = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Location?> { c -> lm.getCurrentLocation(provider, null, ctx.mainExecutor) { c.resume(it) } }
        }
        return fresh ?: listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /** Адрес по координатам (сервис геокодера телефона; без сети может не ответить). */
    suspend fun address(ctx: Context, l: Location): String? = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { c ->
            runCatching {
                Geocoder(ctx, ru).getFromLocation(l.latitude, l.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(list: MutableList<android.location.Address>) {
                        val a = list.firstOrNull()
                        c.resume(a?.let { listOfNotNull(it.thoroughfare?.let { t -> t + (it.subThoroughfare?.let { n -> ", $n" } ?: "") }, it.locality).joinToString(", ").ifBlank { it.getAddressLine(0) } })
                    }
                    override fun onError(errorMessage: String?) { c.resume(null) }
                })
            }.onFailure { c.resume(null) }
        }
    }

    fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val r = FloatArray(1); Location.distanceBetween(aLat, aLon, bLat, bLon, r); return r[0]
    }

    fun openMap(ctx: Context, lat: Double, lon: Double, label: String) =
        Launch.activity(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")))

    // ── Приложения и связь ───────────────────────────────────────────────

    data class AppEntry(val label: String, val pkg: String)

    fun apps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }.distinctBy { it.pkg }
    }

    /** Найти приложение по названию: точное, начало, вхождение — без учёта регистра и «ё». */
    fun findApp(ctx: Context, name: String): AppEntry? {
        fun n(s: String) = s.lowercase(ru).replace('ё', 'е').trim()
        val q = n(name)
        val all = apps(ctx)
        return all.firstOrNull { n(it.label) == q } ?: all.firstOrNull { n(it.label).startsWith(q) } ?: all.firstOrNull { n(it.label).contains(q) }
            ?: all.firstOrNull { q.contains(n(it.label)) && it.label.length > 2 }
    }

    fun openApp(ctx: Context, pkg: String): Result<Unit> {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return Result.failure(IllegalStateException("no_launch"))
        return Launch.activity(ctx, i)
    }

    val settingsScreens = linkedMapOf(
        "main" to (tr(R.string.ui_settings) to Settings.ACTION_SETTINGS),
        "wifi" to ("Wi-Fi" to Settings.ACTION_WIFI_SETTINGS),
        "bluetooth" to ("Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS),
        "mobile" to (tr(R.string.phone_screen_mobile) to Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        "airplane" to (tr(R.string.phone_screen_airplane) to Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        "display" to (tr(R.string.journal_screen) to Settings.ACTION_DISPLAY_SETTINGS),
        "sound" to (tr(R.string.phone_screen_sound) to Settings.ACTION_SOUND_SETTINGS),
        "battery" to (tr(R.string.phone_screen_battery) to Intent.ACTION_POWER_USAGE_SUMMARY),
        "location" to (tr(R.string.phone_screen_location) to Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        "apps" to (tr(R.string.phone_screen_apps) to Settings.ACTION_APPLICATION_SETTINGS),
        "notifications" to (tr(R.string.set_notif) to "android.settings.NOTIFICATION_SETTINGS"),
        "dnd" to (tr(R.string.phone_screen_dnd) to "android.settings.ZEN_MODE_SETTINGS"),
        "storage" to (tr(R.string.set_cap_memory) to Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        "security" to (tr(R.string.phone_screen_security) to Settings.ACTION_SECURITY_SETTINGS),
        "language" to (tr(R.string.phone_screen_language) to Settings.ACTION_LOCALE_SETTINGS),
        "date" to (tr(R.string.phone_screen_datetime) to Settings.ACTION_DATE_SETTINGS),
        "accessibility" to (tr(R.string.phone_screen_accessibility) to Settings.ACTION_ACCESSIBILITY_SETTINGS),
        "assistant" to (tr(R.string.set_assistant_role) to Settings.ACTION_VOICE_INPUT_SETTINGS),
        "this_app" to (tr(R.string.phone_screen_app_info) to Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
    )

    fun openSettings(ctx: Context, screen: String): Result<String> {
        val (title, action) = settingsScreens[screen] ?: return Result.failure(IllegalArgumentException("unknown_screen"))
        val i = Intent(action)
        if (screen == "this_app") i.data = Uri.fromParts("package", ctx.packageName, null)
        return Launch.activity(ctx, i).map { title }
    }

    data class Contact(val name: String, val number: String)

    /** Номера по имени из «Контактов» (сначала точные совпадения). */
    fun contacts(ctx: Context, name: String): List<Contact> {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        val out = mutableListOf<Contact>()
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < 10) out += Contact(c.getString(0) ?: "", c.getString(1) ?: "")
        }
        return out.distinctBy { it.number.filter(Char::isDigit) }
            .sortedBy { if (it.name.equals(name, ignoreCase = true)) 0 else 1 }
    }

    fun dial(ctx: Context, number: String) = Launch.activity(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))

    /** Подготовить сообщение: SMS — с адресатом и текстом; WhatsApp — с номером, если есть; Telegram — выбор чата в самом Telegram. */
    fun draft(ctx: Context, app: String, number: String?, text: String): Result<Unit> = when (app) {
        "whatsapp" -> if (number != null) Launch.activity(ctx, Intent(Intent.ACTION_VIEW,
                Uri.parse("https://api.whatsapp.com/send?phone=${number.filter(Char::isDigit)}&text=${Uri.encode(text)}")).setPackage("com.whatsapp"))
            else Launch.activity(ctx, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.whatsapp"))
        "telegram" -> Launch.activity(ctx, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("org.telegram.messenger"))
        else -> Launch.activity(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number ?: ""))).putExtra("sms_body", text))
    }

    // ── Время для мозга и строк ──────────────────────────────────────────

    fun hm(t: Long): String = SimpleDateFormat("H:mm", ru).format(Date(t))
    fun dayHm(t: Long): String = SimpleDateFormat("EEE, d MMM, H:mm", ru).format(Date(t))
    fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT).format(Date(t))

    /** «2025-10-20T10:00» или «2025-10-20» → миллисекунды (местное время). */
    fun parseIso(s: String): Pair<Long, Boolean>? {
        val t = s.trim()
        runCatching { return SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT).parse(t)!!.time to false }
        runCatching { return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse(t)!!.time to false }
        runCatching { return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(t)!!.time to true }
        return null
    }

    /** «через 9 ч 12 мин» / «через 5 мин» / «через 40 с». */
    fun inText(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = s / 60 % 60
        return when {
            h > 0 -> tr(R.string.phone_in_hours, h) + if (m > 0) tr(R.string.phone_minutes_tail, m) else ""
            m > 0 -> tr(R.string.phone_in_minutes, m)
            else -> tr(R.string.phone_in_seconds, s)
        }
    }

    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
