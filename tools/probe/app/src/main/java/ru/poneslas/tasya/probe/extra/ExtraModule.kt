package ru.poneslas.tasya.probe.extra

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.Settings
import kotlinx.coroutines.delay
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status
import java.util.Calendar

object ExtraModule : Module {
    override val probes = listOf(PanelProbe, ClockResolveProbe, ClockLaunchProbe)
}

private fun resolved(ctx: Context, i: Intent): String {
    val ri = ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) ?: return "ничего"
    val all = ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).size
    return "${ri.activityInfo.packageName}/${ri.activityInfo.name} (всего $all)"
}

/**
 * Запуск [target] из активности пробника или из листа (через startAssistantActivity батута).
 * Возвращает текст ошибки или null.
 */
private suspend fun ProbeScope.launchFrom(fromSheet: Boolean, target: Intent): String? {
    if (!fromSheet) {
        val i = Intent(target)
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i) }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
    }
    Sheet.open() ?: return "лист не открылся"
    val s = Sheet.session.value ?: return "сессии нет"
    ExtraTrampolineActivity.lastError = null
    val t = Intent(ctx, ExtraTrampolineActivity::class.java).putExtra(ExtraTrampolineActivity.EXTRA_TARGET, target)
    runCatching { s.startAssistantActivity(t) }.exceptionOrNull()?.let { return "startAssistantActivity: ${it.javaClass.simpleName}: ${it.message}" }
    delay(1000)
    return ExtraTrampolineActivity.lastError
}

private fun origin(fromSheet: Boolean) = if (fromSheet) "из листа" else "из активности"

object PanelProbe : Probe(
    "П16", "Settings.Panel на One UI",
    "Панели настроек (интернет, громкость, NFC, Wi-Fi) открываются панелью поверх, а не полным экраном настроек.",
    needsHuman = true,
    steps = listOf(
        "Пробник по очереди открывает четыре панели — сначала из своей активности, потом из листа.",
        "После каждой: закрыть панель («Назад» или «Готово») и ответить, что открылось.",
        "Затем полные экраны Bluetooth и Wi-Fi (у Bluetooth панели нет): нужный ли экран, куда ведёт «Назад».",
    ),
) {
    private val panels = listOf(
        Settings.Panel.ACTION_INTERNET_CONNECTIVITY to "интернет",
        Settings.Panel.ACTION_VOLUME to "громкость",
        Settings.Panel.ACTION_NFC to "NFC",
        Settings.Panel.ACTION_WIFI to "Wi-Fi",
    )

    private val screens = listOf(
        Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth (настройки)",
        Settings.ACTION_WIFI_SETTINGS to "Wi-Fi (настройки)",
    )

    override suspend fun ProbeScope.run(): Status {
        val answers = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for ((action, name) in panels) {
            log("resolve", "$name: ${resolved(ctx, Intent(action))}")
            for (fromSheet in listOf(false, true)) {
                val err = launchFrom(fromSheet, Intent(action))
                if (err != null) log("error", "$name ${origin(fromSheet)}: $err")
                val a = ask("Панель «$name» ${origin(fromSheet)}: что открылось?", listOf("Панель", "Полный экран настроек", "Ничего"))
                answers += a
                lines += "$name ${origin(fromSheet)}: ${a.lowercase()}"
            }
            Sheet.close()
        }
        // Bluetooth в Settings.Panel нет — для «включи блютус» остаются полные экраны настроек [док].
        val screenAnswers = mutableListOf<String>()
        for ((action, name) in screens) {
            log("resolve", "$name: ${resolved(ctx, Intent(action))}")
            for (fromSheet in listOf(false, true)) {
                val err = launchFrom(fromSheet, Intent(action))
                if (err != null) log("error", "$name ${origin(fromSheet)}: $err")
                val a = ask("Экран «$name» ${origin(fromSheet)}: это нужный экран? Потом «Назад» — куда вернулись (запишите в заметку, если не в пробник/лист).", listOf("Нужный экран", "Рядом", "Ничего"))
                screenAnswers += a
                lines += "$name ${origin(fromSheet)}: ${a.lowercase()}"
            }
            Sheet.close()
        }
        summary(lines.joinToString("; "))
        return when {
            answers.all { it == "Панель" } && screenAnswers.all { it == "Нужный экран" } -> Status.PASS
            answers.all { it == "Ничего" } && screenAnswers.all { it == "Ничего" } -> Status.FAIL
            else -> Status.PARTIAL
        }
    }
}

/** Интенты «Часов» из AlarmClock; [message] — метка, по которой потом ищем и удаляем. */
private object Clock {
    const val LABEL = "Пробник"

    fun matrix(): List<Pair<String, Intent>> = listOf(
        "SET_TIMER" to setTimer(61),
        "SET_ALARM" to setAlarm(7, 7),
        "DISMISS_ALARM" to dismissAlarm(),
        "DISMISS_TIMER" to Intent(AlarmClock.ACTION_DISMISS_TIMER),
        "SHOW_TIMERS" to Intent(AlarmClock.ACTION_SHOW_TIMERS),
        "SHOW_ALARMS" to Intent(AlarmClock.ACTION_SHOW_ALARMS),
    )

    fun setTimer(seconds: Int) = Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        .putExtra(AlarmClock.EXTRA_MESSAGE, LABEL)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)

    fun setAlarm(hour: Int, minute: Int) = Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, hour)
        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
        .putExtra(AlarmClock.EXTRA_MESSAGE, LABEL)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)

    fun dismissAlarm() = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
        .putExtra(AlarmClock.EXTRA_MESSAGE, LABEL)

    fun nextAlarm(ctx: Context): String {
        val ac = ctx.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return "нет"
        val c = Calendar.getInstance().apply { timeInMillis = ac.triggerTime }
        return "%02d:%02d %td.%<tm (%s)".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c, ac.showIntent?.creatorPackage ?: "?")
    }
}

object ClockResolveProbe : Probe(
    "П17", "Интенты «Часов»: кто их принимает",
    "Какая активность принимает SET_TIMER, SET_ALARM, DISMISS_*, SHOW_*; есть ли разрешение SET_ALARM. Без запуска.",
    needsHuman = false,
) {
    override suspend fun ProbeScope.run(): Status {
        val perm = ctx.checkSelfPermission("com.android.alarm.permission.SET_ALARM") == PackageManager.PERMISSION_GRANTED
        log("permission", "SET_ALARM=$perm")
        log("next_alarm", Clock.nextAlarm(ctx))
        val res = Clock.matrix().associate { (name, i) -> name to resolved(ctx, i) }
        res.forEach { (k, v) -> log("resolve", "$k → $v") }
        val missing = res.filterValues { it == "ничего" }.keys
        summary("разрешение SET_ALARM=$perm; " + if (missing.isEmpty()) "все 6 интентов принимаются (${res["SET_TIMER"]})" else "не принимаются: ${missing.joinToString()}")
        return when {
            "SET_TIMER" in missing || "SET_ALARM" in missing || !perm -> Status.FAIL
            missing.isNotEmpty() -> Status.PARTIAL
            else -> Status.PASS
        }
    }
}

object ClockLaunchProbe : Probe(
    "П17б", "Интенты «Часов»: запуск из листа и активности",
    "SET_TIMER и SET_ALARM с EXTRA_SKIP_UI ставятся тихо, без окна «Часов», в том числе из листа.",
    needsHuman = true,
    steps = listOf(
        "Пробник ставит таймер на 61 с и будильник с меткой «Пробник» — из листа и из активности; смотрит AlarmManager.getNextAlarmClock до и после.",
        "После каждого шага ответить, показалось ли окно «Часов» и появился ли таймер или будильник.",
        "В конце — DISMISS_TIMER и DISMISS_ALARM по метке; оставшиеся тестовые будильники удалить руками.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val now = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 3) }
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)
        val steps = listOf(
            Triple("SET_TIMER", true, Clock.setTimer(61)),
            Triple("SET_ALARM", true, Clock.setAlarm(h, m)),
            Triple("SET_TIMER", false, Clock.setTimer(62)),
            Triple("SET_ALARM", false, Clock.setAlarm(h, (m + 1) % 60)),
            Triple("SHOW_TIMERS", true, Intent(AlarmClock.ACTION_SHOW_TIMERS)),
            Triple("DISMISS_TIMER", false, Intent(AlarmClock.ACTION_DISMISS_TIMER)),
            Triple("DISMISS_ALARM", false, Clock.dismissAlarm()),
        )
        val results = mutableMapOf<String, String>()
        for ((name, fromSheet, intent) in steps) {
            val before = Clock.nextAlarm(ctx)
            val err = launchFrom(fromSheet, intent)
            delay(1500)
            val after = Clock.nextAlarm(ctx)
            log(name, "${origin(fromSheet)}: ошибка=${err ?: "нет"}, следующий будильник $before → $after")
            val a = if (err != null && err != "лист не открылся") "Не сработало" else
                ask("$name ${origin(fromSheet)}: что произошло? (следующий будильник: $before → $after)", listOf("Тихо, сработало", "Окно «Часов»", "Не сработало"))
            results["$name ${origin(fromSheet)}"] = a
            Sheet.close()
        }
        tell("Удалите в «Часах» оставшиеся тестовые таймеры и будильники с меткой «Пробник».")
        summary(results.entries.joinToString("; ") { "${it.key}: ${it.value.lowercase()}" })
        val quietFromSheet = listOf("SET_TIMER из листа", "SET_ALARM из листа").all { results[it] == "Тихо, сработало" }
        val nothing = results.filterKeys { it.startsWith("SET_") }.values.all { it == "Не сработало" }
        return when {
            quietFromSheet -> Status.PASS
            nothing -> Status.FAIL
            else -> Status.PARTIAL
        }
    }
}
