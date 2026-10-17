package ru.poneslas.tasya.probe.pick

import android.app.ActivityOptions
import android.content.Intent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.assist.ProbeSession
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.assist.SheetAction
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status

object PickModule : Module {
    override val probes = listOf(PickProbe)

    override val actions = listOf(
        SheetAction("pick", "Фото") { s -> startPicker(s, "photo") },
        SheetAction("pick", "Файл") { s -> startPicker(s, "file") },
        SheetAction("pick", "Встроенный пикер") { Sheet.setPanel { EmbeddedPickerPanel() } },
    )
}

private fun startPicker(s: ProbeSession, kind: String) {
    PickBus.tAction = PickBus.now()
    val ctx = s.context
    try {
        s.startAssistantActivity(
            Intent(ctx, PickerTrampolineActivity::class.java).putExtra("kind", kind),
            ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle(),
        )
    } catch (t: Throwable) {
        PickBus.emit(PickEvent.Failed(kind, "startAssistantActivity: ${t.javaClass.simpleName}: ${t.message}"))
    }
}

object PickProbe : Probe(
    "П8", "Файл и фото из галереи из листа",
    "Скрепка: фото (Photo Picker), файл (OpenDocument), встроенный пикер в листе, «Поделиться» из Галереи. " +
        "Результат доходит, файл копируется до закрытия батута, лист после выбора снова виден без рабочего стола между.",
    needsHuman = true,
    steps = listOf(
        "Пробник открывает лист; нажмите в нём «Фото» и выберите фото (8.1).",
        "Снова лист: «Файл» → любой файл, лучше крупный (8.2).",
        "«Встроенный пикер» → выбрать фото прямо в листе (8.3, если SDK Extensions ≥ 15).",
        "С блокировки: вызвать лист, «Фото» — что происходит.",
        "Галерея → фото → Поделиться → «В пробник Таси» (8.4).",
        "После каждого выбора ответить, вернулся ли лист с миниатюрой без мигания рабочего стола.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        if (Sheet.awaitService() == null) {
            summary("Сервис помощника не поднят — роль не у пробника")
            return Status.FAIL
        }
        val ok = mutableMapOf<String, Boolean>()
        ok["photo"] = flow("photo", "8.1: нажмите в листе «Фото» и выберите фото")
        ok["file"] = flow("file", "8.2: нажмите в листе «Файл» и выберите файл")
        if (embeddedPickerAvailable()) {
            ok["embedded"] = flow("embedded", "8.3: нажмите «Встроенный пикер» и выберите фото прямо в листе", trampoline = false)
        } else log("8.3", "недоступен: SDK Extensions < 15")

        Sheet.close()
        log("locked", ask(
            "С блокировки: заблокируйте экран, вызовите лист кнопкой, нажмите «Фото». Что произошло?",
            listOf("Попросил разблокировать", "Пикер поверх блокировки", "Пикер под листом", "Ничего", "Пропустить"),
        ))
        Sheet.close()

        ok["share"] = coroutineScope {
            val ev = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(180_000) { PickBus.events.first { it.kind == "share" && (it is PickEvent.Picked || it is PickEvent.Failed) } }
            }
            val skip = async { ask("8.4: откройте Галерею → фото → Поделиться → «В пробник Таси»", listOf("Пропустить")) }
            while (!ev.isCompleted && !skip.isCompleted) delay(100)
            skip.cancel()
            val e = if (ev.isCompleted) ev.await() else { ev.cancel(); null }
            when (e) {
                is PickEvent.Picked -> {
                    log("share", "${e.mime}, ${e.bytes / 1024} КБ, копия ${e.copyMs} мс")
                    val a = ask("Лист открылся с миниатюрой?")
                    Sheet.close()
                    a == "Да"
                }
                is PickEvent.Failed -> { log("share", "ошибка: ${e.msg}"); false }
                else -> { log("share", "пропущено"); false }
            }
        }

        val s = ok.entries.joinToString("; ") { (k, v) -> "$k: ${if (v) "да" else "нет"}" }
        summary(s)
        val photo = ok["photo"] == true || ok["embedded"] == true
        val file = ok["file"] == true
        return when {
            photo && file -> Status.PASS
            ok.values.any { it } -> Status.PARTIAL
            else -> Status.FAIL
        }
    }

    /**
     * Один выбор: открыть лист, ждать, пока человек выберет, логируя onHide/кадры листа и события батута.
     * Успех — результат пришёл, файл скопирован, лист вернулся с миниатюрой (по словам человека).
     */
    private suspend fun ProbeScope.flow(kind: String, prompt: String, trampoline: Boolean = true): Boolean = coroutineScope {
        val t0 = PickBus.now()
        val sheetLog = launch {
            launch { Sheet.hides.collect { log("$kind/лист", "onHide +${it - t0} мс") } }
            launch { Sheet.opens.collect { log("$kind/лист", "кадр #${it.seq} +${it.tFrame - t0} мс, вложение=${it.args.getString("attachment") != null}") } }
            launch { PickBus.events.filter { it.kind == kind }.collect { log("$kind/событие", it.toString().take(300)) } }
        }
        val thumb = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(240_000) { Sheet.opens.first { it.args.getString("src") == "pick_$kind" } }
        }
        val shown = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(240_000) { PickBus.events.first { it.kind == kind && it is PickEvent.PickerShown } }
        }
        val late = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(250_000) { PickBus.events.first { it.kind == kind && it is PickEvent.LateRead } }
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(240_000) {
                PickBus.events.first { it.kind == kind && (it is PickEvent.Picked || it is PickEvent.Cancelled || it is PickEvent.Failed) }
            }
        }
        if (!Sheet.visible.value) Sheet.open()
        val skip = async { ask(prompt, listOf("Пропустить")) }
        while (!result.isCompleted && !skip.isCompleted) delay(100)
        skip.cancel()
        val e = if (result.isCompleted) result.await() else null

        val good = when (e) {
            is PickEvent.Picked -> {
                (shown.takeIf { trampoline }?.let { withTimeoutOrNull(100) { it.await() } } as? PickEvent.PickerShown)?.let {
                    metric("${kind}_action_to_picker_ms", it.t - PickBus.tAction)
                }
                metric("${kind}_copy_ms", e.copyMs)
                metric("${kind}_bytes", e.bytes)
                log(kind, "${e.mime}, ${e.bytes / 1024} КБ, копия ${e.copyMs} мс")
                if (trampoline) {
                    withTimeoutOrNull(5000) { thumb.await() }?.let { metric("${kind}_result_to_thumb_ms", it.tFrame - e.tResult) }
                        ?: log(kind, "лист с вложением не открылся за 5 с")
                    (withTimeoutOrNull(7000) { late.await() } as? PickEvent.LateRead)?.let {
                        log(kind, "чтение через 5 с: ${it.msg}")
                        if (!it.ok) error(SecurityException(it.msg), "$kind: URI после finish")
                    }
                }
                ask("Лист вернулся с миниатюрой, без рабочего стола между?") == "Да"
            }
            is PickEvent.Cancelled -> { log(kind, "выбор отменён"); false }
            is PickEvent.Failed -> { log(kind, "ошибка: ${e.msg}"); error(IllegalStateException(e.msg), kind); false }
            else -> { log(kind, "пропущено"); false }
        }
        sheetLog.cancel()
        thumb.cancel(); shown.cancel(); late.cancel(); result.cancel()
        Sheet.setPanel(null)
        Sheet.close()
        delay(500)
        good
    }
}
