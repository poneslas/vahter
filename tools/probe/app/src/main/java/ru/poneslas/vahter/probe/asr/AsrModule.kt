package ru.poneslas.vahter.probe.asr

import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.Module
import ru.poneslas.vahter.probe.core.Probe
import ru.poneslas.vahter.probe.core.ProbeScope
import ru.poneslas.vahter.probe.core.Status
import ru.poneslas.vahter.probe.core.fmt
import ru.poneslas.vahter.probe.core.median
import java.io.File
import kotlin.coroutines.coroutineContext

object AsrModule : Module {
    override val probes = listOf(AsrProbe)
}

/**
 * П15: sherpa-onnx 1.13.8 (AAR, arm64-v8a) + GigaAM v3 e2e_ctc int8 (чистовик), стриминговый
 * zipformer-small-ru-vosk int8 (черновик), Silero VAD. Всё на CPU. Модели — в files/models
 * (scripts/asr-fetch.sh --models, затем scripts/asr-push.sh или загрузка с Config.server/models).
 */
object AsrProbe : Probe(
    "П15", "Локальное распознавание: загрузка, холодный старт, задержка",
    "GigaAM грузится ≤ 2 с, чистовик ≤ 0,5 с на 5 с речи, черновик отстаёт ≤ 0,5 с, за долгий прогон нет троттлинга выше MODERATE.",
    needsHuman = false,
    steps = listOf(
        "На Mac: scripts/asr-fetch.sh --models — модели и WAV в probe/serve/models (~255 МБ).",
        "Модели на телефон: scripts/asr-push.sh [--minutes N] (adb push + run-as), или пробник сам скачает с «Тестового сервера»/models.",
        "Запустить П15. Холодный старт честный только после force-stop: «Убить процесс» в листе или adb shell am force-stop.",
        "Долгий прогон — 10 минут по умолчанию (asr-push.sh --minutes N меняет); телефон на столе, экран можно погасить.",
        "Живые фразы в листе (П4) и оценка качества черновика на слух — заметкой, пробник этого не делает.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val m = Models(ctx)
        val processAgeMs = SystemClock.uptimeMillis() - App.createdAt
        log("process", "возраст процесса $processAgeMs мс — холодный старт честный, если процесс только что поднят")

        if (m.missing().isNotEmpty()) {
            log("models", "нет: ${m.missing().joinToString { it.relativeTo(m.dir).path }} — пробую скачать с сервера")
            download(m)
        }
        val missing = m.missing()
        if (missing.isNotEmpty()) {
            summary("Моделей нет (${missing.joinToString { it.relativeTo(m.dir).path }}). Положите модели: scripts/asr-fetch.sh --models && scripts/asr-push.sh")
            return Status.NOT_RUN
        }
        val bad = verify(m)
        if (bad.isNotEmpty()) {
            summary("sha256 не совпал: ${bad.joinToString()} — перезалейте scripts/asr-push.sh")
            return Status.FAIL
        }
        log("models", "GigaAM ${m.gigaModel.length() / 1_000_000} МБ, zipformer ${m.zEncoder.length() / 1_000_000} МБ, VAD ${m.vad.length() / 1000} КБ")

        val wavs = m.wavs().map { it to readWav16k(it) }
        if (wavs.isEmpty()) log("wavs", "в models/wavs нет WAV — RTF и стриминг пропущены")
        wavs.forEach { (f, a) -> log("wav", "${f.name}: ${fmt(a.size / 16000.0)} с${if (f.name.startsWith("synth")) " (синтетика say, не голос S24)" else ""}") }

        // 1. Холодное создание GigaAM на 4 потоках — первая загрузка в этом процессе.
        val (rss0, hwm0) = memMb()
        var t = SystemClock.uptimeMillis()
        var giga = withContext(Dispatchers.Default) { offline(m, 4) }
        val gigaColdMs = SystemClock.uptimeMillis() - t
        val (rss1, hwm1) = memMb()
        metric("gigaam_load_cold_ms", gigaColdMs)
        metric("gigaam_rss_delta_mb", rss1 - rss0)
        metric("peak_rss_mb_after_gigaam", hwm1)
        log("gigaam", "холодно $gigaColdMs мс, RSS +${fmt(rss1 - rss0)} МБ, пик ${fmt(hwm1)} МБ (до ${fmt(hwm0)})")

        // 2. Чистовик: RTF и «конец фразы → текст» на 4, 2, 1 потоках.
        var finalOk = true
        for (threads in listOf(4, 2, 1)) {
            coroutineContext.ensureActive()
            if (threads != 4) {
                giga.release()
                t = SystemClock.uptimeMillis()
                giga = withContext(Dispatchers.Default) { offline(m, threads) }
                metric("gigaam_load_warm_ms_t$threads", SystemClock.uptimeMillis() - t)
            }
            for ((f, audio) in wavs) {
                val sec = audio.size / 16000.0
                val (text, ms) = withContext(Dispatchers.Default) { decodeOffline(giga, audio) }
                metric("final_latency_ms_t$threads", ms)
                metric("final_rtf_t$threads", ms / 1000.0 / sec)
                val ref = File(f.path.removeSuffix(".wav") + ".txt").takeIf { it.exists() }?.readText()?.trim()
                val w = ref?.let { wer(it, text) }
                if (w != null && threads == 4) metric("wer_gigaam", w)
                log("final", "t=$threads ${f.name} ${fmt(sec)} с → $ms мс${w?.let { ", WER ${fmt(it)}" } ?: ""}: $text")
                // Цель на 5 с речи: ≤ 0,5 с. Для других длин — пересчёт по RTF.
                if (threads == 4 && ms / sec * 5 > 500) finalOk = false
            }
        }
        giga.release()

        // 3. Потоковый черновик: холодное создание, затем подача кусками по 100 мс в реальном темпе.
        t = SystemClock.uptimeMillis()
        val online = withContext(Dispatchers.Default) { online(m, 2) }
        val zColdMs = SystemClock.uptimeMillis() - t
        metric("zipformer_load_ms", zColdMs)
        log("zipformer", "создание $zColdMs мс, пик RSS ${fmt(memMb().second)} МБ")
        var draftOk = true
        for ((f, audio) in wavs) {
            coroutineContext.ensureActive()
            val r = stream(online, audio)
            r.wordLagMs.forEach { metric("draft_token_lag_ms", it) }
            r.firstTokenMs?.let { metric("draft_first_token_ms", it) }
            metric("draft_end_lag_ms", r.endLagMs)
            if (r.endLagMs > 500) draftOk = false
            log("draft", "${f.name}: первый токен ${r.firstTokenMs} мс от начала, конец +${r.endLagMs} мс, медиана задержки токена ${if (r.wordLagMs.isNotEmpty()) fmt(median(r.wordLagMs)) else "—"} мс: ${r.text}")
        }
        online.release()

        // 4. VAD: создание и цена секунды звука.
        t = SystemClock.uptimeMillis()
        val vad = withContext(Dispatchers.Default) {
            Vad(null, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(m.vad.path, threshold = 0.5f, minSilenceDuration = 0.25f, minSpeechDuration = 0.25f, windowSize = 512)))
        }
        metric("vad_load_ms", SystemClock.uptimeMillis() - t)
        wavs.firstOrNull()?.let { (f, audio) ->
            val (segments, ms) = withContext(Dispatchers.Default) {
                val t0 = SystemClock.uptimeMillis()
                var i = 0
                var n = 0
                while (i + 512 <= audio.size) {
                    vad.acceptWaveform(audio.copyOfRange(i, i + 512))
                    while (!vad.empty()) { n++; vad.pop() }
                    i += 512
                }
                vad.flush()
                while (!vad.empty()) { n++; vad.pop() }
                n to SystemClock.uptimeMillis() - t0
            }
            metric("vad_ms_per_audio_s", ms / (audio.size / 16000.0))
            log("vad", "${f.name}: сегментов $segments, $ms мс")
        }
        vad.release()

        // 5. Долгий прогон: чистовик по кругу, раз в минуту — RTF, тепло, батарея.
        val minutes = m.longRunMinutes()
        val thermalOk = if (wavs.isNotEmpty() && minutes > 0) longRun(m, wavs.map { it.second }, minutes) else {
            log("long", "пропущен: ${if (wavs.isEmpty()) "нет WAV" else "длительность 0"}")
            true
        }

        val loadOk = gigaColdMs <= 2000
        val s = buildString {
            append("GigaAM холодно $gigaColdMs мс (цель ≤ 2000, наша)")
            if (wavs.isNotEmpty()) {
                append("; чистовик ${if (finalOk) "в цели" else "медленнее цели"} (≤ 0,5 с на 5 с, 4 потока)")
                append("; черновик ${if (draftOk) "успевает" else "отстаёт"} (конец ≤ 0,5 с)")
                append("; тепло ${if (thermalOk) "не выше MODERATE" else "выше MODERATE"} за ${fmt(minutes)} мин")
                if (wavs.any { it.first.name.startsWith("synth") }) append("; часть WAV — синтетика say")
            } else append("; WAV нет — скорость распознавания не мерили")
        }
        summary(s)
        return when {
            !loadOk || !finalOk || !draftOk || !thermalOk -> Status.FAIL
            wavs.isEmpty() -> Status.PARTIAL
            else -> Status.PASS
        }
    }

    private fun offline(m: Models, threads: Int) = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            // Как в примере sherpa-onnx для GigaAM: признаки (64 мел) sherpa берёт из метаданных модели.
            featConfig = FeatureConfig(sampleRate = 16000),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = m.gigaModel.path),
                tokens = m.gigaTokens.path,
                numThreads = threads,
                provider = "cpu",
            ),
        ),
    )

    private fun online(m: Models, threads: Int) = OnlineRecognizer(
        null,
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = m.zEncoder.path, decoder = m.zDecoder.path, joiner = m.zJoiner.path),
                tokens = m.zTokens.path,
                numThreads = threads,
                provider = "cpu",
            ),
            enableEndpoint = false,
        ),
    )

    private fun decodeOffline(r: OfflineRecognizer, audio: FloatArray): Pair<String, Long> {
        val t = SystemClock.uptimeMillis()
        val s = r.createStream()
        s.acceptWaveform(audio, 16000)
        r.decode(s)
        val text = r.getResult(s).text
        s.release()
        return text to SystemClock.uptimeMillis() - t
    }

    private class StreamResult(val text: String, val firstTokenMs: Long?, val wordLagMs: List<Double>, val endLagMs: Long)

    /**
     * Подаёт звук кусками по 100 мс с темпом реального времени. Задержка токена = время его появления
     * от начала подачи минус его метка времени в звуке. Конец = от последнего куска до готового текста.
     */
    private suspend fun stream(r: OnlineRecognizer, audio: FloatArray): StreamResult = withContext(Dispatchers.Default) {
        val s = r.createStream("")
        val chunk = 1600
        val t0 = SystemClock.uptimeMillis()
        var seen = 0
        var first: Long? = null
        val lags = mutableListOf<Double>()
        var i = 0
        while (i < audio.size) {
            val end = minOf(i + chunk, audio.size)
            // Реальный темп: кусок [i, end) «записан» к моменту end/16 мс.
            val due = t0 + end / 16
            val wait = due - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            s.acceptWaveform(audio.copyOfRange(i, end), 16000)
            while (r.isReady(s)) r.decode(s)
            val res = r.getResult(s)
            val now = SystemClock.uptimeMillis() - t0
            if (res.tokens.size > seen) {
                if (first == null) first = now
                for (k in seen until res.tokens.size) {
                    val ts = res.timestamps.getOrNull(k) ?: continue
                    lags += now - ts * 1000.0
                }
                seen = res.tokens.size
            }
            i = end
        }
        val tLast = SystemClock.uptimeMillis()
        s.inputFinished()
        while (r.isReady(s)) r.decode(s)
        val text = r.getResult(s).text
        val endLag = SystemClock.uptimeMillis() - tLast
        s.release()
        StreamResult(text, first, lags, endLag)
    }

    /** true — тепловой статус не поднимался выше MODERATE. */
    private suspend fun ProbeScope.longRun(m: Models, audios: List<FloatArray>, minutes: Double): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val giga = withContext(Dispatchers.Default) { offline(m, 4) }
        val battery0 = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val tEnd = SystemClock.uptimeMillis() + (minutes * 60_000).toLong()
        var maxThermal = pm.currentThermalStatus
        var minute = 0
        var firstRtf: Double? = null
        var lastRtf = 0.0
        log("long", "старт: ${fmt(minutes)} мин, тепло ${pm.currentThermalStatus}, запас ${fmt(pm.getThermalHeadroom(10).toDouble())}, батарея $battery0 %")
        try {
            while (SystemClock.uptimeMillis() < tEnd) {
                coroutineContext.ensureActive()
                val tMin = SystemClock.uptimeMillis()
                var audioSec = 0.0
                var busyMs = 0L
                var k = 0
                while (SystemClock.uptimeMillis() - tMin < 60_000 && SystemClock.uptimeMillis() < tEnd) {
                    val a = audios[k++ % audios.size]
                    busyMs += withContext(Dispatchers.Default) { decodeOffline(giga, a).second }
                    audioSec += a.size / 16000.0
                }
                minute++
                val rtf = busyMs / 1000.0 / audioSec
                if (firstRtf == null) firstRtf = rtf
                lastRtf = rtf
                val thermal = pm.currentThermalStatus
                maxThermal = maxOf(maxThermal, thermal)
                metric("long_rtf_by_minute", rtf)
                metric("long_thermal_by_minute", thermal)
                metric("long_headroom_by_minute", pm.getThermalHeadroom(10).toDouble())
                log("long", "минута $minute: RTF ${fmt(rtf)}, тепло $thermal, запас ${fmt(pm.getThermalHeadroom(10).toDouble())}, батарея ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} %")
            }
        } finally {
            giga.release()
        }
        val battery1 = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        metric("long_battery_drop_pct", battery0 - battery1)
        firstRtf?.let { metric("long_rtf_slowdown", lastRtf / it) }
        metric("long_thermal_max", maxThermal)
        log("long", "итог: тепло max $maxThermal, RTF ${firstRtf?.let { fmt(it) }} → ${fmt(lastRtf)}, батарея −${battery0 - battery1} %")
        return maxThermal <= PowerManager.THERMAL_STATUS_MODERATE
    }
}
