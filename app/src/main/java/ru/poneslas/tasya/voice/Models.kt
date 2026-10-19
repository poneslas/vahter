package ru.poneslas.tasya.voice

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Модели распознавания (Архитектура 4, проверено пробником П15):
 * GigaAM v3 e2e CTC с пунктуацией (int8) — чистовик; потоковый zipformer-small-ru-vosk (int8) — черновик «караоке»;
 * Silero VAD. Качаются с официальных сборок sherpa-onnx на Hugging Face с докачкой по Range и проверкой sha256.
 */
object ModelFiles {
    private const val GIGA = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16/resolve/main"
    private const val ZIP = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main"

    data class Entry(val path: String, val url: String, val size: Long, val sha256: String)

    val all = listOf(
        Entry("gigaam/model.int8.onnx", "$GIGA/model.int8.onnx", 224_893_661, "d5fea8df94263c285e54b21e5774b707c707192d3bdbeffd7b1eb07fb6743b35"),
        Entry("gigaam/tokens.txt", "$GIGA/tokens.txt", 2007, "142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd"),
        Entry("zipformer/encoder.int8.onnx", "$ZIP/encoder.int8.onnx", 26_214_060, "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d"),
        Entry("zipformer/decoder.onnx", "$ZIP/decoder.onnx", 2_093_080, "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0"),
        Entry("zipformer/joiner.int8.onnx", "$ZIP/joiner.int8.onnx", 259_417, "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5"),
        Entry("zipformer/tokens.txt", "$ZIP/tokens.txt", 6388, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"),
        Entry("silero_vad.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    )
    val totalBytes = all.sumOf { it.size }
}

class Models(private val context: Context, private val scope: CoroutineScope) {
    val dir = File(context.filesDir, "models")
    fun file(path: String) = File(dir, path)

    sealed interface Status {
        data object None : Status
        data class Downloading(val bytes: Long, val total: Long, val waitingNetwork: Boolean = false) : Status {
            val percent get() = (bytes * 100 / total).toInt()
        }
        data object Verifying : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(if (complete()) Status.Ready else Status.None)
    val status: StateFlow<Status> = _status
    private var job: Job? = null

    /** Готовность определяем по размеру файлов — sha256 проверяем при загрузке, а не при каждом запуске (225 МБ). */
    fun complete() = ModelFiles.all.all { file(it.path).length() == it.size }

    fun isMetered(): Boolean = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    fun downloadedBytes() = ModelFiles.all.sumOf { e ->
        val f = file(e.path)
        if (f.length() == e.size) e.size else File(f.path + ".part").length()
    }

    fun start() {
        if (job?.isActive == true || complete()) { if (complete()) _status.value = Status.Ready; return }
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                try {
                    for (e in ModelFiles.all) {
                        val dst = file(e.path)
                        if (dst.length() == e.size) continue
                        dst.parentFile?.mkdirs()
                        val part = File(dst.path + ".part")
                        fetch(e, part)
                        _status.value = Status.Verifying
                        if (sha256(part) != e.sha256) { part.delete(); throw IllegalStateException("Файл ${e.path} повреждён при загрузке") }
                        part.renameTo(dst)
                    }
                    _status.value = Status.Ready
                    return@launch
                } catch (t: Throwable) {
                    if (!isActive) return@launch
                    attempt++
                    _status.value = Status.Downloading(downloadedBytes(), ModelFiles.totalBytes, waitingNetwork = true)
                    if (attempt > 30) { _status.value = Status.Failed(t.message ?: "Не удалось скачать"); return@launch }
                    delay(minOf(30_000L, 2_000L * attempt)) // ждём сеть и продолжаем с того же места
                }
            }
        }
    }

    fun cancel() { job?.cancel(); _status.value = if (complete()) Status.Ready else Status.None }

    fun delete() { cancel(); dir.deleteRecursively(); _status.value = Status.None }

    private suspend fun fetch(e: ModelFiles.Entry, part: File) = withContext(Dispatchers.IO) {
        var url = e.url
        var redirects = 0
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            if (have == e.size) return@withContext
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    url = c.getHeaderField("Location") ?: error("Перенаправление без адреса")
                    if (!url.startsWith("http")) url = URL(URL(e.url), url).toString()
                    if (++redirects > 5) error("Слишком много перенаправлений")
                    continue
                }
                check(code == 200 || code == 206) { "Сервер ответил $code" }
                val ranged = code == 206
                RandomAccessFile(part, "rw").use { raf ->
                    if (ranged) raf.seek(have) else raf.setLength(0)
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    c.inputStream.use { s ->
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 300) { lastReport = now; _status.value = Status.Downloading(downloadedBytes(), ModelFiles.totalBytes) }
                        }
                    }
                }
                return@withContext
            } finally {
                c.disconnect()
            }
        }
    }
}

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { s ->
        val buf = ByteArray(1 shl 20)
        while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
