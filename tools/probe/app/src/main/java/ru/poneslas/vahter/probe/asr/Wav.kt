package ru.poneslas.vahter.probe.asr

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 WAV → float [-1, 1], моно 16 кГц (каналы усредняются, другая частота — линейная передискретизация). */
fun readWav16k(f: File): FloatArray {
    val bytes = f.readBytes()
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "${f.name}: не WAV" }
    var pos = 12
    var channels = 1
    var rate = 16000
    var bits = 16
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4)
        val size = b.getInt(pos + 4)
        val body = pos + 8
        if (id == "fmt ") {
            channels = b.getShort(body + 2).toInt()
            rate = b.getInt(body + 4)
            bits = b.getShort(body + 14).toInt()
        } else if (id == "data") {
            require(bits == 16) { "${f.name}: нужен PCM16, а тут $bits бит" }
            val frames = minOf(size, bytes.size - body) / (2 * channels)
            val mono = FloatArray(frames) { i ->
                var s = 0f
                for (c in 0 until channels) s += b.getShort(body + (i * channels + c) * 2) / 32768f
                s / channels
            }
            return if (rate == 16000) mono else resample(mono, rate, 16000)
        }
        pos = body + size + (size and 1)
    }
    error("${f.name}: нет блока data")
}

private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
    val n = (x.size.toLong() * to / from).toInt()
    return FloatArray(n) { i ->
        val p = i.toDouble() * from / to
        val j = p.toInt().coerceAtMost(x.size - 1)
        val k = (j + 1).coerceAtMost(x.size - 1)
        val t = (p - j).toFloat()
        x[j] * (1 - t) + x[k] * t
    }
}

/** Пословный WER: расстояние Левенштейна по словам без пунктуации и регистра, ё = е. */
fun wer(ref: String, hyp: String): Double {
    fun words(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.isNotBlank() }
    val r = words(ref)
    val h = words(hyp)
    if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
    var prev = IntArray(h.size + 1) { it }
    for (i in 1..r.size) {
        val cur = IntArray(h.size + 1)
        cur[0] = i
        for (j in 1..h.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
        prev = cur
    }
    return prev[h.size].toDouble() / r.size
}

/** VmRSS и VmHWM (пик за жизнь процесса) из /proc/self/status, МБ. */
fun memMb(): Pair<Double, Double> {
    var rss = 0.0
    var hwm = 0.0
    runCatching {
        File("/proc/self/status").readLines().forEach { l ->
            if (l.startsWith("VmRSS:")) rss = l.split(Regex("\\s+"))[1].toDouble() / 1024
            if (l.startsWith("VmHWM:")) hwm = l.split(Regex("\\s+"))[1].toDouble() / 1024
        }
    }
    return rss to hwm
}
