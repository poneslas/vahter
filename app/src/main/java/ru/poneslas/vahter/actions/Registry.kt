package ru.poneslas.vahter.actions

import ru.poneslas.vahter.ui.Ic
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
    /** Значок строки в «Действиях на телефоне» — как у строк настроек. */
    val icon: Int = Ic.shield,
    /** Что входит в действие — одной строкой («Поставить и выключить»). */
    val sub: String? = null,
)

object Registry {
    val groups = listOf(tr(R.string.reg_group_clock), tr(R.string.reg_group_calendar), tr(R.string.reg_group_phone), tr(R.string.reg_group_apps))

    val all = listOf(
        Tool("alarm.set", tr(R.string.reg_alarm_set), tr(R.string.reg_group_clock), Policy.AUTO, listOf("alarm_set", "alarm_dismiss", "alarm_next"), safe = true, icon = Ic.alarm, sub = tr(R.string.reg_alarm_sub)),
        Tool("timer.set", tr(R.string.reg_timer_set), tr(R.string.reg_group_clock), Policy.AUTO, listOf("timer_set", "timer_stop"), safe = true, icon = Ic.timer, sub = tr(R.string.reg_timer_sub)),
        Tool("calendar.query", tr(R.string.reg_cal_query), tr(R.string.reg_group_calendar), Policy.AUTO, listOf("calendar_query"), perms = listOf(Manifest.permission.READ_CALENDAR), icon = Ic.cal),
        Tool("calendar.add", tr(R.string.reg_cal_edit), tr(R.string.reg_group_calendar), Policy.ASK, listOf("calendar_add", "calendar_delete"),
            perms = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), icon = Ic.cal),
        Tool("torch.set", tr(R.string.reg_torch), tr(R.string.reg_group_phone), Policy.AUTO, listOf("torch_set"), safe = true, icon = Ic.torch),
        Tool("volume.set", tr(R.string.reg_volume), tr(R.string.reg_group_phone), Policy.AUTO, listOf("volume_set"), icon = Ic.vol),
        Tool("dnd.set", tr(R.string.phone_screen_dnd), tr(R.string.reg_group_phone), Policy.AUTO, listOf("dnd_set"), special = "dnd", icon = Ic.dnd),
        Tool("media.control", tr(R.string.reg_media), tr(R.string.reg_group_phone), Policy.AUTO, listOf("media_control"), icon = Ic.music, sub = tr(R.string.reg_media_sub)),
        Tool("location.get", tr(R.string.phone_screen_location), tr(R.string.reg_group_phone), Policy.ASK, listOf("location_get"),
            perms = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), icon = Ic.pin),
        Tool("place.save", tr(R.string.reg_places), tr(R.string.reg_group_phone), Policy.AUTO, listOf("place_save", "place_list", "place_delete"),
            perms = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), icon = Ic.pin),
        Tool("note.remember", tr(R.string.reg_notes), tr(R.string.reg_group_phone), Policy.AUTO, listOf("note_add", "note_find", "note_delete"), icon = Ic.note),
        Tool("app.open", tr(R.string.reg_app_open), tr(R.string.reg_group_apps), Policy.AUTO, listOf("app_open", "settings_open"), icon = Ic.apps, sub = tr(R.string.reg_app_sub)),
        Tool("call.dial", tr(R.string.reg_call), tr(R.string.reg_group_apps), Policy.ASK, listOf("call_dial"), perms = listOf(Manifest.permission.READ_CONTACTS), icon = Ic.phone),
        Tool("message.draft", tr(R.string.reg_message), tr(R.string.reg_group_apps), Policy.ASK, listOf("message_draft"),
            perms = listOf(Manifest.permission.READ_CONTACTS), alwaysAsk = true, icon = Ic.msg),
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
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> tr(R.string.reg_group_calendar)
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION -> tr(R.string.phone_screen_location)
        Manifest.permission.READ_CONTACTS -> tr(R.string.reg_contacts)
        else -> p.substringAfterLast('.')
    }

    fun specialTitle(s: String) = when (s) { "dnd" -> tr(R.string.reg_dnd); else -> s }

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

    /** Невыданные системные разрешения действия (без особых доступов) — карточка «Нет доступа» с кнопкой «Разрешить». */
    fun runtimeMissing(ctx: Context, t: Tool) = t.perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
