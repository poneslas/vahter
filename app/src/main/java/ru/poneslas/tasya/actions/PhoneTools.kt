package ru.poneslas.tasya.actions

import ru.poneslas.tasya.R
import ru.poneslas.tasya.tr
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
    private val deleteNote: (Long) -> Unit,
    /** Когда должны прозвенеть таймеры, которые ставили мы (по строкам разговора). */
    private val timers: () -> List<Long>,
) : ToolHost {

    private fun spec(name: String, desc: String, props: String, required: String = "") = ToolSpec(name, desc,
        """{"type":"object","properties":{$props}${if (required.isNotEmpty()) ""","required":[$required]""" else ""}}""")

    override fun specs(mode: Mode): List<ToolSpec> = if (!mode.tools) emptyList() else listOf(
        spec("alarm_set", tr(R.string.act_postavit_budilnik_v_chasakh),
            """"time":{"type":"string","description":"ЧЧ:ММ, 24 часа"},"label":{"type":"string","description":"Подпись, до 40 символов"}""", "\"time\""),
        spec("alarm_dismiss", tr(R.string.act_tolko_dlya_budilnikov_ne_taymerov),
            """"time":{"type":"string","description":"ЧЧ:ММ — только если человек сам назвал время будильника"},"label":{"type":"string"}"""),
        spec("alarm_next", tr(R.string.act_uznat_kogda_prozvenit_blizhayshiy_budiln), ""),
        spec("timer_set", tr(R.string.act_zapustit_taymer_v_chasakh),
            """"seconds":{"type":"integer","description":"1–86400"},"label":{"type":"string"}""", "\"seconds\""),
        spec("timer_stop", tr(R.string.act_ostanovit_ili_vyklyuchit_taymer_vyklyuch), ""),
        spec("calendar_query", tr(R.string.act_sobytiya_kalendarya_telefona_za_period),
            """"from":{"type":"string","description":"Дата начала ГГГГ-ММ-ДД"},"days":{"type":"integer","description":"Сколько дней, по умолчанию 1"}""", "\"from\""),
        spec("calendar_add", tr(R.string.act_sozdat_sobytie_v_kalendare_telefona),
            """"title":{"type":"string"},"start":{"type":"string","description":"ГГГГ-ММ-ДДTЧЧ:ММ или ГГГГ-ММ-ДД для события на весь день"},"minutes":{"type":"integer","description":"Длительность, по умолчанию 60"},"location":{"type":"string"}""",
            "\"title\",\"start\""),
        spec("calendar_delete", tr(R.string.act_udalit_sobytie_kalendarya_po_nazvaniyu),
            """"title":{"type":"string","description":"Слова из названия"},"date":{"type":"string","description":"ГГГГ-ММ-ДД, если известно"}""", "\"title\""),
        spec("torch_set", tr(R.string.act_vklyuchit_ili_vyklyuchit_fonarik), """"on":{"type":"boolean"}""", "\"on\""),
        spec("volume_set", tr(R.string.act_gromkost_zadat_v_protsentakh_ili),
            """"stream":{"type":"string","enum":["media","ring","alarm","notification"],"description":"media — музыка и видео"},"percent":{"type":"integer","description":"0–100"},"step":{"type":"integer","description":"+1 громче, −1 тише"}""",
            "\"stream\""),
        spec("dnd_set", tr(R.string.act_vklyuchit_ili_vyklyuchit_rezhim_ne), """"on":{"type":"boolean"}""", "\"on\""),
        spec("media_control", tr(R.string.act_upravlenie_muzykoy_i_video_kotorye),
            """"action":{"type":"string","enum":["play","pause","toggle","next","previous"]}""", "\"action\""),
        spec("location_get", tr(R.string.act_tekushchee_mestopolozhenie_telefona_koor), ""),
        spec("place_save", tr(R.string.act_zapomnit_tekushchee_mesto_pod_nazvaniem),
            """"name":{"type":"string"}""", "\"name\""),
        spec("place_list", tr(R.string.act_sokhranennye_mesta_s_rasstoyaniem_ot), ""),
        spec("place_delete", tr(R.string.act_zabyt_sokhranennoe_mesto), """"name":{"type":"string"}""", "\"name\""),
        spec("note_add", tr(R.string.act_zapomnit_zametku_na_telefone), """"text":{"type":"string"}""", "\"text\""),
        spec("note_find", tr(R.string.act_nayti_zametki_pustoy_zapros_poslednie), """"query":{"type":"string"}"""),
        spec("note_delete", tr(R.string.act_zabyt_zametku_zabud_pro_parkovku), """"query":{"type":"string","description":"Слова из заметки"}""", "\"query\""),
        spec("app_open", tr(R.string.act_otkryt_prilozhenie_po_nazvaniyu), """"name":{"type":"string"}""", "\"name\""),
        spec("settings_open", tr(R.string.act_otkryt_ekran_sistemnykh_nastroek),
            """"screen":{"type":"string","enum":[${Phone.settingsScreens.keys.joinToString(",") { "\"$it\"" }}]}""", "\"screen\""),
        spec("call_dial", tr(R.string.act_otkryt_nabor_nomera_zvonit_chelovek),
            """"who":{"type":"string","description":"Имя контакта или номер"}""", "\"who\""),
        spec("message_draft", tr(R.string.act_podgotovit_soobshchenie_otpravlyaet_chel),
            """"app":{"type":"string","enum":["sms","whatsapp","telegram"]},"to":{"type":"string","description":"Имя контакта или номер; для Telegram не нужен"},"text":{"type":"string"}""",
            "\"app\",\"text\""),
    )

    private fun res(ok: Boolean, b: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("ok", ok); b() }.toString()
    private fun err(code: String, message: String) = res(false) { put("error", code); put("message", message) }

    private fun act(icon: String, title: String, sub: String, button: String? = null, action: String? = null, state: String = "done",
                    at: Long? = null, countdown: Boolean = false, undo: String? = null, undoAction: String? = null) =
        report(FeedItem.Act(0, icon, title, sub, button, action, state, at, countdown, undo, undoAction))

    override suspend fun call(name: String, argumentsJson: String): String {
        val a = runCatching { Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull() ?: return err("bad_arguments", tr(R.string.act_ne_razobral_argumenty))
        val t = Registry.byFn(name) ?: return err("unknown_tool", tr(R.string.act_takoy_funktsii_net))
        val s = settings()
        val policy = Registry.policy(s, t.id)
        if (policy == Policy.OFF) {
            journal(t.id, argumentsJson, t.title, tr(R.string.act_zapreshcheno_nastroykami))
            return err("disabled_by_user", tr(R.string.act_deystvie_1_s_zapreshcheno_v, t.title))
        }
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val lockOk = !locked || when (s.lockScope) { LockScope.OFF -> false; LockScope.SAFE -> t.safe; LockScope.ON -> t.perms.isEmpty() && t.id != "note.remember" }
        if (!lockOk) {
            journal(t.id, argumentsJson, t.title, tr(R.string.act_ekran_zablokirovan))
            return err("locked", tr(R.string.act_telefon_zablokirovan_dlya_etogo_deystviy))
        }
        val missingRuntime = Registry.runtimeMissing(context, t)
        if (missingRuntime.isNotEmpty()) {
            val titles = missingRuntime.map { Registry.permTitle(it) }.distinct().joinToString(", ")
            act("🔒", tr(R.string.act_net_dostupa_1_s, titles), tr(R.string.act_nuzhen_dlya_1_s, t.title), tr(R.string.act_razreshit), "perm:" + missingRuntime.joinToString(","), state = "fail")
            journal(t.id, argumentsJson, t.title, tr(R.string.set_net_dostupa_1_s_2, titles))
            return err("no_permission", tr(R.string.act_net_dostupa_android_1_s, titles))
        }
        val r = runCatching { run(name, a, policy) }.getOrElse { e -> err("failed", tr(R.string.act_ne_poluchilos_1_s, e.message ?: e.javaClass.simpleName)) }
        journal(t.id, argumentsJson, t.title, when {
            r.contains("\"uncertain\":true") -> tr(R.string.act_peredano_itog_neizvesten)
            r.contains("\"ok\":true") -> tr(R.string.conv_vypolneno)
            else -> Regex("\"message\":\"([^\"]*)").find(r)?.groupValues?.get(1)?.substringBefore(". ")?.substringBefore(" — ")?.take(80) ?: tr(R.string.conv_ne_vypolneno)
        })
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
    private val declined get() = err("declined_by_user", tr(R.string.act_polzovatel_otkazalsya))

    private suspend fun run(fn: String, a: JsonObject, policy: Policy): String = when (fn) {
        "alarm_set" -> {
            val (h, m) = hhmm(a.str("time")) ?: return err("bad_time", tr(R.string.act_vremya_nuzhno_v_vide_chch))
            val label = a.str("label")?.take(40)
            val title = "%d:%02d".format(h, m)
            if (!ask(policy, tr(R.string.act_postavit_budilnik_1_s, title), label ?: "")) declined
            else PhoneActions.setAlarm(context, h, m, label).fold(
                { val at = Phone.nextOccurrence(h, m)
                  act("⏰", tr(R.string.conv_budilnik_postavlen_1_s, title), listOfNotNull(label, tr(R.string.conv_na_telefone)).joinToString(" · "), tr(R.string.conv_otkryt_budilniki), "alarms", at = at)
                  res(true) { put("rings_in", Phone.inText(at - System.currentTimeMillis())) } },
                { act("⏰", tr(R.string.conv_budilnik_ne_postavlen_1_s, title), tr(R.string.conv_chasy_ne_otvetili), state = "fail"); err("clock_unavailable", tr(R.string.conv_chasy_ne_otvetili)) })
        }
        "alarm_dismiss" -> {
            val hm = hhmm(a.str("time")); val label = a.str("label")
            val what = hm?.let { "%d:%02d".format(it.first, it.second) } ?: label ?: tr(R.string.act_blizhayshiy)
            if (!ask(policy, tr(R.string.act_vyklyuchit_budilnik_1_s, what), tr(R.string.act_blizhayshee_srabatyvanie))) declined
            else Phone.dismissAlarm(context, hm?.first, hm?.second, label).fold(
                { act("⏰", tr(R.string.act_budilnik_1_s_poprosil_chasy, what), tr(R.string.act_esli_chasy_pokazali_spisok_vyklyuchite), tr(R.string.conv_otkryt_budilniki), "alarms",
                      state = "info", undo = hm?.let { tr(R.string.act_vernut) }, undoAction = hm?.let { "alarm:set:${it.first}:${it.second}" })
                  res(true) { put("uncertain", true); put("note", tr(R.string.act_prosba_peredana_chasam_vyklyuchen_li)) } },
                { act("⏰", tr(R.string.act_budilnik_ne_vyklyuchen_1_s, what), tr(R.string.act_chasy_ne_umeyut_vyklyuchat_po), tr(R.string.conv_otkryt_budilniki), "alarms", state = "fail")
                  err("unsupported", tr(R.string.act_chasy_na_etom_telefone_ne)) })
        }
        "timer_stop" -> {
            // Звенит — то, что ставили мы и чьё время вышло последние 15 минут. Идущий таймер Android остановить не даёт.
            val now = System.currentTimeMillis()
            val ringing = timers().any { it in (now - 15 * 60_000)..now }
            if (ringing) Phone.dismissTimer(context).fold(
                { act("⏱", tr(R.string.act_taymer_vyklyuchen), tr(R.string.act_zvenyashchiy_taymer_v_chasakh)); res(true) },
                { Phone.showTimers(context); act("⏱", tr(R.string.act_taymer_ne_vyklyuchen), tr(R.string.act_chasy_ne_otvetili_otkryl_taymery), tr(R.string.conv_otkryt_taymery), "timers", state = "fail")
                  err("clock_unavailable", tr(R.string.act_chasy_ne_vyklyuchili_taymer_otkryty)) })
            else {
                Phone.showTimers(context)
                act("⏱", tr(R.string.act_taymer_idet_ostanovite_v_chasakh), tr(R.string.act_android_ne_daet_ostanovit_ego), tr(R.string.conv_otkryt_taymery), "timers", state = "info")
                err("cannot_stop_running", tr(R.string.act_idushchiy_taymer_android_ne_daet))
            }
        }
        "alarm_next" -> {
            val at = Phone.nextAlarm(context)
            if (at == null) { act("⏰", tr(R.string.act_budilnikov_net), tr(R.string.act_ni_odnogo_vklyuchennogo), tr(R.string.conv_otkryt_budilniki), "alarms", state = "info"); res(true) { put("next", tr(R.string.set_net)) } }
            else { act("⏰", tr(R.string.act_blizhayshiy_budilnik_1_s, Phone.hm(at)), java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale("ru")).format(java.util.Date(at)), tr(R.string.conv_otkryt_budilniki), "alarms", state = "info", at = at)
                   res(true) { put("next", Phone.iso(at)); put("in", Phone.inText(at - System.currentTimeMillis())) } }
        }
        "timer_set" -> {
            val sec = a.int("seconds")?.takeIf { it in 1..86400 } ?: return err("bad_seconds", tr(R.string.act_dlitelnost_1_86400_sekund))
            val label = a.str("label")?.take(40)
            if (!ask(policy, tr(R.string.act_zapustit_taymer_1_s, clock(sec)), label ?: "")) declined
            else PhoneActions.setTimer(context, sec, label).fold(
                { act("⏱", tr(R.string.conv_taymer_zapushchen_1_s, clock(sec)), listOfNotNull(label, tr(R.string.conv_na_telefone)).joinToString(" · "), tr(R.string.conv_otkryt_taymery), "timers",
                      at = System.currentTimeMillis() + sec * 1000L, countdown = true); res(true) },
                { act("⏱", tr(R.string.conv_taymer_ne_zapushchen_1_s, clock(sec)), tr(R.string.conv_chasy_ne_otvetili), state = "fail"); err("clock_unavailable", tr(R.string.conv_chasy_ne_otvetili)) })
        }
        "calendar_query" -> {
            val (from, _) = Phone.parseIso(a.str("from") ?: "") ?: return err("bad_date", tr(R.string.act_data_nuzhna_v_vide_gggg))
            val days = (a.int("days") ?: 1).coerceIn(1, 31)
            val list = Phone.io { Phone.events(context, from, from + days * 86_400_000L) }
            val n = list.size
            act("📅", tr(R.string.act_kalendar_1_s, if (n == 0) tr(R.string.act_sobytiy_net) else "$n " + plural(n, tr(R.string.act_sobytie), tr(R.string.act_sobytiya), tr(R.string.act_sobytiy))),
                if (days == 1) java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale("ru")).format(java.util.Date(from)) else tr(R.string.act_1_s_dn_s_2, days, Phone.dayHm(from).substringBeforeLast(',')), state = "info")
            buildJsonObject {
                put("ok", true)
                put("events", buildJsonArray {
                    list.forEach { e -> add(buildJsonObject {
                        put("title", e.title); put("start", if (e.allDay) tr(R.string.act_ves_den) else Phone.iso(e.begin)); put("end", Phone.iso(e.end))
                        e.location?.let { put("location", it) }; e.calendar?.let { put("calendar", it) }
                    }) }
                })
            }.toString()
        }
        "calendar_add" -> {
            val title = a.str("title") ?: return err("no_title", tr(R.string.act_nuzhno_nazvanie_sobytiya))
            val (start, allDay) = Phone.parseIso(a.str("start") ?: "") ?: return err("bad_date", tr(R.string.act_nachalo_nuzhno_v_vide_gggg))
            val end = if (allDay) start + 86_400_000L else start + (a.int("minutes") ?: 60).coerceIn(5, 1440) * 60_000L
            val when_ = if (allDay) Phone.dayHm(start).substringBeforeLast(',') + tr(R.string.act_ves_den_2) else Phone.dayHm(start)
            if (!ask(policy, tr(R.string.act_sozdat_sobytie_1_s, title), when_)) declined
            else Phone.io { Phone.addEvent(context, title, start, end, allDay, a.str("location")) }.fold(
                { id -> act("📅", tr(R.string.act_sobytie_sozdano_1_s, title), when_, tr(R.string.pg_otkryt), "cal:open:$id", undo = tr(R.string.act_udalit), undoAction = "cal:del:$id"); res(true) { put("id", id) } },
                { e -> val why = if (e.message == "no_calendar") tr(R.string.act_na_telefone_net_kalendarya_kuda) else tr(R.string.act_kalendar_ne_prinyal_sobytie)
                       act("📅", tr(R.string.act_sobytie_ne_sozdano_1_s, title), why, state = "fail"); err("calendar_failed", why.replaceFirstChar { it.uppercase() }) })
        }
        "calendar_delete" -> {
            val q = a.str("title")?.lowercase() ?: return err("no_title", tr(R.string.act_kakoe_sobytie_udalit))
            val from = a.str("date")?.let { Phone.parseIso(it)?.first } ?: (System.currentTimeMillis() - 86_400_000L)
            val found = Phone.io { Phone.events(context, from, from + 60 * 86_400_000L) }.filter { e -> q.split(' ').filter { it.length > 2 }.any { e.title.lowercase().contains(it.take(5)) } }
            val e = found.firstOrNull() ?: return err("not_found", tr(R.string.act_sobytie_1_s_ne_naydeno, q))
            val when_ = if (e.allDay) Phone.dayHm(e.begin).substringBeforeLast(',') + tr(R.string.act_ves_den_2) else Phone.dayHm(e.begin)
            if (!ask(Policy.ASK, tr(R.string.act_udalit_sobytie_1_s, e.title), when_)) declined
            else Phone.deleteEvent(context, e.id).fold(
                { act("📅", tr(R.string.act_sobytie_udaleno_1_s, e.title), when_); res(true) { if (found.size > 1) put("note", tr(R.string.act_pokhozhikh_sobytiy_bylo_1_s, found.size)) } },
                { err("calendar_failed", tr(R.string.act_kalendar_ne_udalil_sobytie)) })
        }
        "torch_set" -> {
            val on = a.bool("on") ?: true
            Phone.torch(context, on).fold(
                { act("🔦", if (on) tr(R.string.conv_fonarik_vklyuchen) else tr(R.string.conv_fonarik_vyklyuchen), tr(R.string.conv_na_telefone), undo = if (on) tr(R.string.conv_vyklyuchit) else null, undoAction = if (on) "torch:off" else null); res(true) },
                { err("no_flash", tr(R.string.act_fonarik_nedostupen_kamera_zanyata_ili)) })
        }
        "volume_set" -> {
            val stream = a.str("stream") ?: "media"
            Phone.volume(context, stream, a.int("percent"), a.int("step")).fold(
                { (was, now) -> act("🔊", tr(R.string.act_gromkost_1_s_2_s, Phone.streamTitles[stream] ?: "", now), if (was == now) tr(R.string.act_uzhe_bylo_tak) else tr(R.string.act_bylo_1_s, was),
                      undo = if (was == now) null else tr(R.string.act_vernut), undoAction = if (was == now) null else "vol:$stream:$was")
                  res(true) { put("was", was); put("now", now) } },
                { err("volume_failed", tr(R.string.act_gromkost_ne_izmenilas_vozmozhno_vklyuche)) })
        }
        "dnd_set" -> {
            val on = a.bool("on") ?: true
            if (!Phone.dndGranted(context)) {
                act("🌙", tr(R.string.act_net_dostupa_k_ne_bespokoit), tr(R.string.act_vklyuchite_tasya_v_spiske_odin), tr(R.string.act_otkryt_dostup), "dndaccess", state = "fail")
                err("no_access", tr(R.string.act_nuzhen_dostup_k_ne_bespokoit))
            } else Phone.dnd(context, on).fold(
                { before -> act("🌙", if (on) tr(R.string.act_ne_bespokoit_vklyucheno) else tr(R.string.act_ne_bespokoit_vyklyucheno), tr(R.string.conv_na_telefone), undo = tr(R.string.act_otmenit), undoAction = "dnd:$before"); res(true) },
                { err("dnd_failed", tr(R.string.act_rezhim_ne_pereklyuchilsya)) })
        }
        "media_control" -> {
            val action = a.str("action") ?: "toggle"
            val word = mapOf("play" to tr(R.string.act_igraet), "pause" to tr(R.string.act_pauza), "next" to tr(R.string.act_sleduyushchiy_trek), "previous" to tr(R.string.act_predydushchiy_trek)).getOrDefault(action, tr(R.string.act_pauza_igraet))
            val back = when (action) { "pause" -> tr(R.string.act_igrat) to "media:play"; "play" -> tr(R.string.act_pauza_2) to "media:pause"; else -> null }
            Phone.media(context, action).fold({ act("🎵", tr(R.string.act_muzyka_1_s, word), tr(R.string.act_v_prilozhenii_kotoroe_igraet), undo = back?.first, undoAction = back?.second); res(true) },
                { err("media_failed", tr(R.string.act_nechem_upravlyat_nichego_ne_igraet)) })
        }
        "location_get" -> {
            if (!ask(policy, tr(R.string.act_uznat_mestopolozhenie), tr(R.string.act_koordinaty_uydut_modeli))) declined
            else {
                val l = Phone.location(context) ?: return err("no_location", tr(R.string.act_mestopolozhenie_nedostupno_vklyuchite_ge))
                val addr = Phone.address(context, l)
                act("📍", tr(R.string.act_mestopolozhenie_1_s, addr ?: "%.4f, %.4f".format(l.latitude, l.longitude)), tr(R.string.act_tochnost_1_s_m, l.accuracy.toInt()), tr(R.string.act_karta), tr(R.string.act_map_1_s_2_s, l.latitude, l.longitude), state = "info")
                res(true) { put("lat", l.latitude); put("lon", l.longitude); put("accuracy_m", l.accuracy.toInt()); addr?.let { put("address", it) } }
            }
        }
        "place_save" -> {
            val nm = a.str("name") ?: return err("no_name", tr(R.string.act_nuzhno_nazvanie_mesta))
            val l = Phone.location(context) ?: return err("no_location", tr(R.string.act_mestopolozhenie_nedostupno_vklyuchite_ge))
            val addr = Phone.address(context, l)
            val id = System.currentTimeMillis()
            saveNote(Note(id, nm, id, place = true, lat = l.latitude, lon = l.longitude, address = addr))
            act("📍", tr(R.string.act_mesto_sokhraneno_1_s, nm), addr ?: "%.4f, %.4f".format(l.latitude, l.longitude), tr(R.string.act_karta), "map:${l.latitude}:${l.longitude}:$nm", undo = tr(R.string.act_udalit), undoAction = "note:del:$id")
            res(true)
        }
        "place_list" -> {
            val places = notes().filter { it.place }
            val here = if (places.isNotEmpty()) Phone.location(context) else null
            act("📍", tr(R.string.act_sokhranennye_mesta_1_s, places.size), if (places.isEmpty()) tr(R.string.act_poka_ni_odnogo) else places.take(3).joinToString(", ") { it.text }, state = "info")
            buildJsonObject {
                put("ok", true)
                put("places", buildJsonArray { places.takeLast(30).forEach { p -> add(buildJsonObject {
                    put("name", p.text); put("saved", Phone.iso(p.at)); p.address?.let { put("address", it) }
                    if (here != null && p.lat != null && p.lon != null) put("distance_m", Phone.distanceMeters(here.latitude, here.longitude, p.lat, p.lon).toInt())
                }) } })
            }.toString()
        }
        "note_add" -> {
            val text = a.str("text") ?: return err("no_text", tr(R.string.act_pustaya_zametka))
            val id = System.currentTimeMillis()
            saveNote(Note(id, text, id))
            act("📝", tr(R.string.act_zametka_sokhranena), text.take(80), undo = tr(R.string.act_udalit), undoAction = "note:del:$id"); res(true)
        }
        "note_find" -> {
            val q = a.str("query")?.lowercase()
            val found = notes().filter { !it.place && (q == null || q.split(' ').any { w -> w.length > 2 && it.text.lowercase().contains(w.take(5)) }) }.takeLast(20)
            act("📝", tr(R.string.act_zametki_1_s, found.size), found.lastOrNull()?.text?.take(60) ?: tr(R.string.act_nichego_ne_nashlos), state = "info")
            buildJsonObject { put("ok", true); put("notes", buildJsonArray { found.forEach { add(buildJsonObject { put("text", it.text); put("saved", Phone.iso(it.at)) }) } }) }.toString()
        }
        "note_delete", "place_delete" -> {
            val place = fn == "place_delete"
            val q = (a.str("query") ?: a.str("name"))?.lowercase() ?: return err("no_query", tr(R.string.act_chto_zabyt))
            val n = notes().filter { it.place == place }.lastOrNull { nt -> q.split(' ').filter { it.length > 2 }.ifEmpty { listOf(q) }.any { nt.text.lowercase().contains(it.take(5)) } }
                ?: return err("not_found", if (place) tr(R.string.act_takogo_mesta_net) else tr(R.string.act_takoy_zametki_net))
            deleteNote(n.id)
            act(if (place) "📍" else "📝", if (place) tr(R.string.act_mesto_zabyto_1_s, n.text) else tr(R.string.act_zametka_udalena), if (place) (n.address ?: "") else n.text.take(80))
            res(true)
        }
        "app_open" -> {
            val nm = a.str("name") ?: return err("no_name", tr(R.string.act_nuzhno_nazvanie_prilozheniya))
            val app = Phone.findApp(context, nm) ?: return err("not_found", tr(R.string.act_prilozhenie_1_s_ne_naydeno, nm))
            Phone.openApp(context, app.pkg).fold({ act("📱", tr(R.string.act_otkryto_1_s, app.label), tr(R.string.conv_na_telefone)); res(true) { put("opened", app.label) } },
                { err("open_failed", tr(R.string.act_1_s_ne_otkrylos, app.label)) })
        }
        "settings_open" -> {
            val sc = a.str("screen") ?: "main"
            Phone.openSettings(context, sc).fold({ title -> act("⚙️", tr(R.string.act_nastroyki_1_s, title), tr(R.string.act_otkryty)); res(true) }, { err("unknown_screen", tr(R.string.act_takogo_ekrana_nastroek_net)) })
        }
        "call_dial" -> {
            val who = a.str("who") ?: return err("no_who", tr(R.string.act_komu_zvonit))
            val digits = who.filter { it.isDigit() || it == '+' }
            val (name, number) = if (digits.count(Char::isDigit) >= 3) who to digits
                else Phone.io { Phone.contacts(context, who) }.firstOrNull()?.let { it.name to it.number } ?: return err("not_found", tr(R.string.act_v_kontaktakh_net_1_s, who))
            if (!ask(policy, tr(R.string.act_pozvonit_1_s, name), number)) declined
            else Phone.dial(context, number).fold({ act("📞", tr(R.string.act_nomer_nabran_1_s, name), tr(R.string.act_zvonite_vy)); res(true) }, { err("dial_failed", tr(R.string.act_zvonilka_ne_otkrylas)) })
        }
        "message_draft" -> {
            val app = a.str("app") ?: "sms"
            val text = a.str("text") ?: return err("no_text", tr(R.string.act_nuzhen_tekst_soobshcheniya))
            val to = a.str("to")
            val contact = if (to == null || app == "telegram") null else {
                val digits = to.filter { it.isDigit() || it == '+' }
                if (digits.count(Char::isDigit) >= 3) to to digits else Phone.io { Phone.contacts(context, to) }.firstOrNull()?.let { it.name to it.number }
                    ?: return err("not_found", tr(R.string.act_v_kontaktakh_net_1_s, to))
            }
            val appTitle = mapOf("sms" to "SMS", "whatsapp" to "WhatsApp", "telegram" to "Telegram")[app] ?: app
            if (!ask(Policy.ASK, tr(R.string.act_podgotovit_soobshchenie_1_s_2, appTitle, contact?.let { " · ${it.first}" } ?: ""), text)) declined
            else Phone.draft(context, app, contact?.second, text).fold(
                { act("💬", tr(R.string.act_soobshchenie_podgotovleno_1_s, appTitle), (contact?.first?.let { "$it · " } ?: "") + tr(R.string.act_otpravlyaete_vy)); res(true) },
                { err("no_app", tr(R.string.act_1_s_ne_otkrylsya_vozmozhno, appTitle)) })
        }
        else -> err("unknown_tool", tr(R.string.act_takoy_funktsii_net))
    }

    private fun plural(n: Int, one: String, few: String, many: String) =
        if (n % 10 == 1 && n % 100 != 11) one else if (n % 10 in 2..4 && n % 100 !in 12..14) few else many
}
