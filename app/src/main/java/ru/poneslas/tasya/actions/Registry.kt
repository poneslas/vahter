package ru.poneslas.tasya.actions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import ru.poneslas.tasya.core.Policy
import ru.poneslas.tasya.core.Settings

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
    val groups = listOf("Часы", "Календарь", "Телефон", "Приложения и связь")

    val all = listOf(
        Tool("alarm.set", "Будильник", "Часы", Policy.AUTO, listOf("alarm_set"), safe = true),
        Tool("alarm.dismiss", "Выключение будильника", "Часы", Policy.AUTO, listOf("alarm_dismiss"), safe = true),
        Tool("alarm.next", "Ближайший будильник", "Часы", Policy.AUTO, listOf("alarm_next"), safe = true),
        Tool("timer.set", "Таймер", "Часы", Policy.AUTO, listOf("timer_set"), safe = true),
        Tool("calendar.query", "Просмотр календаря", "Календарь", Policy.AUTO, listOf("calendar_query"), perms = listOf(Manifest.permission.READ_CALENDAR)),
        Tool("calendar.add", "Создание событий", "Календарь", Policy.ASK, listOf("calendar_add"),
            perms = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
        Tool("torch.set", "Фонарик", "Телефон", Policy.AUTO, listOf("torch_set"), safe = true),
        Tool("volume.set", "Громкость", "Телефон", Policy.AUTO, listOf("volume_set")),
        Tool("dnd.set", "Не беспокоить", "Телефон", Policy.AUTO, listOf("dnd_set"), special = "dnd"),
        Tool("media.control", "Управление музыкой", "Телефон", Policy.AUTO, listOf("media_control")),
        Tool("location.get", "Местоположение", "Телефон", Policy.ASK, listOf("location_get"),
            perms = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)),
        Tool("place.save", "Сохранённые места", "Телефон", Policy.AUTO, listOf("place_save", "place_list"),
            perms = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)),
        Tool("note.remember", "Заметки", "Телефон", Policy.AUTO, listOf("note_add", "note_find")),
        Tool("app.open", "Запуск приложений", "Приложения и связь", Policy.AUTO, listOf("app_open")),
        Tool("settings.open", "Системные настройки", "Приложения и связь", Policy.AUTO, listOf("settings_open")),
        Tool("call.dial", "Звонки", "Приложения и связь", Policy.ASK, listOf("call_dial"), perms = listOf(Manifest.permission.READ_CONTACTS)),
        Tool("message.draft", "Сообщения", "Приложения и связь", Policy.ASK, listOf("message_draft"),
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
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> "Календарь"
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION -> "Местоположение"
        Manifest.permission.READ_CONTACTS -> "Контакты"
        else -> p.substringAfterLast('.')
    }

    fun specialTitle(s: String) = when (s) { "dnd" -> "«Не беспокоить»"; else -> s }

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
