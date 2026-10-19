package ru.poneslas.tasya.actions

import android.app.KeyguardManager
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.poneslas.tasya.brain.ToolHost
import ru.poneslas.tasya.brain.ToolSpec
import ru.poneslas.tasya.core.LockScope
import ru.poneslas.tasya.core.Mode
import ru.poneslas.tasya.core.Policy
import ru.poneslas.tasya.core.Settings

/**
 * Действия телефона для мозга (Архитектура 7, Б.1–Б.2): описание функции → проверка разрешения
 * («Разрешено» / «Спрашивать» / «Не разрешено») и экрана блокировки → исполнение → строка итога.
 * Мозгу всегда возвращается понятный результат, а не тишина.
 */
class PhoneTools(
    private val context: Context,
    private val settings: () -> Settings,
    /** Карточка «Поставить / Отмена» в разговоре; true — пользователь согласился. */
    private val confirm: suspend (title: String, detail: String) -> Boolean,
    /** Строка итога в ленте. */
    private val report: (icon: String, title: String, subtitle: String, button: String?, action: String?) -> Unit,
) : ToolHost {

    override fun specs(mode: Mode): List<ToolSpec> = if (!mode.tools) emptyList() else listOf(
        ToolSpec(
            "alarm_set", "Поставить будильник в приложении «Часы».",
            """{"type":"object","properties":{"time":{"type":"string","description":"Время ЧЧ:ММ, 24-часовой формат"},"label":{"type":"string","description":"Подпись, до 40 символов"}},"required":["time"]}""",
        ),
        ToolSpec(
            "timer_set", "Запустить таймер в приложении «Часы».",
            """{"type":"object","properties":{"seconds":{"type":"integer","description":"Длительность в секундах, от 1 до 86400"},"label":{"type":"string"}},"required":["seconds"]}""",
        ),
    )

    private val safe = setOf("alarm.set", "timer.set")

    override suspend fun call(name: String, argumentsJson: String): String {
        val args = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return """{"ok":false,"error":"bad_arguments"}"""
        val id = when (name) { "alarm_set" -> "alarm.set"; "timer_set" -> "timer.set"; else -> return """{"ok":false,"error":"unknown_tool"}""" }
        val s = settings()
        when (s.policy[id] ?: Policy.ASK) {
            Policy.OFF -> return """{"ok":false,"error":"disabled_by_user","message":"Действие запрещено пользователем в настройках"}"""
            else -> Unit
        }
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (locked && !(s.lockScope != LockScope.OFF && id in safe)) {
            return """{"ok":false,"error":"locked","message":"Телефон заблокирован — нужна разблокировка"}"""
        }
        return when (id) {
            "alarm.set" -> {
                val time = args["time"]?.jsonPrimitive?.contentOrNull ?: return """{"ok":false,"error":"no_time"}"""
                val m = Regex("^(\\d{1,2}):(\\d{2})$").find(time.trim()) ?: return """{"ok":false,"error":"bad_time"}"""
                val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
                if (h !in 0..23 || min !in 0..59) return """{"ok":false,"error":"bad_time"}"""
                val label = args["label"]?.jsonPrimitive?.contentOrNull?.take(40)
                val title = "Будильник · %d:%02d".format(h, min)
                if (s.policy[id] == Policy.ASK && !confirm(title, label ?: "Поставить?")) return """{"ok":false,"error":"declined_by_user"}"""
                PhoneActions.setAlarm(context, h, min, label).fold(
                    onSuccess = { report("⏰", title, "на телефоне", "Открыть Часы", "alarms"); """{"ok":true}""" },
                    onFailure = { """{"ok":false,"error":"clock_unavailable"}""" },
                )
            }
            "timer.set" -> {
                val sec = args["seconds"]?.jsonPrimitive?.intOrNull ?: return """{"ok":false,"error":"no_seconds"}"""
                if (sec !in 1..86400) return """{"ok":false,"error":"bad_seconds"}"""
                val label = args["label"]?.jsonPrimitive?.contentOrNull?.take(40)
                val title = "Таймер · " + if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
                if (s.policy[id] == Policy.ASK && !confirm(title, label ?: "Запустить?")) return """{"ok":false,"error":"declined_by_user"}"""
                PhoneActions.setTimer(context, sec, label).fold(
                    onSuccess = { report("⏱", title, "на телефоне", "Открыть таймеры", "timers"); """{"ok":true}""" },
                    onFailure = { """{"ok":false,"error":"clock_unavailable"}""" },
                )
            }
            else -> """{"ok":false,"error":"unknown_tool"}"""
        }
    }
}
