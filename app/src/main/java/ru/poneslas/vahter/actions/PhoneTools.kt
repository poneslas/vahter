package ru.poneslas.vahter.actions

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import android.app.KeyguardManager
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.poneslas.vahter.brain.ToolHost
import ru.poneslas.vahter.brain.ToolSpec
import ru.poneslas.vahter.core.FeedItem
import ru.poneslas.vahter.core.LockScope
import ru.poneslas.vahter.core.Mode
import ru.poneslas.vahter.core.Note
import ru.poneslas.vahter.core.Policy
import ru.poneslas.vahter.core.Settings
import ru.poneslas.vahter.ui.Note
import ru.poneslas.vahter.trp

/**
 * Действия телефона для мозга (Архитектура 7, Б.1–Б.3): описание функции → «Разрешено / Спрашивать /
 * Не разрешено» → экран блокировки → доступы Android → исполнение → строка итога и запись в журнал.
 * Мозгу всегда возвращается понятный результат, а не тишина.
 */
class PhoneTools(
    private val context: Context,
    private val settings: () -> Settings,
    /** Карточка «Да / Отмена» в разговоре; true — пользователь согласился. */
    private val confirm: suspend (title: String, detail: String) -> Boolean,
    /** Строка итога в ленте (id назначает разговор). */
    private val report: (FeedItem.Act) -> Unit,
    /** Запись журнала: действие, параметры, итог, статус. */
    private val notes: () -> List<Note>,
    private val saveNote: (Note) -> Unit,
    private val deleteNote: (Long) -> Unit,
    /** Когда должны прозвенеть таймеры, которые ставили мы (по строкам разговора). */
    private val timers: () -> List<Long>,
) : ToolHost {

    private fun spec(name: String, desc: String, props: String, required: String = "") = ToolSpec(name, desc,
        """{"type":"object","properties":{$props}${if (required.isNotEmpty()) ""","required":[$required]""" else ""}}""")

    override fun specs(mode: Mode): List<ToolSpec> = if (!mode.tools) emptyList() else listOf(
        spec("alarm_set", tr(R.string.act_alarm_set_desc),
            """"time":{"type":"string","description":"ЧЧ:ММ, 24 часа"},"label":{"type":"string","description":"Подпись, до 40 символов"}""", "\"time\""),
        spec("alarm_dismiss", tr(R.string.act_alarm_off_desc),
            """"time":{"type":"string","description":"ЧЧ:ММ — только если человек сам назвал время будильника"},"label":{"type":"string"}"""),
        spec("alarm_next", tr(R.string.act_alarm_next_desc), ""),
        spec("timer_set", tr(R.string.act_timer_set_desc),
            """"seconds":{"type":"integer","description":"1–86400"},"label":{"type":"string"}""", "\"seconds\""),
        spec("timer_stop", tr(R.string.act_timer_off_desc), ""),
        spec("calendar_query", tr(R.string.act_cal_query_desc),
            """"from":{"type":"string","description":"Дата начала ГГГГ-ММ-ДД"},"days":{"type":"integer","description":"Сколько дней, по умолчанию 1"}""", "\"from\""),
        spec("calendar_add", tr(R.string.act_cal_add_desc),
            """"title":{"type":"string"},"start":{"type":"string","description":"ГГГГ-ММ-ДДTЧЧ:ММ или ГГГГ-ММ-ДД для события на весь день"},"minutes":{"type":"integer","description":"Длительность, по умолчанию 60"},"location":{"type":"string"}""",
            "\"title\",\"start\""),
        spec("calendar_delete", tr(R.string.act_cal_del_desc),
            """"title":{"type":"string","description":"Слова из названия"},"date":{"type":"string","description":"ГГГГ-ММ-ДД, если известно"}""", "\"title\""),
        spec("torch_set", tr(R.string.act_torch_desc), """"on":{"type":"boolean"}""", "\"on\""),
        spec("volume_set", tr(R.string.act_volume_desc),
            """"stream":{"type":"string","enum":["media","ring","alarm","notification"],"description":"media — музыка и видео"},"percent":{"type":"integer","description":"0–100"},"step":{"type":"integer","description":"+1 громче, −1 тише"}""",
            "\"stream\""),
        spec("dnd_set", tr(R.string.act_dnd_desc), """"on":{"type":"boolean"}""", "\"on\""),
        spec("media_control", tr(R.string.act_media_desc),
            """"action":{"type":"string","enum":["play","pause","toggle","next","previous"]}""", "\"action\""),
        spec("location_get", tr(R.string.act_location_desc), ""),
        spec("place_save", tr(R.string.act_place_save_desc),
            """"name":{"type":"string"}""", "\"name\""),
        spec("place_list", tr(R.string.act_places_desc), ""),
        spec("place_delete", tr(R.string.act_place_forget_desc), """"name":{"type":"string"}""", "\"name\""),
        spec("note_add", tr(R.string.act_note_save_desc), """"text":{"type":"string"}""", "\"text\""),
        spec("note_find", tr(R.string.act_note_find_desc), """"query":{"type":"string"}"""),
        spec("note_delete", tr(R.string.act_note_forget_desc), """"query":{"type":"string","description":"Слова из заметки"}""", "\"query\""),
        spec("app_open", tr(R.string.act_app_open_desc), """"name":{"type":"string"}""", "\"name\""),
        spec("settings_open", tr(R.string.act_settings_open_desc),
            """"screen":{"type":"string","enum":[${Phone.settingsScreens.keys.joinToString(",") { "\"$it\"" }}]}""", "\"screen\""),
        spec("call_dial", tr(R.string.act_call_desc),
            """"who":{"type":"string","description":"Имя контакта или номер"}""", "\"who\""),
        spec("message_draft", tr(R.string.act_message_desc),
            """"app":{"type":"string","enum":["sms","whatsapp","telegram"]},"to":{"type":"string","description":"Имя контакта или номер; для Telegram не нужен"},"text":{"type":"string"}""",
            "\"app\",\"text\""),
    )

    private fun res(ok: Boolean, b: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("ok", ok); b() }.toString()
    private fun err(code: String, message: String) = res(false) { put("error", code); put("message", message) }

    private fun act(icon: String, title: String, sub: String, button: String? = null, action: String? = null, state: String = "done",
                    at: Long? = null, countdown: Boolean = false, undo: String? = null, undoAction: String? = null) =
        report(FeedItem.Act(0, icon, title, sub, button, action, state, at, countdown, undo, undoAction))

    override suspend fun call(name: String, argumentsJson: String): String {
        val a = runCatching { Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull() ?: return err("bad_arguments", tr(R.string.act_bad_args))
        val t = Registry.byFn(name) ?: return err("unknown_tool", tr(R.string.act_no_such_tool))
        val s = settings()
        val policy = Registry.policy(s, t.id)
        if (policy == Policy.OFF) {
            return err("disabled_by_user", tr(R.string.act_policy_off_long, t.title))
        }
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val lockOk = !locked || when (s.lockScope) { LockScope.OFF -> false; LockScope.SAFE -> t.safe; LockScope.ON -> t.perms.isEmpty() && t.id != "note.remember" }
        if (!lockOk) {
            return err("locked", tr(R.string.act_locked_long))
        }
        val missingRuntime = Registry.runtimeMissing(context, t)
        if (missingRuntime.isNotEmpty()) {
            val titles = missingRuntime.map { Registry.permTitle(it) }.distinct().joinToString(", ")
            act("🔒", tr(R.string.act_no_access, titles), tr(R.string.act_needed_for, t.title), tr(R.string.act_allow), "perm:" + missingRuntime.joinToString(","), state = "fail")
            return err("no_permission", tr(R.string.act_no_access_long, titles))
        }
        return runCatching { run(name, a, policy) }.getOrElse { e -> err("failed", tr(R.string.act_failed, e.message ?: e.javaClass.simpleName)) }
    }

    private suspend fun ask(policy: Policy, title: String, detail: String) = policy != Policy.ASK || confirm(title, detail)
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull
    private fun hhmm(s: String?): Pair<Int, Int>? {
        val m = Regex("^(\\d{1,2})[:.](\\d{2})$").find(s ?: return null) ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }
    private fun clock(sec: Int) = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
    private val declined get() = err("declined_by_user", tr(R.string.act_user_declined))

    private suspend fun run(fn: String, a: JsonObject, policy: Policy): String = when (fn) {
        "alarm_set" -> {
            val (h, m) = hhmm(a.str("time")) ?: return err("bad_time", tr(R.string.act_alarm_time_format))
            val label = a.str("label")?.take(40)
            val title = "%d:%02d".format(h, m)
            if (!ask(policy, tr(R.string.act_alarm_set_title, title), label ?: "")) declined
            else PhoneActions.setAlarm(context, h, m, label).fold(
                { val at = Phone.nextOccurrence(h, m)
                  act("⏰", tr(R.string.conv_alarm_set, title), listOfNotNull(label, tr(R.string.conv_on_phone)).joinToString(" · "), tr(R.string.conv_open_alarms), "alarms", at = at)
                  res(true) { put("rings_in", Phone.inText(at - System.currentTimeMillis())) } },
                { act("⏰", tr(R.string.conv_alarm_failed, title), tr(R.string.conv_clock_silent), state = "fail"); err("clock_unavailable", tr(R.string.conv_clock_silent)) })
        }
        "alarm_dismiss" -> {
            val hm = hhmm(a.str("time")); val label = a.str("label")
            val what = hm?.let { "%d:%02d".format(it.first, it.second) } ?: label ?: tr(R.string.act_alarm_nearest)
            if (!ask(policy, tr(R.string.act_alarm_off_title, what), tr(R.string.act_alarm_off_sub))) declined
            else Phone.dismissAlarm(context, hm?.first, hm?.second, label).fold(
                { act("⏰", tr(R.string.act_alarm_off_done, what), tr(R.string.act_alarm_off_done_sub), tr(R.string.conv_open_alarms), "alarms",
                      state = "info", undo = hm?.let { tr(R.string.act_undo) }, undoAction = hm?.let { "alarm:set:${it.first}:${it.second}" })
                  res(true) { put("uncertain", true); put("note", tr(R.string.act_alarm_off_result)) } },
                { act("⏰", tr(R.string.act_alarm_off_failed, what), tr(R.string.act_alarm_off_unsupported), tr(R.string.conv_open_alarms), "alarms", state = "fail")
                  err("unsupported", tr(R.string.act_alarm_off_unsupported_long)) })
        }
        "timer_stop" -> {
            // Звенит — то, что ставили мы и чьё время вышло последние 15 минут. Идущий таймер Android остановить не даёт.
            val now = System.currentTimeMillis()
            val ringing = timers().any { it in (now - 15 * 60_000)..now }
            if (ringing) Phone.dismissTimer(context).fold(
                { act("⏱", tr(R.string.act_timer_off_done), tr(R.string.act_timer_off_done_sub)); res(true) },
                { Phone.showTimers(context); act("⏱", tr(R.string.act_timer_off_failed), tr(R.string.act_timer_off_failed_sub), tr(R.string.conv_open_timers), "timers", state = "fail")
                  err("clock_unavailable", tr(R.string.act_timer_off_failed_long)) })
            else {
                Phone.showTimers(context)
                act("⏱", tr(R.string.act_timer_running), tr(R.string.act_timer_running_sub), tr(R.string.conv_open_timers), "timers", state = "info")
                err("cannot_stop_running", tr(R.string.act_timer_running_long))
            }
        }
        "alarm_next" -> {
            val at = Phone.nextAlarm(context)
            if (at == null) { act("⏰", tr(R.string.act_alarm_none), tr(R.string.act_alarm_none_sub), tr(R.string.conv_open_alarms), "alarms", state = "info"); res(true) { put("next", tr(R.string.set_none)) } }
            else { act("⏰", tr(R.string.act_alarm_next_title, Phone.hm(at)), java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale("ru")).format(java.util.Date(at)), tr(R.string.conv_open_alarms), "alarms", state = "info", at = at)
                   res(true) { put("next", Phone.iso(at)); put("in", Phone.inText(at - System.currentTimeMillis())) } }
        }
        "timer_set" -> {
            val sec = a.int("seconds")?.takeIf { it in 1..86400 } ?: return err("bad_seconds", tr(R.string.act_timer_duration_format))
            val label = a.str("label")?.take(40)
            if (!ask(policy, tr(R.string.act_timer_set_title, clock(sec)), label ?: "")) declined
            else PhoneActions.setTimer(context, sec, label).fold(
                { act("⏱", tr(R.string.conv_timer_set, clock(sec)), listOfNotNull(label, tr(R.string.conv_on_phone)).joinToString(" · "), tr(R.string.conv_open_timers), "timers",
                      at = System.currentTimeMillis() + sec * 1000L, countdown = true); res(true) },
                { act("⏱", tr(R.string.conv_timer_failed, clock(sec)), tr(R.string.conv_clock_silent), state = "fail"); err("clock_unavailable", tr(R.string.conv_clock_silent)) })
        }
        "calendar_query" -> {
            val (from, _) = Phone.parseIso(a.str("from") ?: "") ?: return err("bad_date", tr(R.string.act_cal_date_format))
            val days = (a.int("days") ?: 1).coerceIn(1, 31)
            val list = Phone.io { Phone.events(context, from, from + days * 86_400_000L) }
            val n = list.size
            act("📅", tr(R.string.act_cal_title, if (n == 0) tr(R.string.act_cal_none) else trp(R.plurals.act_cal_events, n)),
                if (days == 1) java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale("ru")).format(java.util.Date(from)) else tr(R.string.act_cal_days_from, days, Phone.dayHm(from).substringBeforeLast(',')), state = "info")
            buildJsonObject {
                put("ok", true)
                put("events", buildJsonArray {
                    list.forEach { e -> add(buildJsonObject {
                        put("title", e.title); put("start", if (e.allDay) tr(R.string.act_cal_all_day) else Phone.iso(e.begin)); put("end", Phone.iso(e.end))
                        e.location?.let { put("location", it) }; e.calendar?.let { put("calendar", it) }
                    }) }
                })
            }.toString()
        }
        "calendar_add" -> {
            val title = a.str("title") ?: return err("no_title", tr(R.string.act_cal_need_title))
            val (start, allDay) = Phone.parseIso(a.str("start") ?: "") ?: return err("bad_date", tr(R.string.act_cal_start_format))
            val end = if (allDay) start + 86_400_000L else start + (a.int("minutes") ?: 60).coerceIn(5, 1440) * 60_000L
            val when_ = if (allDay) Phone.dayHm(start).substringBeforeLast(',') + tr(R.string.act_cal_all_day_tail) else Phone.dayHm(start)
            if (!ask(policy, tr(R.string.act_cal_add_title, title), when_)) declined
            else Phone.io { Phone.addEvent(context, title, start, end, allDay, a.str("location")) }.fold(
                { id -> act("📅", tr(R.string.act_cal_add_done, title), when_, tr(R.string.ui_open), "cal:open:$id", undo = tr(R.string.act_delete), undoAction = "cal:del:$id"); res(true) { put("id", id) } },
                { e -> val why = if (e.message == "no_calendar") tr(R.string.act_cal_no_calendar) else tr(R.string.act_cal_rejected)
                       act("📅", tr(R.string.act_cal_add_failed, title), why, state = "fail"); err("calendar_failed", why.replaceFirstChar { it.uppercase() }) })
        }
        "calendar_delete" -> {
            val q = a.str("title")?.lowercase() ?: return err("no_title", tr(R.string.act_cal_del_which))
            val from = a.str("date")?.let { Phone.parseIso(it)?.first } ?: (System.currentTimeMillis() - 86_400_000L)
            val found = Phone.io { Phone.events(context, from, from + 60 * 86_400_000L) }.filter { e -> q.split(' ').filter { it.length > 2 }.any { e.title.lowercase().contains(it.take(5)) } }
            val e = found.firstOrNull() ?: return err("not_found", tr(R.string.act_cal_del_not_found, q))
            val when_ = if (e.allDay) Phone.dayHm(e.begin).substringBeforeLast(',') + tr(R.string.act_cal_all_day_tail) else Phone.dayHm(e.begin)
            if (!ask(Policy.ASK, tr(R.string.act_cal_del_title, e.title), when_)) declined
            else Phone.deleteEvent(context, e.id).fold(
                { act("📅", tr(R.string.act_cal_del_done, e.title), when_); res(true) { if (found.size > 1) put("note", tr(R.string.act_cal_del_ambiguous, found.size)) } },
                { err("calendar_failed", tr(R.string.act_cal_del_failed)) })
        }
        "torch_set" -> {
            val on = a.bool("on") ?: true
            Phone.torch(context, on).fold(
                { act("🔦", if (on) tr(R.string.conv_torch_on) else tr(R.string.conv_torch_off), tr(R.string.conv_on_phone), undo = if (on) tr(R.string.conv_turn_off) else null, undoAction = if (on) "torch:off" else null); res(true) },
                { err("no_flash", tr(R.string.act_torch_failed)) })
        }
        "volume_set" -> {
            val stream = a.str("stream") ?: "media"
            Phone.volume(context, stream, a.int("percent"), a.int("step")).fold(
                { (was, now) -> act("🔊", tr(R.string.act_volume_title, Phone.streamTitles[stream] ?: "", now), if (was == now) tr(R.string.act_volume_same) else tr(R.string.act_volume_was, was),
                      undo = if (was == now) null else tr(R.string.act_undo), undoAction = if (was == now) null else "vol:$stream:$was")
                  res(true) { put("was", was); put("now", now) } },
                { err("volume_failed", tr(R.string.act_volume_unchanged)) })
        }
        "dnd_set" -> {
            val on = a.bool("on") ?: true
            if (!Phone.dndGranted(context)) {
                act("🌙", tr(R.string.act_dnd_no_access), tr(R.string.act_dnd_no_access_sub), tr(R.string.act_dnd_open_access), "dndaccess", state = "fail")
                err("no_access", tr(R.string.act_dnd_no_access_long))
            } else Phone.dnd(context, on).fold(
                { before -> act("🌙", if (on) tr(R.string.act_dnd_on) else tr(R.string.act_dnd_off), tr(R.string.conv_on_phone), undo = tr(R.string.act_cancel), undoAction = "dnd:$before"); res(true) },
                { err("dnd_failed", tr(R.string.act_dnd_failed)) })
        }
        "media_control" -> {
            val action = a.str("action") ?: "toggle"
            val word = mapOf("play" to tr(R.string.act_media_playing), "pause" to tr(R.string.act_media_paused), "next" to tr(R.string.act_media_next), "previous" to tr(R.string.act_media_prev)).getOrDefault(action, tr(R.string.act_media_toggled))
            val back = when (action) { "pause" -> tr(R.string.act_media_play) to "media:play"; "play" -> tr(R.string.act_media_pause) to "media:pause"; else -> null }
            Phone.media(context, action).fold({ act("🎵", tr(R.string.act_media_title, word), tr(R.string.act_media_sub), undo = back?.first, undoAction = back?.second); res(true) },
                { err("media_failed", tr(R.string.act_media_nothing)) })
        }
        "location_get" -> {
            if (!ask(policy, tr(R.string.act_location_title), tr(R.string.act_location_ask_sub))) declined
            else {
                val l = Phone.location(context) ?: return err("no_location", tr(R.string.act_location_failed))
                val addr = Phone.address(context, l)
                act("📍", tr(R.string.act_location_done, addr ?: "%.4f, %.4f".format(l.latitude, l.longitude)), tr(R.string.act_location_accuracy, l.accuracy.toInt()), tr(R.string.act_location_map), tr(R.string.act_location_map_action, l.latitude, l.longitude), state = "info")
                res(true) { put("lat", l.latitude); put("lon", l.longitude); put("accuracy_m", l.accuracy.toInt()); addr?.let { put("address", it) } }
            }
        }
        "place_save" -> {
            val nm = a.str("name") ?: return err("no_name", tr(R.string.act_place_need_name))
            val l = Phone.location(context) ?: return err("no_location", tr(R.string.act_location_failed))
            val addr = Phone.address(context, l)
            val id = System.currentTimeMillis()
            saveNote(Note(id, nm, id, place = true, lat = l.latitude, lon = l.longitude, address = addr))
            act("📍", tr(R.string.act_place_saved, nm), addr ?: "%.4f, %.4f".format(l.latitude, l.longitude), tr(R.string.act_location_map), "map:${l.latitude}:${l.longitude}:$nm", undo = tr(R.string.act_delete), undoAction = "note:del:$id")
            res(true)
        }
        "place_list" -> {
            val places = notes().filter { it.place }
            val here = if (places.isNotEmpty()) Phone.location(context) else null
            act("📍", tr(R.string.act_places_title, places.size), if (places.isEmpty()) tr(R.string.act_places_none) else places.take(3).joinToString(", ") { it.text }, state = "info")
            buildJsonObject {
                put("ok", true)
                put("places", buildJsonArray { places.takeLast(30).forEach { p -> add(buildJsonObject {
                    put("name", p.text); put("saved", Phone.iso(p.at)); p.address?.let { put("address", it) }
                    if (here != null && p.lat != null && p.lon != null) put("distance_m", Phone.distanceMeters(here.latitude, here.longitude, p.lat, p.lon).toInt())
                }) } })
            }.toString()
        }
        "note_add" -> {
            val text = a.str("text") ?: return err("no_text", tr(R.string.act_note_empty))
            val id = System.currentTimeMillis()
            saveNote(Note(id, text, id))
            act("📝", tr(R.string.act_note_saved), text.take(80), undo = tr(R.string.act_delete), undoAction = "note:del:$id"); res(true)
        }
        "note_find" -> {
            val q = a.str("query")?.lowercase()
            val found = notes().filter { !it.place && (q == null || q.split(' ').any { w -> w.length > 2 && it.text.lowercase().contains(w.take(5)) }) }.takeLast(20)
            act("📝", tr(R.string.act_notes_title, found.size), found.lastOrNull()?.text?.take(60) ?: tr(R.string.act_notes_none), state = "info")
            buildJsonObject { put("ok", true); put("notes", buildJsonArray { found.forEach { add(buildJsonObject { put("text", it.text); put("saved", Phone.iso(it.at)) }) } }) }.toString()
        }
        "note_delete", "place_delete" -> {
            val place = fn == "place_delete"
            val q = (a.str("query") ?: a.str("name"))?.lowercase() ?: return err("no_query", tr(R.string.act_forget_what))
            val n = notes().filter { it.place == place }.lastOrNull { nt -> q.split(' ').filter { it.length > 2 }.ifEmpty { listOf(q) }.any { nt.text.lowercase().contains(it.take(5)) } }
                ?: return err("not_found", if (place) tr(R.string.act_place_not_found) else tr(R.string.act_note_not_found))
            deleteNote(n.id)
            act(if (place) "📍" else "📝", if (place) tr(R.string.act_place_forgot, n.text) else tr(R.string.act_note_deleted), if (place) (n.address ?: "") else n.text.take(80))
            res(true)
        }
        "app_open" -> {
            val nm = a.str("name") ?: return err("no_name", tr(R.string.act_app_need_name))
            val app = Phone.findApp(context, nm) ?: return err("not_found", tr(R.string.act_app_not_found, nm))
            Phone.openApp(context, app.pkg).fold({ act("📱", tr(R.string.act_app_opened, app.label), tr(R.string.conv_on_phone)); res(true) { put("opened", app.label) } },
                { err("open_failed", tr(R.string.act_app_failed, app.label)) })
        }
        "settings_open" -> {
            val sc = a.str("screen") ?: "main"
            Phone.openSettings(context, sc).fold({ title -> act("⚙️", tr(R.string.act_settings_title, title), tr(R.string.act_settings_opened)); res(true) }, { err("unknown_screen", tr(R.string.act_settings_not_found)) })
        }
        "call_dial" -> {
            val who = a.str("who") ?: return err("no_who", tr(R.string.act_call_whom))
            val digits = who.filter { it.isDigit() || it == '+' }
            val (name, number) = if (digits.count(Char::isDigit) >= 3) who to digits
                else Phone.io { Phone.contacts(context, who) }.firstOrNull()?.let { it.name to it.number } ?: return err("not_found", tr(R.string.act_call_not_found, who))
            if (!ask(policy, tr(R.string.act_call_title, name), number)) declined
            else Phone.dial(context, number).fold({ act("📞", tr(R.string.act_call_done, name), tr(R.string.act_call_done_sub)); res(true) }, { err("dial_failed", tr(R.string.act_call_failed)) })
        }
        "message_draft" -> {
            val app = a.str("app") ?: "sms"
            val text = a.str("text") ?: return err("no_text", tr(R.string.act_message_need_text))
            val to = a.str("to")
            val contact = if (to == null || app == "telegram") null else {
                val digits = to.filter { it.isDigit() || it == '+' }
                if (digits.count(Char::isDigit) >= 3) to to digits else Phone.io { Phone.contacts(context, to) }.firstOrNull()?.let { it.name to it.number }
                    ?: return err("not_found", tr(R.string.act_call_not_found, to))
            }
            val appTitle = mapOf("sms" to "SMS", "whatsapp" to "WhatsApp", "telegram" to "Telegram")[app] ?: app
            if (!ask(Policy.ASK, tr(R.string.act_message_title, appTitle, contact?.let { " · ${it.first}" } ?: ""), text)) declined
            else Phone.draft(context, app, contact?.second, text).fold(
                { act("💬", tr(R.string.act_message_done, appTitle), (contact?.first?.let { "$it · " } ?: "") + tr(R.string.act_message_done_sub)); res(true) },
                { err("no_app", tr(R.string.act_message_failed, appTitle)) })
        }
        else -> err("unknown_tool", tr(R.string.act_no_such_tool))
    }

}
