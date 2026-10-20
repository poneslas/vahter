package ru.poneslas.vahter.actions

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import ru.poneslas.vahter.core.Policy
import ru.poneslas.vahter.core.Settings

/**
 * Реестр действий на телефоне (Архитектура 7; прототип — model.js, TOOLS): группа, название в настройках,
 * политика по умолчанию, можно ли на заблокированном экране, какие доступы Android нужны.
 * [fns] — имена функций для мозга, которые относятся к действию.
 */
data class Tool(
    val id: String,
    val title: String,
    val group: String,
    val def: Policy,
    val fns: List<String>,
    /** Безопасно на заблокированном экране при «Только будильник, таймер, фонарик». */
    val safe: Boolean = false,
    /** Доступы Android (runtime), без которых действие не работает. */
    val perms: List<String> = emptyList(),
    /** Особый доступ из системных настроек: «dnd» — «Не беспокоить». */
    val special: String? = null,
    /** Всегда спрашивать: «Разрешено» не предлагается (сообщения). */
    val alwaysAsk: Boolean = false,
)

object Registry {
    val groups = listOf(tr(R.string.reg_chasy), tr(R.string.reg_kalendar), tr(R.string.reg_telefon), tr(R.string.reg_prilozheniya_i_svyaz))

    val all = listOf(
        Tool("alarm.set", tr(R.string.reg_budilnik), tr(R.string.reg_chasy), Policy.AUTO, listOf("alarm_set"), safe = true),
        Tool("alarm.dismiss", tr(R.string.reg_vyklyuchenie_budilnika), tr(R.string.reg_chasy), Policy.AUTO, listOf("alarm_dismiss"), safe = true),
        Tool("alarm.next", tr(R.string.reg_blizhayshiy_budilnik), tr(R.string.reg_chasy), Policy.AUTO, listOf("alarm_next"), safe = true),
        Tool("timer.set", tr(R.string.reg_taymer), tr(R.string.reg_chasy), Policy.AUTO, listOf("timer_set"), safe = true),
        Tool("timer.stop", tr(R.string.reg_ostanovka_taymera), tr(R.string.reg_chasy), Policy.AUTO, listOf("timer_stop"), safe = true),
        Tool("calendar.query", tr(R.string.reg_prosmotr_kalendarya), tr(R.string.reg_kalendar), Policy.AUTO, listOf("calendar_query"), perms = listOf(Manifest.permission.READ_CALENDAR)),
        Tool("calendar.add", tr(R.string.reg_sozdanie_i_udalenie_sobytiy), tr(R.string.reg_kalendar), Policy.ASK, listOf("calendar_add", "calendar_delete"),
            perms = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
        Tool("torch.set", tr(R.string.reg_fonarik), tr(R.string.reg_telefon), Policy.AUTO, listOf("torch_set"), safe = true),
        Tool("volume.set", tr(R.string.reg_gromkost), tr(R.string.reg_telefon), Policy.AUTO, listOf("volume_set")),
        Tool("dnd.set", tr(R.string.ph_ne_bespokoit), tr(R.string.reg_telefon), Policy.AUTO, listOf("dnd_set"), special = "dnd"),
        Tool("media.control", tr(R.string.reg_upravlenie_muzykoy), tr(R.string.reg_telefon), Policy.AUTO, listOf("media_control")),
        Tool("location.get", tr(R.string.ph_mestopolozhenie), tr(R.string.reg_telefon), Policy.ASK, listOf("location_get"),
            perms = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)),
        Tool("place.save", tr(R.string.reg_sokhranennye_mesta), tr(R.string.reg_telefon), Policy.AUTO, listOf("place_save", "place_list", "place_delete"),
            perms = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)),
        Tool("note.remember", tr(R.string.reg_zametki), tr(R.string.reg_telefon), Policy.AUTO, listOf("note_add", "note_find", "note_delete")),
        Tool("app.open", tr(R.string.reg_zapusk_prilozheniy), tr(R.string.reg_prilozheniya_i_svyaz), Policy.AUTO, listOf("app_open")),
        Tool("settings.open", tr(R.string.reg_sistemnye_nastroyki), tr(R.string.reg_prilozheniya_i_svyaz), Policy.AUTO, listOf("settings_open")),
        Tool("call.dial", tr(R.string.reg_zvonki), tr(R.string.reg_prilozheniya_i_svyaz), Policy.ASK, listOf("call_dial"), perms = listOf(Manifest.permission.READ_CONTACTS)),
        Tool("message.draft", tr(R.string.reg_soobshcheniya), tr(R.string.reg_prilozheniya_i_svyaz), Policy.ASK, listOf("message_draft"),
            perms = listOf(Manifest.permission.READ_CONTACTS), alwaysAsk = true),
    )

    fun byId(id: String) = all.first { it.id == id }
    fun byFn(fn: String) = all.firstOrNull { fn in it.fns }

    fun policy(s: Settings, id: String): Policy {
        val t = byId(id)
        val p = s.policy[id] ?: t.def
        return if (t.alwaysAsk && p == Policy.AUTO) Policy.ASK else p
    }

    /** Названия доступов — как в системном окне Android. */
    fun permTitle(p: String) = when (p) {
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> tr(R.string.reg_kalendar)
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION -> tr(R.string.ph_mestopolozhenie)
        Manifest.permission.READ_CONTACTS -> tr(R.string.reg_kontakty)
        else -> p.substringAfterLast('.')
    }

    fun specialTitle(s: String) = when (s) { "dnd" -> tr(R.string.reg_ne_bespokoit); else -> s }

    /** Чего не хватает действию: пусто — всё выдано. */
    fun missing(ctx: Context, t: Tool): List<String> {
        val runtime = t.perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .map { permTitle(it) }.distinct()
        val special = when (t.special) {
            "dnd" -> if (ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) emptyList() else listOf(specialTitle("dnd"))
            else -> emptyList()
        }
        return runtime + special
    }

    /** Отказано так, что окно больше не появится — только через настройки (знаем по флагу, что уже спрашивали). */
    fun runtimeMissing(ctx: Context, t: Tool) = t.perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
