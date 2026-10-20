package ru.poneslas.vahter.probe.expand

import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.vahter.probe.Module
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.assist.SheetAction
import ru.poneslas.vahter.probe.core.Probe
import ru.poneslas.vahter.probe.core.ProbeScope
import ru.poneslas.vahter.probe.core.Status
import ru.poneslas.vahter.probe.core.fmt
import ru.poneslas.vahter.probe.core.median

object ExpandModule : Module {
    override val probes = listOf(ExpandProbe)
    override val actions = listOf(
        SheetAction("expand", "Развернуть (П10)") { s ->
            val ms = ExpandBus.expand(s) { Sheet.setStatus("Развернуть: ${it.javaClass.simpleName}: ${it.message}") }
            if (ms != null) Sheet.setStatus("Развернуть: первый кадр через $ms мс")
        },
    )
}

/** П10: развернуть лист в активность и свернуть обратно, 20 раз с таймингами. Мигание оценивает глаз. */
object ExpandProbe : Probe(
    "П10", "Развернуть лист в приложение и обратно",
    "«Развернуть» — startAssistantActivity без анимации, лист прячется по первому кадру активности; «Свернуть» — showSession + finish. Цель ≤ 250 мс и без кадра рабочего стола.",
    needsHuman = false,
    steps = listOf(
        "Пробник сам: 20 раз лист → «Развернуть» → 1 с → «Свернуть» (половина — сначала showSession, половина — сначала finish).",
        "Мигание: перед запуском на Mac `adb shell screenrecord /sdcard/p10.mp4` (до 3 мин), потом `adb pull /sdcard/p10.mp4` и покадрово. Итог — в заметку и статус вручную.",
        "Руками: в развёрнутом окне «Назад», «Домой», «Недавние»; повтор над светлым и тёмным приложением (кнопка «Развернуть (П10)» в листе).",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        Sheet.close()
        delay(300)
        var okExpand = 0
        var okCollapse = 0
        val expandMs = mutableListOf<Double>()
        repeat(20) { i ->
            val showFirst = i % 2 == 0
            val open = Sheet.open()
            val session = Sheet.session.value
            if (open == null || session == null) {
                log("open", "${i + 1}: лист не открылся")
                return@repeat
            }
            delay(300)
            val t0 = SystemClock.uptimeMillis()
            val (ms, tHide) = coroutineScope {
                val hideWait = async { withTimeoutOrNull(3000) { Sheet.hides.first() } }
                val ms = ExpandBus.expand(session) { error(it, "startAssistantActivity") }
                ms to (if (ms == null) null.also { hideWait.cancel() } else hideWait.await())
            }
            if (ms == null) {
                log("expand", "${i + 1}: активность не нарисовалась за 3 с")
                Sheet.close()
                delay(500)
                return@repeat
            }
            okExpand++
            expandMs += ms.toDouble()
            metric("expand_ms", ms)
            tHide?.let { metric("expand_to_hide_ms", it - t0) }
            log("expand", "${i + 1}: кадр активности $ms мс, onHide ${tHide?.let { "${it - t0} мс" } ?: "не пришёл"}")
            delay(1000)
            val back = ExpandBus.collapse(showFirst)
            val name = if (showFirst) "collapse_show_first_ms" else "collapse_finish_first_ms"
            val c = back?.callToFrame
            if (c != null) {
                okCollapse++
                metric(name, c)
            }
            log("collapse", "${i + 1}: ${if (showFirst) "showSession→finish" else "finish→showSession"}: ${back?.describe() ?: "лист не вернулся за 3 с"}")
            withTimeoutOrNull(2000) { while (ExpandBus.current != null) delay(50) }
            Sheet.close()
            delay(500)
        }
        Sheet.close()
        val med = if (expandMs.isEmpty()) Double.NaN else median(expandMs)
        summary("развернулось $okExpand/20, медиана ${fmt(med)} мс; свернулось $okCollapse/20. Мигание — только по записи экрана, статус ставит человек.")
        // Тайминги без глаза не дают «прошло»: критерий включает «без мигания». Человек ставит статус сам.
        return if (okExpand > 0) Status.PARTIAL else Status.FAIL
    }
}
