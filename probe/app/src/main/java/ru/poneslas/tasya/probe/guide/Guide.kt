package ru.poneslas.tasya.probe.guide

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.Registry
import ru.poneslas.tasya.probe.core.Results
import ru.poneslas.tasya.probe.core.Runner
import ru.poneslas.tasya.probe.core.Status

/** Шаг прогона: проверка и как её называть человеку. */
data class Step(val id: String, val title: String)

data class Part(val n: Int, val title: String, val note: String, val minutes: Int, val steps: List<Step>)

/**
 * Ведущий прогон: две части. Первая идёт без человека подряд, вторая — по шагу с короткой
 * инструкцией. Состояние — в Results, так что прогон переживает перезапуск и обновление.
 */
object Guide {
    val parts = listOf(
        Part(
            1, "Телефон сам", "Положите телефон экраном вверх и не трогайте.", 8,
            listOf(Step("П4", "Микрофон"), Step("П6", "Камера"), Step("П23", "Ответ после закрытия листа"), Step("П25", "Ключ API")),
        ),
        Part(
            2, "С вами", "По одному действию за раз.", 15,
            listOf(
                Step("Г1", "Боковая кнопка"), Step("Г2", "Камера с кнопки"), Step("Г3", "Заблокированный экран"),
                Step("Г4", "Фото и файл из листа"), Step("Г5", "Ответ при выключенном экране"),
                Step("Г6", "Видно ли настройку кнопки"), Step("Г7", "После обновления"),
            ),
        ),
    )

    val allSteps get() = parts.flatMap { it.steps }

    private val _current = MutableStateFlow<Step?>(null)
    /** Шаг, который идёт сейчас. */
    val current: StateFlow<Step?> = _current
    private var job: Job? = null

    fun isGuideStep(id: String?) = id != null && allSteps.any { it.id == id }
    fun partOf(id: String) = parts.first { p -> p.steps.any { it.id == id } }
    fun indexIn(id: String) = partOf(id).steps.indexOfFirst { it.id == id }

    fun status(id: String): Status = Results.get(id).status
    /** Пройден: есть итог, и шаг не прерван и не оборван (такой проходим заново). */
    private fun done(id: String): Boolean {
        val r = Results.get(id)
        return r.status in setOf(Status.PASS, Status.FAIL, Status.PARTIAL) &&
            !r.summary.startsWith("Прервано") && !r.summary.startsWith("оборвано") &&
            !(id == "Г7" && r.summary.startsWith("Ждём"))
    }

    /** Первый незаконченный шаг части (или null — часть пройдена). */
    fun nextIn(part: Part) = part.steps.firstOrNull { !done(it.id) }

    fun partDone(part: Part) = nextIn(part) == null

    /** Запустить часть с первого незаконченного шага; [only] — один шаг (повтор). */
    fun start(ctx: Context, part: Part, only: Step? = null) {
        if (job?.isActive == true || Runner.running.value != null) return
        job = App.scope.launch {
            try {
                val steps = only?.let { listOf(it) } ?: part.steps.filter { !done(it.id) }
                for (s in steps) {
                    _current.value = s
                    Runner.runAwait(ctx, Registry.probe(s.id))
                    // Г7 ждёт установки новой версии — дальше идти некуда.
                    if (s.id == "Г7" && !done(s.id)) break
                }
            } finally {
                _current.value = null
            }
        }
    }

    /**
     * Итог шага простыми словами. Шаги части 2 пишут его сами; подробные проверки части 1 пишут
     * технический итог — здесь он пересказывается по цифрам.
     */
    fun plain(id: String): String {
        val r = Results.get(id)
        if (r.status == Status.NOT_RUN || r.status == Status.RUNNING) return ""
        fun m(k: String) = r.metrics[k]?.takeIf { it.isNotEmpty() }?.let { ru.poneslas.tasya.probe.core.median(it) }
        fun ms(v: Double?) = v?.let { "${it.toLong()} мс" } ?: "—"
        return when (id) {
            "П4" -> {
                val buf = m("early_show_to_first_buffer_ms")
                val zeros = m("early_first300_zero_fraction")
                "Запись начинается через ${ms(buf?.coerceAtLeast(0.0))} после появления листа (цель — до 100)" +
                    (zeros?.let { if (it < 0.9) "; начало записи не пустое" else "; начало записи пустое" } ?: "")
            }
            "П6" -> "Видоискатель на весь экран через ${ms(m("insheet_texture_call_to_preview_ms"))} (цель — до 700); " +
                "заморозка кадра ${ms(m("insheet_texture_freeze_ms"))}; через отдельный экран — ${ms(m("activity_call_to_preview_ms"))}"
            "П23" -> {
                val c = m("coroutine_done") == 1.0
                val w = m("workmanager_done") == 1.0
                when {
                    c && w -> "Ответ дочитывается после закрытия листа, уведомление приходит"
                    c || w -> "Дочитывается только одним способом из двух"
                    else -> "Ответ после закрытия листа не дочитывается"
                }
            }
            "П25" -> if (r.status == Status.FAIL) "Ключ API не расшифровывается" else "Ключ API шифруется и расшифровывается"
            else -> r.summary
        }
    }

    fun stop() {
        job?.cancel()
        Runner.cancel()
    }

    /** После обновления пробника — закончить шаг «После обновления» без касаний. */
    fun resumeAfterUpdate(ctx: Context) {
        if (UpdateStep.pending(ctx)) start(ctx, parts[1], parts[1].steps.first { it.id == "Г7" })
    }
}
