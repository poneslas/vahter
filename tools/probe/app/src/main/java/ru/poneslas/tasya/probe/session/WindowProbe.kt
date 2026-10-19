package ru.poneslas.tasya.probe.session

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status
import ru.poneslas.tasya.probe.core.fmt
import ru.poneslas.tasya.probe.core.median
import java.util.Collections

private fun usedHeapKb(): Long {
    System.gc()
    Runtime.getRuntime().gc()
    val r = Runtime.getRuntime()
    return (r.totalMemory() - r.freeMemory()) / 1024
}

/** П3, автоматическая часть: открытия, кадры прокрутки, клавиатура, куча. Без человека. */
object WindowProbe : Probe(
    "П3", "Окно сессии: Compose, кадры, клавиатура",
    "Лист на Compose в окне сессии: открытие ≤ 300 мс медиана, ≥ 95 % кадров прокрутки в бюджете, клавиатура открывается и поле не уходит под неё.",
    needsHuman = false,
    steps = listOf(
        "Пробник сам: 20 открытий/закрытий листа через showSession.",
        "Прокрутка списка из 50 строк 5 с, кадры через FrameMetrics окна сессии.",
        "Фокус в поле → видна ли клавиатура (IME), её высота, поле над ней.",
        "Куча до и после 20 открытий.",
        "Поворот, голосовой ввод клавиатуры, касание затемнения — отдельная проверка «П3ч» с человеком.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        Sheet.close()
        Sheet.setPanel(P3Panel.content)
        try {
            return body()
        } finally {
            Sheet.close()
            Sheet.setPanel(null)
            P3Panel.reset()
        }
    }

    private suspend fun ProbeScope.body(): Status {
        val heapBefore = usedHeapKb()
        val nativeBefore = Debug.getNativeHeapAllocatedSize() / 1024
        log("heap_before", "java=${heapBefore} КБ native=${nativeBefore} КБ")

        // 1. Серия открытий
        val recs = Sheet.series(20) { i, r -> log("open", "${i + 1}: ${r?.describe() ?: "не открылся за 4 с"}") }
        if (recs.isEmpty()) {
            summary("Лист не открылся ни разу через showSession — нет роли или VIS не готов")
            return Status.FAIL
        }
        recs.forEach { r ->
            val name = if (r.newSession) "new_session" else "warm"
            r.callToFrame?.let { metric("${name}_call_to_frame_ms", it) }
            metric("${name}_show_to_frame_ms", r.showToFrame)
        }
        val warm = recs.filter { !it.newSession }.mapNotNull { it.callToFrame?.toDouble() }
        val heapAfter = usedHeapKb()
        val nativeAfter = Debug.getNativeHeapAllocatedSize() / 1024
        metric("heap_growth_kb", heapAfter - heapBefore)
        metric("native_heap_growth_kb", nativeAfter - nativeBefore)
        log("heap_after", "java=${heapAfter} КБ native=${nativeAfter} КБ")

        // 2. Прокрутка и кадры
        val open = Sheet.open()
        if (open == null) {
            summary("Серия прошла, но лист для прокрутки не открылся")
            return Status.PARTIAL
        }
        val w: Window? = Sheet.session.value?.window?.window
        if (w == null) {
            summary("Нет окна сессии")
            return Status.FAIL
        }
        log("window", "type=${w.attributes.type} flags=0x${Integer.toHexString(w.attributes.flags)} softInput=0x${Integer.toHexString(w.attributes.softInputMode)}")
        val hz = w.decorView.display?.refreshRate ?: 60f
        val budgetNs = 1e9 / hz
        metric("refresh_rate_hz", hz.toDouble())
        val frames = Collections.synchronizedList(mutableListOf<LongArray>())
        val listener = Window.OnFrameMetricsAvailableListener { _, fm, _ ->
            frames += longArrayOf(fm.getMetric(FrameMetrics.TOTAL_DURATION), fm.getMetric(FrameMetrics.DEADLINE))
        }
        withTimeoutOrNull(2000) { while (P3Panel.scope == null) delay(50) }
        val sc = P3Panel.scope
        val st = P3Panel.listState
        if (sc == null || st == null) {
            log("scroll", "панель не появилась — пропуск")
        } else {
            w.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            sc.launch {
                val end = SystemClock.uptimeMillis() + 5000
                var dir = 1f
                while (SystemClock.uptimeMillis() < end) {
                    st.animateScrollBy(dir * 1500f, tween(600))
                    dir = -dir
                }
            }.join()
            delay(200)
            runCatching { w.removeOnFrameMetricsAvailableListener(listener) }
        }
        val snap = synchronized(frames) { frames.toList() }
        val inBudget = snap.count { it[0] <= budgetNs }
        val inDeadline = snap.count { it[1] <= 0 || it[0] <= it[1] }
        val share = if (snap.isEmpty()) 0.0 else inBudget.toDouble() / snap.size
        metric("scroll_frames", snap.size)
        metric("scroll_share_in_budget", share)
        if (snap.isNotEmpty()) metric("scroll_share_in_deadline", inDeadline.toDouble() / snap.size)
        snap.forEach { metric("scroll_frame_ms", it[0] / 1e6) }
        log("scroll", "кадров ${snap.size}, в бюджете ${fmt(budgetNs / 1e6)} мс: $inBudget, в deadline: $inDeadline")

        // 3. Клавиатура
        val decor = w.decorView
        val tFocus = SystemClock.uptimeMillis()
        sc?.launch { P3Panel.focus?.requestFocus(); P3Panel.keyboard?.show() }
        val imeAt = withTimeoutOrNull(3000) {
            while (ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) != true) delay(30)
            SystemClock.uptimeMillis()
        }
        var imeOk = false
        if (imeAt != null) {
            delay(400) // дождаться конца анимации IME и перекладки
            val insets = ViewCompat.getRootWindowInsets(decor)
            val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            val visibleBottom = decor.height - imeBottom
            imeOk = P3Panel.fieldBottom in 1f..visibleBottom.toFloat()
            metric("ime_show_ms", imeAt - tFocus)
            metric("ime_height_px", imeBottom)
            log("ime", "видна через ${imeAt - tFocus} мс, высота $imeBottom px, низ поля ${P3Panel.fieldBottom} px, видимая область до $visibleBottom px → поле ${if (imeOk) "над" else "ПОД"} клавиатурой")
        } else {
            // [?] на эмуляторе с аппаратной клавиатурой экранная может не появляться вовсе
            log("ime", "клавиатура не появилась за 3 с")
        }
        sc?.launch { P3Panel.keyboard?.hide() }

        val s = buildString {
            append("открытий ${recs.size}/20")
            if (warm.isNotEmpty()) append(", тёплые вызов→кадр медиана ${fmt(median(warm))} мс")
            append("; кадров прокрутки ${snap.size}, в бюджете ${"%.0f".format(share * 100)} % (${fmt(hz.toDouble())} Гц)")
            append("; клавиатура: ${if (imeAt == null) "не появилась" else if (imeOk) "поле над ней" else "поле под ней"}")
            append("; куча +${heapAfter - heapBefore} КБ")
        }
        summary(s)
        val openOk = recs.size == 20 && warm.isNotEmpty() && median(warm) <= 300
        val framesOk = snap.isNotEmpty() && share >= 0.95
        return when {
            openOk && framesOk && imeOk -> Status.PASS
            openOk || framesOk -> Status.PARTIAL
            else -> Status.FAIL
        }
    }
}

/** П3, человеческая часть: то, что пробник сам не проверит. */
object WindowHumanProbe : Probe(
    "П3ч", "П3 с человеком: набор, поворот, голосовой ввод, затемнение",
    "Клавиатура в листе после поворота, голосовой ввод клавиатуры Samsung, касание затемнения не проходит в приложение под листом.",
    needsHuman = true,
    steps = listOf(
        "Пробник открывает лист; наберите текст в поле.",
        "Поверните телефон — клавиатура вернулась сама?",
        "Нажмите микрофон на клавиатуре Samsung — голосовой ввод работает?",
        "Коснитесь затемнения над листом — лист закрылся, а приложение под ним касание не получило?",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        Sheet.setPanel(P3Panel.content)
        try {
            if (Sheet.open() == null) {
                summary("Лист не открылся")
                return Status.FAIL
            }
            val typed = ask("Нажмите в поле листа и наберите пару слов. Поле видно над клавиатурой?")
            val rotate = ask("Поверните телефон (автоповорот включён). Клавиатура осталась/вернулась сама, поле видно?", listOf("Да", "Нет", "Пришлось нажать в поле снова"))
            val voice = ask("Нажмите микрофон на клавиатуре и продиктуйте фразу. Текст появился в поле?", listOf("Да", "Нет", "Кнопки нет"))
            val scrim = ask("Коснитесь затемнения над листом (над кнопкой или текстом приложения под ним). Лист закрылся, а под ним ничего не нажалось?", listOf("Да", "Лист закрылся, но нажалось и под ним", "Лист не закрылся"))
            summary("поле над клавиатурой: $typed; поворот: $rotate; голосовой ввод: $voice; затемнение: $scrim")
            val good = listOf(typed == "Да", rotate == "Да", voice == "Да", scrim == "Да")
            return when {
                good.all { it } -> Status.PASS
                typed == "Да" -> Status.PARTIAL
                else -> Status.FAIL
            }
        } finally {
            Sheet.close()
            Sheet.setPanel(null)
            P3Panel.reset()
        }
    }
}
