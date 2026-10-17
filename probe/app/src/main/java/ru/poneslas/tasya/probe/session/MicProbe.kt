package ru.poneslas.tasya.probe.session

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status

/** П4: запись из процесса, пока лист показан, без FGS; что с записью и процессом после скрытия. */
object MicProbe : Probe(
    "П4", "Микрофон в окне без активности; процесс после скрытия",
    "Пока лист показан, AudioRecord пишет без foreground-сервиса; после скрытия — запись и важность процесса.",
    needsHuman = false,
    steps = listOf(
        "Пробник открывает лист и пишет 5 с (16 кГц моно): RMS, доля нулей, onShow → первый буфер, заглушён ли клиент.",
        "Закрывает лист и сразу пишет ещё 5 с — ожидаем тишину или ошибку.",
        "Затем каждые 10 с пишет важность процесса (по умолчанию 5 минут).",
        "Если говорить во время первой записи — RMS заметно выше; на эмуляторе сигнал может быть нулевым.",
    ),
) {
    /** Сколько следить за важностью процесса после скрытия. */
    var monitorMs = 5 * 60_000L

    override suspend fun ProbeScope.run(): Status {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            summary("Нет разрешения RECORD_AUDIO — нажмите «Разрешения» на главном экране")
            return Status.FAIL
        }
        Sheet.close()
        delay(500)
        log("importance_before", importanceName(importance()))

        // 1. Лист показан
        val open = Sheet.open()
        if (open == null) {
            summary("Лист не открылся")
            return Status.FAIL
        }
        log("importance_shown", importanceName(importance()))
        Sheet.setStatus("П4: запись 5 с — можно говорить")
        val shown = recordFor(ctx, 5000)
        Sheet.setStatus("")
        log("rec_shown", shown.describe())
        shown.tFirstBuffer?.let { metric("show_to_first_buffer_ms", it - open.tShow) }
        shown.tFirstBuffer?.let { metric("start_to_first_buffer_ms", it - shown.tStart) }
        metric("shown_rms", shown.rms)
        metric("shown_zero_fraction", shown.zeroFraction)
        if (!shown.ok) recordError(shown.error)

        // 2. Сразу после скрытия
        Sheet.close()
        log("importance_hidden", importanceName(importance()))
        val hidden = recordFor(ctx, 5000)
        log("rec_hidden", hidden.describe())
        metric("hidden_rms", hidden.rms)
        metric("hidden_zero_fraction", hidden.zeroFraction)

        // 3. Важность процесса после скрытия. Смерть процесса оборвёт прогон — это тоже ответ (П9).
        val t0 = SystemClock.uptimeMillis()
        val seen = linkedMapOf<String, Int>()
        while (SystemClock.uptimeMillis() - t0 < monitorMs) {
            val imp = importance()
            val sec = (SystemClock.uptimeMillis() - t0) / 1000
            log("importance", "${sec} с: ${importanceName(imp)}")
            metric("importance_after_hide", imp)
            seen[importanceName(imp)] = (seen[importanceName(imp)] ?: 0) + 1
            delay(10_000)
        }

        val firstBuf = shown.tFirstBuffer?.let { it - open.tShow }
        val signal = shown.ok && shown.zeroFraction < 0.99 && shown.silenced != true
        val s = buildString {
            append("при листе: ${if (shown.ok) "запись идёт" else "ошибка ${shown.error}"}")
            if (shown.ok) append(", rms ${"%.1f".format(shown.rms)}, нулей ${"%.0f".format(shown.zeroFraction * 100)} %, silenced=${shown.silenced}, onShow→буфер ${firstBuf ?: "—"} мс")
            append("; после скрытия: ${if (!hidden.ok) "ошибка ${hidden.error}" else "rms ${"%.1f".format(hidden.rms)}, нулей ${"%.0f".format(hidden.zeroFraction * 100)} %, silenced=${hidden.silenced}"}")
            append("; важность за ${monitorMs / 60_000} мин: ${seen.entries.joinToString { "${it.key}×${it.value}" }}")
        }
        summary(s)
        return when {
            signal && firstBuf != null && firstBuf <= 100 -> Status.PASS
            // Нулевой сигнал на эмуляторе — не приговор: проверить на телефоне с речью.
            shown.ok -> Status.PARTIAL
            else -> Status.FAIL
        }
    }

    private fun ProbeScope.recordError(msg: String?) = error(IllegalStateException(msg ?: "?"), "запись при показанном листе")
}
