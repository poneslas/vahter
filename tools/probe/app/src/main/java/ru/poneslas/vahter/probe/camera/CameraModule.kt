package ru.poneslas.vahter.probe.camera

import android.Manifest
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.os.bundleOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.Module
import ru.poneslas.vahter.probe.TAG
import ru.poneslas.vahter.probe.assist.OpenRecord
import ru.poneslas.vahter.probe.assist.ProbeSession
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.assist.SheetAction
import ru.poneslas.vahter.probe.core.Probe
import ru.poneslas.vahter.probe.core.ProbeScope
import ru.poneslas.vahter.probe.core.Results
import ru.poneslas.vahter.probe.core.Status
import ru.poneslas.vahter.probe.core.fmt
import ru.poneslas.vahter.probe.core.median

object CameraModule : Module {
    override val probes = listOf(CameraAutoProbe, CameraHumanProbe)

    override val actions = listOf(
        SheetAction("camera", "Камера в листе") { InSheetCamera.start(Variant.INSHEET_COMPAT, null) },
        SheetAction("camera", "Камера: активность") { s -> launchCameraActivity(s, Variant.ACTIVITY, null) },
        SheetAction("camera", "Системная камера") { s -> launchCameraActivity(s, Variant.SYSTEM, null) },
    )

    override fun init(ctx: Context) {
        App.scope.launch { Sheet.opens.collect { onOpen(it) } }
        App.scope.launch { Sheet.hides.collect { InSheetCamera.stop() } }
    }

    /**
     * Каждое открытие листа: вложение из args → миниатюра; `camera=…` (пробник, ярлык камеры)
     * или «взведённый» П6+ вариант (открытие человеком) → запуск камеры.
     * Камера стартует по первому кадру листа, а не в onShow: onShow — в assist/, туда не лезем;
     * в цифры это добавляет время первого кадра листа (оно же видно в OpenRecord).
     */
    private fun onOpen(r: OpenRecord) {
        r.args.getString("attachment")?.let { path ->
            Sheet.setPanel { AttachmentThumb(path, "из ${r.args.getString("src") ?: "?"}") }
            return
        }
        val v = r.args.getString("camera") ?: CameraBus.armed?.takeIf { r.tCall == null } ?: return
        Log.i(TAG, "camera: открытие #${r.seq} → $v")
        when (v) {
            Variant.INSHEET_COMPAT, Variant.INSHEET_PERF -> InSheetCamera.start(v, r)
            Variant.ACTIVITY, Variant.SYSTEM -> Sheet.session.value?.let { launchCameraActivity(it, v, r) }
        }
    }
}

/** 6.2/6.3: активность из листа. Лист должен быть показан, иначе START_ASSISTANT_HIDDEN_SESSION [AOSP]. */
fun launchCameraActivity(s: ProbeSession, v: String, open: OpenRecord?) {
    val ctx = s.context
    CameraBus.activityOpen = open
    val cls = if (v == Variant.SYSTEM) ImageCaptureTrampoline::class.java else CameraActivity::class.java
    try {
        s.startAssistantActivity(Intent(ctx, cls), ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle())
    } catch (t: Throwable) {
        Log.w(TAG, "startAssistantActivity $v", t)
        CameraBus.emit(CamEvent.Failed(v, "startAssistantActivity: ${t.javaClass.simpleName}: ${t.message}", open))
    }
}

private fun hasCameraPermission(ctx: Context) =
    ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun key(v: String) = when (v) {
    Variant.INSHEET_COMPAT -> "insheet_texture"
    Variant.INSHEET_PERF -> "insheet_surface"
    Variant.ACTIVITY -> "activity"
    else -> "system"
}

/** Кто ответит на ACTION_IMAGE_CAPTURE и предустановлен ли он (6.3). */
private fun ProbeScope.logImageCaptureHandler() {
    val ri = ctx.packageManager.resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0)
    if (ri == null) {
        log("6.3", "ACTION_IMAGE_CAPTURE: обработчика нет")
    } else {
        val sys = ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        log("6.3", "ACTION_IMAGE_CAPTURE → ${ri.activityInfo.packageName}/${ri.activityInfo.name}, системная=$sys")
    }
}

private fun ProbeScope.summarize(ok: Map<String, Int>, tries: Map<String, Int>, metricSuffix: String): Pair<String, Status> {
    val r = Results.get(id)
    val parts = ok.keys.map { v ->
        val m = r.metrics["${key(v)}_$metricSuffix"].orEmpty()
        val fz = r.metrics["${key(v)}_freeze_ms"].orEmpty()
        "${Variant.title(v)}: ${ok[v]}/${tries[v]}" + (if (m.isNotEmpty()) ", медиана ${fmt(median(m))} мс" else "") +
            (if (fz.isNotEmpty()) ", заморозка кадра ${fmt(median(fz))} мс" else "")
    }
    val good = ok.keys.filter { v ->
        val m = r.metrics["${key(v)}_$metricSuffix"].orEmpty()
        v != Variant.SYSTEM && ok[v] == tries[v] && (tries[v] ?: 0) > 0 && m.isNotEmpty() && median(m) <= 700
    }
    val status = when {
        good.isNotEmpty() -> Status.PASS
        ok.filterKeys { it != Variant.SYSTEM }.values.any { it > 0 } -> Status.PARTIAL
        else -> Status.FAIL
    }
    return parts.joinToString("; ") to status
}

object CameraAutoProbe : Probe(
    "П6", "Камера сразу по вызову: три способа (авто)",
    "Пробник сам открывает лист с камерой по 10 раз: 6.1 CameraX в листе (TextureView и SurfaceView), 6.2 своя активность. " +
        "Цифра — от вызова showSession до STREAMING превью; цель ≤ 700 мс, 10 из 10. Цены боковой кнопки здесь нет — она в П6+.",
    needsHuman = false,
    steps = listOf(
        "Нужны роль помощника и разрешение «Камера» (кнопка «Разрешения»).",
        "Человек не нужен: лист с видоискателем откроется и закроется 30 раз.",
        "6.3 (системная камера) требует нажать спуск — она в П6+; здесь только проверяется, кто отвечает на IMAGE_CAPTURE.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        if (!hasCameraPermission(ctx)) {
            summary("Нет разрешения CAMERA — нажмите «Разрешения» на главном экране")
            return Status.FAIL
        }
        if (Sheet.awaitService() == null) {
            summary("Сервис помощника не поднят — роль не у пробника")
            return Status.FAIL
        }
        logImageCaptureHandler()
        val variants = listOf(Variant.INSHEET_COMPAT, Variant.INSHEET_PERF, Variant.ACTIVITY)
        val ok = variants.associateWith { 0 }.toMutableMap()
        val tries = variants.associateWith { 10 }
        for (v in variants) {
            repeat(10) { i ->
                Sheet.setStatus("П6: ${Variant.title(v)}, ${i + 1}/10")
                if (attempt(v)) ok[v] = ok.getValue(v) + 1
            }
        }
        Sheet.setStatus("")
        val (s, status) = summarize(ok, tries, "call_to_preview_ms")
        summary("$s (вызов → видоискатель)")
        return status
    }

    private suspend fun ProbeScope.attempt(v: String): Boolean = coroutineScope {
        val ev = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(6000) { CameraBus.events.first { it.variant == v && (it is CamEvent.Streaming || it is CamEvent.Failed) } }
        }
        val created = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(6000) { CameraBus.events.first { it.variant == v && it is CamEvent.ActivityCreated } }
        }
        val r = Sheet.open(bundleOf("camera" to v))
        if (r == null) {
            ev.cancel(); created.cancel()
            log(v, "лист не открылся")
            return@coroutineScope false
        }
        val e = ev.await()
        if (v == Variant.ACTIVITY) {
            (created.await() as? CamEvent.ActivityCreated)?.let { metric("${key(v)}_call_to_activity_ms", it.t - r.tCall!!) }
        } else created.cancel()
        val good = when (e) {
            is CamEvent.Streaming -> {
                metric("${key(v)}_call_to_preview_ms", e.t - r.tCall!!)
                metric("${key(v)}_frame_to_preview_ms", e.t - r.tFrame)
                log(v, "#${r.seq} видоискатель ${e.t - r.tCall!!} мс от вызова, importance=${CameraBus.importanceName(e.importance)}")
                if (v != Variant.ACTIVITY) {
                    // Нажатие инструмента («Обрезать / Обвести / Стрелка») замораживает кадр — сколько это стоит.
                    delay(300)
                    val fr = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(3000) { CameraBus.events.first { it.variant == v && (it is CamEvent.Frozen || it is CamEvent.Failed) } }
                    }
                    InSheetCamera.freeze?.invoke() ?: fr.cancel()
                    when (val f = if (fr.isCancelled) null else fr.await()) {
                        is CamEvent.Frozen -> {
                            metric("${key(v)}_freeze_ms", f.ms)
                            log(v, "заморозка кадра ${f.ms} мс, ${f.w}×${f.h}")
                        }
                        is CamEvent.Failed -> log(v, "заморозка: ${f.msg}")
                        else -> log(v, "заморозка: нет ответа")
                    }
                }
                true
            }
            is CamEvent.Failed -> {
                log(v, "#${r.seq} ошибка: ${e.msg}")
                error(IllegalStateException(e.msg), v)
                false
            }
            else -> {
                log(v, "#${r.seq} нет кадра превью за 6 с")
                false
            }
        }
        CameraBus.activity?.finish()
        delay(300)
        Sheet.close()
        InSheetCamera.stop()
        delay(700)
        good
    }
}

object CameraHumanProbe : Probe(
    "П6+", "Камера с боковой кнопки, блокировка, занятая камера",
    "Те же три способа, но вызов — настоящей кнопкой: цифра от onPrepareToShowSession до видоискателя. " +
        "Снимок должен оказаться в листе. Потом — с блокировки и когда камера занята другим приложением.",
    needsHuman = true,
    steps = listOf(
        "Пробник «взводит» вариант: следующее удержание кнопки откроет лист сразу с камерой.",
        "6.1 и 6.2 — по 5 удержаний; в первом сделать снимок «Снять» и проверить миниатюру в листе.",
        "6.3 — 2 раза: системная камера, снять, подтвердить.",
        "С блокировки — 6.1 и 6.2 по разу.",
        "Занятая камера: открыть системную камеру, затем удержать кнопку.",
        "На эмуляторе вместо кнопки: adb shell input keyevent KEYCODE_ASSIST.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        if (!hasCameraPermission(ctx)) {
            summary("Нет разрешения CAMERA — нажмите «Разрешения»")
            return Status.FAIL
        }
        logImageCaptureHandler()
        val ok = mutableMapOf<String, Int>()
        val tries = mutableMapOf<String, Int>()
        try {
            for ((v, n) in listOf(Variant.INSHEET_COMPAT to 5, Variant.ACTIVITY to 5)) {
                for (i in 1..n) {
                    tries[v] = (tries[v] ?: 0) + 1
                    val e = awaitHumanOpen(v, "${Variant.title(v)}: удержите боковую кнопку ($i/$n)") ?: continue
                    if (e !is CamEvent.Streaming) continue
                    ok[v] = (ok[v] ?: 0) + 1
                    if (i == 1) shootAndConfirm(v)
                    else { CameraBus.activity?.finish(); Sheet.close() }
                }
            }
            tries[Variant.SYSTEM] = 2
            ok[Variant.SYSTEM] = 0
            repeat(2) { i ->
                CameraBus.armed = Variant.SYSTEM
                val e = waitEvent(
                    "6.3: удержите кнопку — откроется системная камера; снимите и подтвердите (${i + 1}/2)",
                    90_000,
                ) { it.variant == Variant.SYSTEM && (it is CamEvent.Captured || it is CamEvent.Failed) }
                CameraBus.armed = null
                when (e) {
                    is CamEvent.Captured -> {
                        metric("system_capture_ms", e.ms)
                        if (ask("Снимок вернулся в лист миниатюрой?") == "Да") ok[Variant.SYSTEM] = ok.getValue(Variant.SYSTEM) + 1
                        Sheet.close()
                    }
                    is CamEvent.Failed -> log("6.3", "ошибка: ${e.msg}")
                    else -> log("6.3", "пропущено")
                }
            }
            for (v in listOf(Variant.INSHEET_COMPAT, Variant.ACTIVITY)) {
                val e = awaitHumanOpen(v, "Заблокируйте экран и удержите кнопку: ${Variant.title(v)} на блокировке", metricSuffix = "locked_prepare_to_preview_ms")
                log("locked_$v", when (e) {
                    is CamEvent.Streaming -> "видоискатель есть, открытие на блокировке=${e.open?.locked}"
                    is CamEvent.Failed -> "ошибка: ${e.msg}"
                    else -> "пропущено"
                })
                if (e is CamEvent.Streaming) log("locked_$v", "человек: " + ask("Что видно: видоискатель поверх блокировки?", listOf("Да", "Просит разблокировать", "Чёрный экран")))
                CameraBus.activity?.finish()
                Sheet.close()
            }
            val busy = awaitHumanOpen(Variant.INSHEET_COMPAT, "Откройте системную камеру, оставьте её на экране и удержите кнопку", metricSuffix = "busy_prepare_to_preview_ms")
            log("busy", when (busy) {
                is CamEvent.Streaming -> "камера дана, хотя занята (система отобрала её у другого приложения)"
                is CamEvent.Failed -> "ошибка: ${busy.msg}"
                else -> "пропущено"
            })
            Sheet.close()
        } finally {
            CameraBus.armed = null
            InSheetCamera.stop()
        }
        val (s, status) = summarize(ok, tries, "prepare_to_preview_ms")
        summary("$s (onPrepareToShowSession → видоискатель; цена самой кнопки — Perfetto)")
        return status
    }

    /** Взвести вариант и ждать, пока человек откроет лист: результат — Streaming, Failed или null (пропуск). */
    private suspend fun ProbeScope.awaitHumanOpen(v: String, prompt: String, metricSuffix: String = "prepare_to_preview_ms"): CamEvent? {
        CameraBus.armed = v
        val e = waitEvent(prompt, 120_000) { it.variant == v && (it is CamEvent.Streaming || it is CamEvent.Failed) }
        CameraBus.armed = null
        when (e) {
            is CamEvent.Streaming -> e.open?.let { o ->
                val ms = e.t - (o.tPrepare ?: o.tShow)
                metric("${key(v)}_$metricSuffix", ms)
                log(v, "#${o.seq} видоискатель через $ms мс от onPrepareToShowSession, процесс новый=${o.freshProcess}, importance=${CameraBus.importanceName(e.importance)}")
            }
            is CamEvent.Failed -> { log(v, "ошибка: ${e.msg}"); error(IllegalStateException(e.msg), v) }
            else -> log(v, "пропущено человеком")
        }
        return e
    }

    private suspend fun ProbeScope.shootAndConfirm(v: String) {
        val e = waitEvent("Нажмите «Снять» (${if (v == Variant.ACTIVITY) "в камере" else "в листе"})", 60_000) {
            it.variant == v && (it is CamEvent.Captured || it is CamEvent.Failed)
        }
        if (e is CamEvent.Captured) {
            metric("${key(v)}_capture_ms", e.ms)
            log(v, "снимок ${e.bytes / 1024} КБ за ${e.ms} мс")
            log(v, "миниатюра в листе: " + ask("Снимок виден в листе миниатюрой?"))
        }
        CameraBus.activity?.finish()
        Sheet.close()
    }

    /** Вопрос с кнопкой «Пропустить» параллельно с ожиданием события камеры. */
    private suspend fun ProbeScope.waitEvent(prompt: String, timeoutMs: Long, match: (CamEvent) -> Boolean): CamEvent? = coroutineScope {
        val ev = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(timeoutMs) { CameraBus.events.first(match) } }
        val skip = async { ask(prompt, listOf("Пропустить")) }
        while (!ev.isCompleted && !skip.isCompleted) delay(100)
        skip.cancel()
        if (ev.isCompleted) ev.await() else { ev.cancel(); null }
    }
}
