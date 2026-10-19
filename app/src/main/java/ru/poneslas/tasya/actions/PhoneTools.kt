package ru.poneslas.tasya.actions

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
import ru.poneslas.tasya.brain.ToolHost
import ru.poneslas.tasya.brain.ToolSpec
import ru.poneslas.tasya.core.FeedItem
import ru.poneslas.tasya.core.LockScope
import ru.poneslas.tasya.core.Mode
import ru.poneslas.tasya.core.Note
import ru.poneslas.tasya.core.Policy
import ru.poneslas.tasya.core.Settings

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
    private val journal: (tool: String, params: String, title: String, status: String) -> Unit,
    private val notes: () -> List<Note>,
    private val saveNote: (Note) -> Unit,
) : ToolHost {

    private fun spec(name: String, desc: String, props: String, required: String = "") = ToolSpec(name, desc,
        """{"type":"object","properties":{$props}${if (required.isNotEmpty()) ""","required":[$required]""" else ""}}""")

    override fun specs(mode: Mode): List<ToolSpec> = if (!mode.tools) emptyList() else listOf(
        spec("alarm_set", "Поставить будильник в «Часах».",
            """"time":{"type":"string","description":"ЧЧ:ММ, 24 часа"},"label":{"type":"string","description":"Подпись, до 40 символов"}""", "\"time\""),
        spec("alarm_dismiss", "Выключить ближайшее срабатывание будильника (по времени или подписи). Удалить будильник нельзя — только выключить ближайшее.",
            """"time":{"type":"string","description":"ЧЧ:ММ, если известно"},"label":{"type":"string"}"""),
        spec("alarm_next", "Узнать, когда прозвенит ближайший будильник на телефоне.", ""),
        spec("timer_set", "Запустить таймер в «Часах».",
            """"seconds":{"type":"integer","description":"1–86400"},"label":{"type":"string"}""", "\"seconds\""),
        spec("calendar_query", "События календаря телефона за период.",
            """"from":{"type":"string","description":"Дата начала ГГГГ-ММ-ДД"},"days":{"type":"integer","description":"Сколько дней, по умолчанию 1"}""", "\"from\""),
        spec("calendar_add", "Создать событие в календаре телефона.",
            """"title":{"type":"string"},"start":{"type":"string","description":"ГГГГ-ММ-ДДTЧЧ:ММ или ГГГГ-ММ-ДД для события на весь день"},"minutes":{"type":"integer","description":"Длительность, по умолчанию 60"},"location":{"type":"string"}""",
            "\"title\",\"start\""),
        spec("torch_set", "Включить или выключить фонарик.", """"on":{"type":"boolean"}""", "\"on\""),
        spec("volume_set", "Громкость: задать в процентах или прибавить/убавить шагами.",
            """"stream":{"type":"string","enum":["media","ring","alarm","notification"],"description":"media — музыка и видео"},"percent":{"type":"integer","description":"0–100"},"step":{"type":"integer","description":"+1 громче, −1 тише"}""",
            "\"stream\""),
        spec("dnd_set", "Включить или выключить режим «Не беспокоить».", """"on":{"type":"boolean"}""", "\"on\""),
        spec("media_control", "Управление музыкой и видео, которые сейчас играют.",
            """"action":{"type":"string","enum":["play","pause","toggle","next","previous"]}""", "\"action\""),
        spec("location_get", "Текущее местоположение телефона: координаты и адрес.", ""),
        spec("place_save", "Запомнить текущее место под названием («здесь припарковался»).",
            """"name":{"type":"string"}""", "\"name\""),
        spec("place_list", "Сохранённые места с расстоянием от текущего.", ""),
        spec("note_add", "Запомнить заметку на телефоне.", """"text":{"type":"string"}""", "\"text\""),
        spec("note_find", "Найти заметки (пустой запрос — последние).", """"query":{"type":"string"}"""),
        spec("app_open", "Открыть приложение по названию.", """"name":{"type":"string"}""", "\"name\""),
        spec("settings_open", "Открыть экран системных настроек.",
            """"screen":{"type":"string","enum":[${Phone.settingsScreens.keys.joinToString(",") { "\"$it\"" }}]}""", "\"screen\""),
        spec("call_dial", "Открыть набор номера (звонит человек сам). Имя ищется в «Контактах».",
            """"who":{"type":"string","description":"Имя контакта или номер"}""", "\"who\""),
        spec("message_draft", "Подготовить сообщение — отправляет человек сам.",
            """"app":{"type":"string","enum":["sms","whatsapp","telegram"]},"to":{"type":"string","description":"Имя контакта или номер; для Telegram не нужен"},"text":{"type":"string"}""",
            "\"app\",\"text\""),
    )

    private fun res(ok: Boolean, b: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("ok", ok); b() }.toString()
    private fun err(code: String, message: String) = res(false) { put("error", code); put("message", message) }

    private fun act(icon: String, title: String, sub: String, button: String? = null, action: String? = null, state: String = "done",
                    at: Long? = null, countdown: Boolean = false, undo: String? = null, undoAction: String? = null) =
        report(FeedItem.Act(0, icon, title, sub, button, action, state, at, countdown, undo, undoAction))

    override suspend fun call(name: String, argumentsJson: String): String {
        val a = runCatching { Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull() ?: return err("bad_arguments", "Не разобрал аргументы")
        val t = Registry.byFn(name) ?: return err("unknown_tool", "Такой функции нет")
        val s = settings()
        val policy = Registry.policy(s, t.id)
        if (policy == Policy.OFF) {
            journal(t.id, argumentsJson, t.title, "запрещено настройками")
            return err("disabled_by_user", "Действие «${t.title}» запрещено в настройках приложения (Действия на телефоне).")
        }
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val lockOk = !locked || when (s.lockScope) { LockScope.OFF -> false; LockScope.SAFE -> t.safe; LockScope.ON -> t.perms.isEmpty() && t.id != "note.remember" }
        if (!lockOk) {
            journal(t.id, argumentsJson, t.title, "экран заблокирован")
            return err("locked", "Телефон заблокирован — для этого действия нужна разблокировка.")
        }
        val missingRuntime = Registry.runtimeMissing(context, t)
        if (missingRuntime.isNotEmpty()) {
            val titles = missingRuntime.map { Registry.permTitle(it) }.distinct().joinToString(", ")
            act("🔒", "Нет доступа: $titles", "нужен для «${t.title}»", "Разрешить", "perm:" + missingRuntime.joinToString(","), state = "fail")
            journal(t.id, argumentsJson, t.title, "нет доступа: $titles")
            return err("no_permission", "Нет доступа Android «$titles». В разговоре появилась строка с кнопкой «Разрешить» — попроси нажать её и повторить.")
        }
        val r = runCatching { run(name, a, policy) }.getOrElse { e -> err("failed", "Не получилось: ${e.message ?: e.javaClass.simpleName}") }
        journal(t.id, argumentsJson, t.title, if (r.contains("\"ok\":true")) "выполнено" else Regex("\"message\":\"([^\"]*)").find(r)?.groupValues?.get(1) ?: "не выполнено")
        return r
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
    private val declined get() = err("declined_by_user", "Пользователь отказался.")

    private suspend fun run(fn: String, a: JsonObject, policy: Policy): String = when (fn) {
        "alarm_set" -> {
            val (h, m) = hhmm(a.str("time")) ?: return err("bad_time", "Время нужно в виде ЧЧ:ММ")
            val label = a.str("label")?.take(40)
            val title = "%d:%02d".format(h, m)
            if (!ask(policy, "Поставить будильник · $title", label ?: "")) declined
            else PhoneActions.setAlarm(context, h, m, label).fold(
                { val at = Phone.nextOccurrence(h, m)
                  act("⏰", "Будильник поставлен · $title", listOfNotNull(label, "на телефоне").joinToString(" · "), "Открыть будильники", "alarms", at = at)
                  res(true) { put("rings_in", Phone.inText(at - System.currentTimeMillis())) } },
                { act("⏰", "Будильник не поставлен · $title", "«Часы» не ответили", state = "fail"); err("clock_unavailable", "«Часы» не ответили") })
        }
        "alarm_dismiss" -> {
            val hm = hhmm(a.str("time")); val label = a.str("label")
            val what = hm?.let { "%d:%02d".format(it.first, it.second) } ?: label ?: "ближайший"
            if (!ask(policy, "Выключить будильник · $what", "Ближайшее срабатывание")) declined
            else Phone.dismissAlarm(context, hm?.first, hm?.second, label).fold(
                { act("⏰", "Будильник выключен · $what", "ближайшее срабатывание", "Открыть будильники", "alarms")
                  res(true) { put("note", "Выключено ближайшее срабатывание; сам будильник в «Часах» остался") } },
                { act("⏰", "Будильник не выключен · $what", "«Часы» не умеют выключать по просьбе", "Открыть будильники", "alarms", state = "fail")
                  err("unsupported", "«Часы» на этом телефоне не выключают будильник по просьбе других приложений") })
        }
        "alarm_next" -> {
            val at = Phone.nextAlarm(context)
            if (at == null) { act("⏰", "Будильников нет", "ни одного включённого", "Открыть будильники", "alarms", state = "info"); res(true) { put("next", "нет") } }
            else { act("⏰", "Ближайший будильник · ${Phone.hm(at)}", java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale("ru")).format(java.util.Date(at)), "Открыть будильники", "alarms", state = "info", at = at)
                   res(true) { put("next", Phone.iso(at)); put("in", Phone.inText(at - System.currentTimeMillis())) } }
        }
        "timer_set" -> {
            val sec = a.int("seconds")?.takeIf { it in 1..86400 } ?: return err("bad_seconds", "Длительность 1–86400 секунд")
            val label = a.str("label")?.take(40)
            if (!ask(policy, "Запустить таймер · ${clock(sec)}", label ?: "")) declined
            else PhoneActions.setTimer(context, sec, label).fold(
                { act("⏱", "Таймер запущен · ${clock(sec)}", listOfNotNull(label, "на телефоне").joinToString(" · "), "Открыть таймеры", "timers",
                      at = System.currentTimeMillis() + sec * 1000L, countdown = true); res(true) },
                { act("⏱", "Таймер не запущен · ${clock(sec)}", "«Часы» не ответили", state = "fail"); err("clock_unavailable", "«Часы» не ответили") })
        }
        "calendar_query" -> {
            val (from, _) = Phone.parseIso(a.str("from") ?: "") ?: return err("bad_date", "Дата нужна в виде ГГГГ-ММ-ДД")
            val days = (a.int("days") ?: 1).coerceIn(1, 31)
            val list = Phone.io { Phone.events(context, from, from + days * 86_400_000L) }
            val n = list.size
            act("📅", "Календарь · ${if (n == 0) "событий нет" else "$n " + plural(n, "событие", "события", "событий")}",
                if (days == 1) java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale("ru")).format(java.util.Date(from)) else "$days дн. с ${Phone.dayHm(from).substringBeforeLast(',')}", state = "info")
            buildJsonObject {
                put("ok", true)
                put("events", buildJsonArray {
                    list.forEach { e -> add(buildJsonObject {
                        put("title", e.title); put("start", if (e.allDay) "весь день" else Phone.iso(e.begin)); put("end", Phone.iso(e.end))
                        e.location?.let { put("location", it) }; e.calendar?.let { put("calendar", it) }
                    }) }
                })
            }.toString()
        }
        "calendar_add" -> {
            val title = a.str("title") ?: return err("no_title", "Нужно название события")
            val (start, allDay) = Phone.parseIso(a.str("start") ?: "") ?: return err("bad_date", "Начало нужно в виде ГГГГ-ММ-ДДTЧЧ:ММ")
            val end = if (allDay) start + 86_400_000L else start + (a.int("minutes") ?: 60).coerceIn(5, 1440) * 60_000L
            val when_ = if (allDay) Phone.dayHm(start).substringBeforeLast(',') + " · весь день" else Phone.dayHm(start)
            if (!ask(policy, "Создать событие · $title", when_)) declined
            else Phone.io { Phone.addEvent(context, title, start, end, allDay, a.str("location")) }.fold(
                { id -> act("📅", "Событие создано · $title", when_, "Открыть", "cal:open:$id", undo = "Удалить", undoAction = "cal:del:$id"); res(true) { put("id", id) } },
                { e -> val why = if (e.message == "no_calendar") "на телефоне нет календаря, куда можно записывать" else "календарь не принял событие"
                       act("📅", "Событие не создано · $title", why, state = "fail"); err("calendar_failed", why.replaceFirstChar { it.uppercase() }) })
        }
        "torch_set" -> {
            val on = a.bool("on") ?: true
            Phone.torch(context, on).fold(
                { act("🔦", if (on) "Фонарик включён" else "Фонарик выключен", "на телефоне", undo = if (on) "Выключить" else null, undoAction = if (on) "torch:off" else null); res(true) },
                { err("no_flash", "Фонарик недоступен — камера занята или вспышки нет") })
        }
        "volume_set" -> {
            val stream = a.str("stream") ?: "media"
            Phone.volume(context, stream, a.int("percent"), a.int("step")).fold(
                { (was, now) -> act("🔊", "Громкость ${Phone.streamTitles[stream] ?: ""} · $now %", if (was == now) "уже было так" else "было $was %",
                      undo = if (was == now) null else "Вернуть", undoAction = if (was == now) null else "vol:$stream:$was")
                  res(true) { put("was", was); put("now", now) } },
                { err("volume_failed", "Громкость не изменилась — возможно, включено «Не беспокоить»") })
        }
        "dnd_set" -> {
            val on = a.bool("on") ?: true
            if (!Phone.dndGranted(context)) {
                act("🌙", "Нет доступа к «Не беспокоить»", "включите «Тася» в списке — один раз", "Открыть доступ", "dndaccess", state = "fail")
                err("no_access", "Нужен доступ к «Не беспокоить»: в разговоре появилась кнопка «Открыть доступ» — попроси включить «Тася» в списке.")
            } else Phone.dnd(context, on).fold(
                { before -> act("🌙", if (on) "Не беспокоить · включено" else "Не беспокоить · выключено", "на телефоне", undo = "Отменить", undoAction = "dnd:$before"); res(true) },
                { err("dnd_failed", "Режим не переключился") })
        }
        "media_control" -> {
            val action = a.str("action") ?: "toggle"
            val word = mapOf("play" to "играет", "pause" to "пауза", "next" to "следующий трек", "previous" to "предыдущий трек").getOrDefault(action, "пауза / играет")
            Phone.media(context, action).fold({ act("🎵", "Музыка · $word", "в приложении, которое играет"); res(true) }, { err("media_failed", "Нечем управлять — ничего не играет") })
        }
        "location_get" -> {
            if (!ask(policy, "Узнать местоположение", "Координаты уйдут модели")) declined
            else {
                val l = Phone.location(context) ?: return err("no_location", "Местоположение недоступно — включите геолокацию")
                val addr = Phone.address(context, l)
                act("📍", "Местоположение · ${addr ?: "%.4f, %.4f".format(l.latitude, l.longitude)}", "точность ~${l.accuracy.toInt()} м", "Карта", "map:${l.latitude}:${l.longitude}:Здесь", state = "info")
                res(true) { put("lat", l.latitude); put("lon", l.longitude); put("accuracy_m", l.accuracy.toInt()); addr?.let { put("address", it) } }
            }
        }
        "place_save" -> {
            val nm = a.str("name") ?: return err("no_name", "Нужно название места")
            val l = Phone.location(context) ?: return err("no_location", "Местоположение недоступно — включите геолокацию")
            val addr = Phone.address(context, l)
            saveNote(Note(System.currentTimeMillis(), nm, System.currentTimeMillis(), place = true, lat = l.latitude, lon = l.longitude, address = addr))
            act("📍", "Место сохранено · $nm", addr ?: "%.4f, %.4f".format(l.latitude, l.longitude), "Карта", "map:${l.latitude}:${l.longitude}:$nm")
            res(true)
        }
        "place_list" -> {
            val places = notes().filter { it.place }
            val here = if (places.isNotEmpty()) Phone.location(context) else null
            act("📍", "Сохранённые места · ${places.size}", if (places.isEmpty()) "пока ни одного" else places.take(3).joinToString(", ") { it.text }, state = "info")
            buildJsonObject {
                put("ok", true)
                put("places", buildJsonArray { places.takeLast(30).forEach { p -> add(buildJsonObject {
                    put("name", p.text); put("saved", Phone.iso(p.at)); p.address?.let { put("address", it) }
                    if (here != null && p.lat != null && p.lon != null) put("distance_m", Phone.distanceMeters(here.latitude, here.longitude, p.lat, p.lon).toInt())
                }) } })
            }.toString()
        }
        "note_add" -> {
            val text = a.str("text") ?: return err("no_text", "Пустая заметка")
            saveNote(Note(System.currentTimeMillis(), text, System.currentTimeMillis()))
            act("📝", "Запомнил", text.take(80)); res(true)
        }
        "note_find" -> {
            val q = a.str("query")?.lowercase()
            val found = notes().filter { !it.place && (q == null || q.split(' ').any { w -> w.length > 2 && it.text.lowercase().contains(w.take(5)) }) }.takeLast(20)
            act("📝", "Заметки · ${found.size}", found.lastOrNull()?.text?.take(60) ?: "ничего не нашлось", state = "info")
            buildJsonObject { put("ok", true); put("notes", buildJsonArray { found.forEach { add(buildJsonObject { put("text", it.text); put("saved", Phone.iso(it.at)) }) } }) }.toString()
        }
        "app_open" -> {
            val nm = a.str("name") ?: return err("no_name", "Нужно название приложения")
            val app = Phone.findApp(context, nm) ?: return err("not_found", "Приложение «$nm» не найдено на телефоне")
            Phone.openApp(context, app.pkg).fold({ act("📱", "Открыто · ${app.label}", "на телефоне"); res(true) { put("opened", app.label) } },
                { err("open_failed", "«${app.label}» не открылось") })
        }
        "settings_open" -> {
            val sc = a.str("screen") ?: "main"
            Phone.openSettings(context, sc).fold({ title -> act("⚙️", "Настройки · $title", "открыты"); res(true) }, { err("unknown_screen", "Такого экрана настроек нет") })
        }
        "call_dial" -> {
            val who = a.str("who") ?: return err("no_who", "Кому звонить?")
            val digits = who.filter { it.isDigit() || it == '+' }
            val (name, number) = if (digits.count(Char::isDigit) >= 3) who to digits
                else Phone.io { Phone.contacts(context, who) }.firstOrNull()?.let { it.name to it.number } ?: return err("not_found", "В «Контактах» нет «$who»")
            if (!ask(policy, "Позвонить · $name", number)) declined
            else Phone.dial(context, number).fold({ act("📞", "Номер набран · $name", "звоните вы"); res(true) }, { err("dial_failed", "Звонилка не открылась") })
        }
        "message_draft" -> {
            val app = a.str("app") ?: "sms"
            val text = a.str("text") ?: return err("no_text", "Нужен текст сообщения")
            val to = a.str("to")
            val contact = if (to == null || app == "telegram") null else {
                val digits = to.filter { it.isDigit() || it == '+' }
                if (digits.count(Char::isDigit) >= 3) to to digits else Phone.io { Phone.contacts(context, to) }.firstOrNull()?.let { it.name to it.number }
                    ?: return err("not_found", "В «Контактах» нет «$to»")
            }
            val appTitle = mapOf("sms" to "SMS", "whatsapp" to "WhatsApp", "telegram" to "Telegram")[app] ?: app
            if (!ask(Policy.ASK, "Подготовить сообщение · $appTitle${contact?.let { " · ${it.first}" } ?: ""}", text)) declined
            else Phone.draft(context, app, contact?.second, text).fold(
                { act("💬", "Сообщение подготовлено · $appTitle", (contact?.first?.let { "$it · " } ?: "") + "отправляете вы"); res(true) },
                { err("no_app", "$appTitle не открылся — возможно, он не установлен") })
        }
        else -> err("unknown_tool", "Такой функции нет")
    }

    private fun plural(n: Int, one: String, few: String, many: String) =
        if (n % 10 == 1 && n % 100 != 11) one else if (n % 10 in 2..4 && n % 100 !in 12..14) few else many
}
