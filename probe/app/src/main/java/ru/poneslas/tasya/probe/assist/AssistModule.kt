package ru.poneslas.tasya.probe.assist

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.TAG
import ru.poneslas.tasya.probe.core.Header
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.ReplaceLog
import ru.poneslas.tasya.probe.core.Status
import ru.poneslas.tasya.probe.core.fmt
import ru.poneslas.tasya.probe.core.median
import ru.poneslas.tasya.probe.core.percentile

object AssistModule : Module {
    override val probes = listOf(RoleProbe, ButtonProbe)
    override val actions = listOf(
        SheetAction("assist", "Убить процесс") { s ->
            Log.i(TAG, "Убиваю процесс по кнопке")
            s.hide()
            delay(300)
            Process.killProcess(Process.myPid())
        },
    )
}

/** Открыть настройки помощника: сначала голосовой ввод, затем приложения по умолчанию. Возвращает имя активности. */
fun openAssistantSettings(ctx: Context): String {
    for (action in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ri = ctx.packageManager.resolveActivity(i, 0) ?: continue
        ctx.startActivity(i)
        return "$action → ${ri.activityInfo.packageName}/${ri.activityInfo.name}"
    }
    return "ни один интент настроек не нашёлся"
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("probes", Context.MODE_PRIVATE)

object RoleProbe : Probe(
    "П1", "Роль помощника: назначение, переживает обновление",
    "Роль ставится через настройки и не слетает при обновлении, пока в XML есть recognitionService.",
    needsHuman = true,
    steps = listOf(
        "Пробник проверяет роль; если её нет — открывает настройки, выберите «Пробник Таси».",
        "На Mac: scripts/bump.sh 1, затем scripts/bump.sh 2 — две обновлённые сборки подряд.",
        "На Mac: scripts/bump.sh 3 norec — сборка без recognitionService (роль должна слететь).",
        "На Mac: scripts/install.sh — вернуть нормальную сборку и роль.",
        "Снова запустите П1 и выберите «Посчитать».",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val rm = ctx.getSystemService(RoleManager::class.java)
        log("role", "available=${rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)} held=${rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)} visActive=${Header.visActive(ctx)}")
        val p = prefs(ctx)
        val marker = p.getLong("p1_marker", 0)
        val choice = if (marker == 0L) "Новый замер" else ask(
            "Есть начатый замер обновлений (${ReplaceLog.since(ctx, marker).size} записей). Что делаем?",
            listOf("Посчитать", "Новый замер"),
        )
        if (choice == "Посчитать") return evaluate(marker)

        if (!Header.roleHeld(ctx)) {
            val got = coroutineScope {
                val answer = async { ask("Роль помощника не у пробника. Откроются настройки — выберите «Пробник Таси» и вернитесь.", listOf("Не получается")) }
                log("settings", openAssistantSettings(ctx))
                val ok = withTimeoutOrNull(180_000) { while (!Header.roleHeld(ctx) && !answer.isCompleted) delay(500); Header.roleHeld(ctx) } ?: false
                answer.cancel()
                ok
            }
            log("role_after_settings", "held=$got visActive=${Header.visActive(ctx)}")
            if (!got) {
                summary("Роль через настройки не поставилась")
                return Status.FAIL
            }
        }
        // VIS поднимается системой не сразу после выдачи роли.
        val ready = Sheet.awaitService(5000) != null
        log("vis_ready", ready.toString())
        p.edit { putLong("p1_marker", System.currentTimeMillis()) }
        tell("Роль у пробника. Теперь на Mac по очереди: scripts/bump.sh 1; scripts/bump.sh 2; scripts/bump.sh 3 norec; scripts/install.sh. Процесс пробника при каждой установке перезапустится — потом снова откройте П1 и выберите «Посчитать».", "Понятно")
        summary("Роль поставлена; ждём обновлений, затем «Посчитать»")
        return Status.PARTIAL
    }

    private fun ProbeScope.evaluate(marker: Long): Status {
        val all = ReplaceLog.since(ctx, marker)
        all.forEach { log("replaced", "${it.versionName} (${it.versionCode}) ${it.stage}: роль=${it.roleHeld} vis=${it.visActive} installer=${it.installer}") }
        val late = all.filter { it.stage != "сразу" }.ifEmpty { all }
        val normal = late.filter { !it.versionName.endsWith("-norec") }
        val control = late.filter { it.versionName.endsWith("-norec") }
        val normalKept = normal.count { it.roleHeld }
        metric("updates_normal", normal.size)
        metric("updates_normal_role_kept", normalKept)
        metric("updates_norec", control.size)
        metric("updates_norec_role_lost", control.count { !it.roleHeld })
        val s = "обычных обновлений ${normal.size}, роль осталась в $normalKept; без recognitionService ${control.size}, роль слетела в ${control.count { !it.roleHeld }}"
        summary(s)
        return when {
            normal.size >= 2 && normalKept == normal.size && control.isNotEmpty() && control.all { !it.roleHeld } -> Status.PASS
            normal.isNotEmpty() && normalKept < normal.size -> Status.FAIL
            else -> Status.PARTIAL
        }
    }
}

object ButtonProbe : Probe(
    "П2", "Боковая кнопка, время до листа",
    "Удержание боковой кнопки открывает наш лист; медиана ≤ 300 мс тёплым, ≤ 700 мс холодным.",
    needsHuman = true,
    steps = listOf(
        "Настройки → Дополнительные функции → Боковая кнопка → Нажатие и удержание → Цифровой помощник.",
        "Тёплые: 10 раз удержать кнопку и закрыть лист, между нажатиями ~3 с. Пять из них — на заблокированном экране.",
        "Холодные: в листе «Убить процесс», затем удержать кнопку; повторить 5 раз.",
        "Снова запустить П2 → «Посчитать».",
        "Нажатие → кадр пробник берёт из времени нажатия, которое система кладёт в args; сверка — scripts/trace.sh (Perfetto).",
        "На эмуляторе вместо кнопки: adb shell input keyevent KEYCODE_ASSIST.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val p = prefs(ctx)
        val marker = p.getLong("p2_marker", 0)
        val choice = if (marker == 0L) "Новый замер" else ask("Есть начатый замер. Что делаем?", listOf("Посчитать", "Новый замер"))
        if (choice == "Новый замер") {
            val m = System.currentTimeMillis()
            p.edit { putLong("p2_marker", m) }
            coroutineScope {
                val stop = async { ask("Удержите боковую кнопку 10 раз (закрывая лист между нажатиями), 5 — на блокировке. Счёт — в строке листа.", listOf("Хватит")) }
                val collector = async {
                    Sheet.opens.filter { it.tCall == null }.take(10).collect { r ->
                        log("open", r.describe())
                        Sheet.setStatus("П2: открытий ${r.seq}; последнее ${r.prepareToFrame} мс")
                    }
                }
                while (!stop.isCompleted && !collector.isCompleted) delay(300)
                stop.cancel(); collector.cancel()
            }
            Sheet.setStatus("")
            tell("Теперь холодные: в листе «Убить процесс», потом удержать кнопку; 5 раз. Потом снова П2 → «Посчитать». Если холодные не нужны — «Готово».")
        }
        return evaluate(p.getLong("p2_marker", 0))
    }

    private fun ProbeScope.evaluate(marker: Long): Status {
        val opens = OpenLog.since(ctx, marker).filter { it.callToFrame == null }
        if (opens.isEmpty()) {
            summary("Ни одного открытия кнопкой — Bixby/Gemini перехватывают удержание или роль не у пробника")
            return Status.FAIL
        }
        val warm = opens.filter { !it.fresh }
        val cold = opens.filter { it.fresh }
        warm.forEach { metric("warm_prepare_to_frame_ms", it.prepareToFrame) }
        cold.forEach { metric("cold_prepare_to_frame_ms", it.prepareToFrame) }
        warm.mapNotNull { it.invokeToFrame }.forEach { metric("warm_press_to_frame_ms", it) }
        cold.mapNotNull { it.invokeToFrame }.forEach { metric("cold_press_to_frame_ms", it) }
        opens.filter { it.locked }.forEach { metric("locked_prepare_to_frame_ms", it.prepareToFrame) }
        log("flags", opens.map { showFlags(it.flags) }.distinct().joinToString())
        // Полная цена — от нажатия (время из args системы); если его нет — от onPrepareToShowSession.
        val haveInvoke = opens.all { it.invokeToFrame != null }
        val wm = warm.map { (if (haveInvoke) it.invokeToFrame!! else it.prepareToFrame).toDouble() }
        val cm = cold.map { (if (haveInvoke) it.invokeToFrame!! else it.prepareToFrame).toDouble() }
        val s = buildString {
            append("тёплых ${warm.size}")
            if (wm.isNotEmpty()) append(": медиана ${fmt(median(wm))} мс, p90 ${fmt(percentile(wm, .9))}")
            append("; холодных ${cold.size}")
            if (cm.isNotEmpty()) append(": медиана ${fmt(median(cm))} мс")
            append("; флаги: ${opens.map { showFlags(it.flags) }.distinct().joinToString()}")
            append(if (haveInvoke) ". Отсчёт от нажатия (время из args системы)." else ". Отсчёт от onPrepareToShowSession, без цены кнопки — её даёт Perfetto.")
        }
        summary(s)
        val warmOk = wm.isNotEmpty() && median(wm) <= 300
        val coldOk = cm.isEmpty() || median(cm) <= 700
        return when {
            warmOk && coldOk && cm.isNotEmpty() -> Status.PASS
            warmOk && coldOk -> Status.PARTIAL
            else -> Status.FAIL
        }
    }
}
