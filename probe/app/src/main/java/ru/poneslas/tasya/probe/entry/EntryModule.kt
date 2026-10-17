package ru.poneslas.tasya.probe.entry

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.assist.OpenRecord
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status
import ru.poneslas.tasya.probe.core.fmt
import ru.poneslas.tasya.probe.core.median

object EntryModule : Module {
    override val probes = listOf(EntryProbe, DynamicShortcutsProbe)

    override fun init(ctx: Context) {
        // Вход из плитки или уведомления-приёмника: шторка остаётся открытой, закрываем её из листа (11.3, 12б).
        // Свой onCloseSystemDialogs лист на это время пропускает, иначе закроется сам.
        App.scope.launch {
            Sheet.opens.collect { r ->
                if (!r.args.getBoolean("collapse")) return@collect
                val s = Sheet.session.value ?: return@collect
                s.ignoreCloseDialogsUntil = SystemClock.uptimeMillis() + 1500
                runCatching { s.closeSystemDialogs() }
                    .onSuccess { EntryLauncher.note("${r.args.getString("src")}: closeSystemDialogs из листа") }
                    .onFailure { EntryLauncher.note("closeSystemDialogs: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }
}

/** Время «вход → первый кадр листа»; null, если вход не положил t_entry. */
fun OpenRecord.entryToFrame(): Long? = args.getLong("t_entry", 0L).takeIf { it > 0 }?.let { tFrame - it }

/**
 * Пока человек делает [instruction], собирает открытия листа с src из [srcs] и события входов.
 * Возвращает открытия; кнопка «Готово» — конец.
 */
suspend fun ProbeScope.collectEntries(
    srcs: Set<String>,
    instruction: String,
    expected: Int,
): List<OpenRecord> = coroutineScope {
    val got = mutableListOf<OpenRecord>()
    val opens = launch {
        Sheet.opens.collect { r ->
            val src = r.args.getString("src") ?: return@collect
            if (src !in srcs) return@collect
            got += r
            log("open", "$src: вход→кадр ${r.entryToFrame()} мс, ждали сервис ${r.args.getLong("wait_ms", -1)} мс, mode=${r.mode}, ${r.describe()}")
            Sheet.setStatus("${src}: ${got.count { it.args.getString("src") == src }} из $expected")
        }
    }
    val events = launch { EntryLauncher.events.collect { log("entry", it) } }
    val answer = async { ask(instruction, listOf("Готово")) }
    answer.await()
    opens.cancel(); events.cancel()
    Sheet.setStatus("")
    got
}

object EntryProbe : Probe(
    "П11", "Ярлыки иконки и плитка открывают лист в режиме",
    "Ярлык или плитка открывают лист в нужном режиме ≤ 400 мс от касания, без мелькания батута и без открытой шторки.",
    needsHuman = true,
    steps = listOf(
        "Долгое нажатие на иконку «Пробник Таси» → ярлык «Перевод (сервис)», 5 раз (11.1).",
        "Ярлык «Что это (showAssist)», 5 раз (11.2).",
        "Ярлык «Гастрогид (камера)», 3 раза (11.5; видоискатель — дело П6, здесь только режим и время).",
        "Добавить плитку «Тася-пробник» в быстрые настройки (карандаш в шторке).",
        "Плитка в режиме «сервис», 5 раз, 2 из них — с заблокированного экрана (11.3).",
        "Плитка в режиме «активность», 5 раз (11.4).",
        "Время меряется от касания ярлыка (onCreate батута) или onClick плитки до первого кадра листа — само касание до этого не видно.",
    ),
) {
    private data class Entry(val key: String, val title: String, val srcs: Set<String>, val mode: String, val n: Int, val tileMode: String? = null)

    private val entries = listOf(
        Entry("11.1", "ярлык «Перевод (сервис)»", setOf("shortcut_service"), "translate", 5),
        Entry("11.2", "ярлык «Что это (showAssist)»", setOf("shortcut_showAssist"), "whatis", 5),
        Entry("11.5", "ярлык «Гастрогид (камера)»", setOf("shortcut_service"), "gastro", 3),
        Entry("11.3", "плитку «Тася» (режим сервис), 2 раза — с блокировки", setOf("tile_service"), "assistant", 5, "service"),
        Entry("11.4", "плитку «Тася» (режим активность)", setOf("tile_activity"), "assistant", 5, "activity"),
    )

    override suspend fun ProbeScope.run(): Status {
        log("service", "VIS жив: ${Sheet.service != null}")
        val verdicts = mutableListOf<Status>()
        val lines = mutableListOf<String>()
        for (e in entries) {
            val go = ask("${e.key}: ${e.title}. Делаем?", listOf("Да", "Пропустить"))
            if (go == "Пропустить") { lines += "${e.key} пропущено"; continue }
            e.tileMode?.let { ProbeTileService.setTileMode(ctx, it) }
            val hint = if (e.tileMode != null) " Подпись плитки должна быть «Тася · ${e.tileMode}» (если нет — свернуть и снова открыть шторку)." else ""
            val opens = collectEntries(e.srcs, "Откройте ${e.title} ${e.n} раз, каждый раз закрывая лист.$hint Потом «Готово».", e.n)
                .filter { it.mode == e.mode }
            val clean = ask("${e.key}: было мелькание батута или открытая шторка поверх листа?", listOf("Чисто", "Мелькание", "Шторка осталась", "Лист не открывался"))
            val times = opens.mapNotNull { it.entryToFrame()?.toDouble() }
            times.forEach { metric("entry_to_frame_ms_${e.key}", it) }
            metric("opens_${e.key}", opens.size)
            val med = if (times.isNotEmpty()) median(times) else Double.NaN
            lines += "${e.key}: ${opens.size} откр., медиана ${if (times.isEmpty()) "—" else fmt(med) + " мс"}, $clean"
            verdicts += when {
                opens.isEmpty() -> Status.FAIL
                med <= 400 && clean == "Чисто" -> Status.PASS
                else -> Status.PARTIAL
            }
        }
        ProbeTileService.setTileMode(ctx, "service")
        summary(lines.joinToString("; "))
        return when {
            verdicts.isEmpty() -> Status.NOT_RUN
            verdicts.all { it == Status.PASS } -> Status.PASS
            verdicts.all { it == Status.FAIL } -> Status.FAIL
            else -> Status.PARTIAL
        }
    }
}
