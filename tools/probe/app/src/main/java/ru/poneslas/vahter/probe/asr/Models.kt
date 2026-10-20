package ru.poneslas.vahter.probe.asr

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.poneslas.vahter.probe.core.Config
import ru.poneslas.vahter.probe.core.ProbeScope
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Модели П15 в files/models. Раскладка повторяет probe/serve/models (scripts/asr-fetch.sh --models):
 * gigaam/{model.int8.onnx,tokens.txt}, zipformer/{encoder.int8.onnx,decoder.onnx,joiner.int8.onnx,tokens.txt},
 * silero_vad.onnx, wavs/(*.wav + *.txt эталон), manifest.json (имя, размер, sha256).
 */
class Models(ctx: Context) {
    val dir = File(ctx.filesDir, "models")
    val gigaModel = File(dir, "gigaam/model.int8.onnx")
    val gigaTokens = File(dir, "gigaam/tokens.txt")
    val zEncoder = File(dir, "zipformer/encoder.int8.onnx")
    val zDecoder = File(dir, "zipformer/decoder.onnx")
    val zJoiner = File(dir, "zipformer/joiner.int8.onnx")
    val zTokens = File(dir, "zipformer/tokens.txt")
    val vad = File(dir, "silero_vad.onnx")
    val manifest = File(dir, "manifest.json")

    val required get() = listOf(gigaModel, gigaTokens, zEncoder, zDecoder, zJoiner, zTokens, vad)
    fun missing() = required.filter { !it.exists() }
    fun wavs(): List<File> = File(dir, "wavs").listFiles { f -> f.extension == "wav" }?.sortedBy { it.name } ?: emptyList()

    /** Длительность долгого прогона, минут: models/settings.properties → long_run_minutes (asr-push.sh --minutes N). */
    fun longRunMinutes(): Double = runCatching {
        File(dir, "settings.properties").readLines()
            .first { it.startsWith("long_run_minutes=") }.substringAfter('=').trim().toDouble()
    }.getOrDefault(10.0)

    data class Entry(val name: String, val size: Long, val sha256: String)

    fun parseManifest(text: String): List<Entry> {
        val a = JSONObject(text).getJSONArray("files")
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.getString("name"), o.getLong("size"), o.getString("sha256")) } }
    }
}

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { s ->
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Проверка sha256 по manifest.json. Возвращает несовпавшие файлы. */
suspend fun ProbeScope.verify(m: Models): List<String> = withContext(Dispatchers.IO) {
    if (!m.manifest.exists()) {
        log("sha256", "manifest.json нет — проверка пропущена")
        return@withContext emptyList()
    }
    val bad = mutableListOf<String>()
    m.parseManifest(m.manifest.readText()).forEach { e ->
        val f = File(m.dir, e.name)
        if (!f.exists()) return@forEach
        val t = SystemClock.uptimeMillis()
        val h = sha256(f)
        val ms = SystemClock.uptimeMillis() - t
        if (h != e.sha256) bad += e.name
        log("sha256", "${e.name}: ${if (h == e.sha256) "совпал" else "НЕ совпал"} за $ms мс")
    }
    bad
}

/**
 * Загрузка моделей с тестового сервера: Config.server/models/manifest.json → файлы в *.part с докачкой по Range,
 * sha256, атомарное переименование. Первый большой файл обрываем на 50 % и докачиваем — проверка Range.
 * false — сервер недоступен или нет manifest.json.
 */
suspend fun ProbeScope.download(m: Models): Boolean = withContext(Dispatchers.IO) {
    val base = Config.server + "/models"
    val manifestText = runCatching { httpText("$base/manifest.json") }.getOrElse {
        log("download", "manifest.json с $base недоступен: ${it.javaClass.simpleName} ${it.message}")
        return@withContext false
    }
    val entries = m.parseManifest(manifestText)
    m.dir.mkdirs()
    var breakDone = false
    val t0 = SystemClock.uptimeMillis()
    var total = 0L
    for (e in entries) {
        val dst = File(m.dir, e.name)
        if (dst.exists() && dst.length() == e.size && sha256(dst) == e.sha256) continue
        dst.parentFile?.mkdirs()
        val part = File(dst.path + ".part")
        val tFile = SystemClock.uptimeMillis()
        if (!breakDone && e.size > 10_000_000) {
            breakDone = true
            fetch("$base/${e.name}", part, stopAt = e.size / 2)
            log("download", "${e.name}: оборвали на ${part.length()} из ${e.size}")
            val resumed = fetch("$base/${e.name}", part, stopAt = null)
            log("download", "${e.name}: докачка ${if (resumed) "по Range (206)" else "с нуля — сервер не отдаёт Range"}")
            metric("download_range_supported", if (resumed) 1 else 0)
        } else {
            fetch("$base/${e.name}", part, stopAt = null)
        }
        if (sha256(part) != e.sha256) {
            log("download", "${e.name}: sha256 не совпал, файл удалён")
            part.delete()
            continue
        }
        part.renameTo(dst)
        total += e.size
        log("download", "${e.name}: ${e.size / 1_000_000} МБ за ${SystemClock.uptimeMillis() - tFile} мс")
    }
    m.manifest.writeText(manifestText)
    val ms = SystemClock.uptimeMillis() - t0
    if (total > 0) {
        metric("download_total_ms", ms)
        metric("download_mbit_s", total * 8.0 / 1e6 / (ms / 1000.0))
    }
    true
}

private fun httpText(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 5000
    c.readTimeout = 10000
    try {
        check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        return c.inputStream.bufferedReader().readText()
    } finally {
        c.disconnect()
    }
}

/** Качает в [part] с позиции его длины. true — сервер ответил 206 на Range. [stopAt] — оборвать на этом байте. */
private fun fetch(url: String, part: File, stopAt: Long?): Boolean {
    val have = if (part.exists()) part.length() else 0L
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 5000
    c.readTimeout = 30000
    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
    try {
        val code = c.responseCode
        check(code == 200 || code == 206) { "HTTP $code для $url" }
        val ranged = code == 206
        RandomAccessFile(part, "rw").use { raf ->
            if (ranged) raf.seek(have) else raf.setLength(0)
            var pos = if (ranged) have else 0L
            val buf = ByteArray(1 shl 16)
            c.inputStream.use { s ->
                while (stopAt == null || pos < stopAt) {
                    val n = s.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    pos += n
                }
            }
        }
        return ranged
    } finally {
        c.disconnect()
    }
}
