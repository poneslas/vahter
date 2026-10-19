package ru.poneslas.tasya.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Распознавание речи на телефоне (Архитектура 4): потоковый черновик + чистовик GigaAM.
 */
interface Recognizer {
    /** Черновик «караоке», пока человек говорит. */
    val partial: StateFlow<String>
    /** Уровень звука 0..1 — для эквалайзера. */
    val level: StateFlow<Float>
    fun start()
    /** Остановить и вернуть чистовик. */
    suspend fun stop(): String
    fun cancel()
    val available: Boolean
}

class NoopRecognizer : Recognizer {
    override val partial = MutableStateFlow("")
    override val level = MutableStateFlow(0f)
    override val available = false
    override fun start() { partial.value = "" }
    override suspend fun stop(): String = ""
    override fun cancel() { partial.value = "" }
}

/**
 * sherpa-onnx: GigaAM v3 (4 потока) — чистовик по всей записи; zipformer (2 потока) — черновик по кускам 100 мс.
 * Модели грузятся один раз (прогрев) и живут, пока жив процесс. Микрофон: AudioRecord создаётся заранее
 * ([prepare] — из onPrepareToShowSession), запускается только на запись: запущенный — горит индикатор (S24).
 */
class SherpaRecognizer(private val context: Context, private val models: Models, private val scope: CoroutineScope) : Recognizer {
    override val partial = MutableStateFlow("")
    override val level = MutableStateFlow(0f)
    override val available get() = offline != null

    private var offline: OfflineRecognizer? = null
    private var online: OnlineRecognizer? = null
    private val loadLock = Mutex()
    private var record: AudioRecord? = null
    private var job: Job? = null
    private var stream: OnlineStream? = null
    private val samples = ArrayList<FloatArray>()

    /** Прогрев: загрузить модели и прогнать секунду тишины, чтобы первый ответ был быстрым. */
    suspend fun warmUp() = loadLock.withLock {
        if (offline != null || !models.complete()) return@withLock
        withContext(Dispatchers.Default) {
            val off = OfflineRecognizer(null, OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000),
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = models.file("gigaam/model.int8.onnx").path),
                    tokens = models.file("gigaam/tokens.txt").path, numThreads = 4, provider = "cpu",
                ),
            ))
            val on = OnlineRecognizer(null, OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = models.file("zipformer/encoder.int8.onnx").path,
                        decoder = models.file("zipformer/decoder.onnx").path,
                        joiner = models.file("zipformer/joiner.int8.onnx").path,
                    ),
                    tokens = models.file("zipformer/tokens.txt").path, numThreads = 2, provider = "cpu",
                ),
                enableEndpoint = false,
            ))
            val s = off.createStream(); s.acceptWaveform(FloatArray(16000), 16000); off.decode(s); s.release()
            offline = off; online = on
        }
    }

    private fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Создать AudioRecord заранее (не запуская) — ранний старт без индикатора. */
    @SuppressLint("MissingPermission")
    fun prepare() {
        if (record != null || !hasMic()) return
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }

    override fun start() {
        if (job?.isActive == true) return
        partial.value = ""
        prepare()
        val rec = record ?: return
        samples.clear()
        stream = online?.createStream("")
        runCatching { rec.startRecording() }.onFailure { return }
        job = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(1600)
            while (isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val f = FloatArray(n) { buf[it] / 32768f }
                synchronized(samples) { samples += f }
                var sum = 0.0; for (x in f) sum += x * x
                level.value = (sqrt(sum / n) * 6).toFloat().coerceIn(0f, 1f)
                val on = online; val st = stream
                if (on != null && st != null) {
                    st.acceptWaveform(f, 16000)
                    while (on.isReady(st)) on.decode(st)
                    val text = on.getResult(st).text.trim().lowercase()
                    if (text.isNotEmpty()) partial.value = text
                }
            }
        }
    }

    private fun stopMic() {
        job?.cancel(); job = null
        runCatching { record?.stop() }
        level.value = 0f
        stream?.release(); stream = null
    }

    override suspend fun stop(): String {
        stopMic()
        val all = synchronized(samples) { val n = samples.sumOf { it.size }; FloatArray(n).also { out -> var i = 0; for (a in samples) { a.copyInto(out, i); i += a.size } } }
        samples.clear()
        partial.value = ""
        if (all.size < 16000 / 4) return ""
        val off = offline ?: return ""
        return withContext(Dispatchers.Default) {
            val s = off.createStream()
            // Хвост тишины помогает CTC закончить последнее слово.
            s.acceptWaveform(all + FloatArray(4000), 16000)
            off.decode(s)
            val text = off.getResult(s).text.trim()
            s.release()
            text
        }
    }

    /** Для проверки на файлах (отладочная сборка). */
    suspend fun decodeForTest(audio: FloatArray): String {
        val off = offline ?: return "(модель не загружена)"
        return withContext(Dispatchers.Default) {
            val s = off.createStream(); s.acceptWaveform(audio + FloatArray(4000), 16000); off.decode(s)
            off.getResult(s).text.trim().also { s.release() }
        }
    }

    override fun cancel() {
        stopMic()
        samples.clear()
        partial.value = ""
    }
}
