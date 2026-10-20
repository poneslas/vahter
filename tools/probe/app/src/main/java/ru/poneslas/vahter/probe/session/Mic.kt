package ru.poneslas.vahter.probe.session

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Итог одной записи: удалось ли начать, первый буфер, уровень сигнала, заглушён ли клиент системой. */
data class MicStats(
    val ok: Boolean,
    val error: String?,
    val tStart: Long,
    val tFirstBuffer: Long?,
    val rms: Double,
    val zeroFraction: Double,
    val silenced: Boolean?,
    val samples: Int,
    val recordingState: Int,
    /** Первые 300 мс звука (4800 сэмплов): не пустые ли — важно для «сразу пишет». */
    val first300Rms: Double = 0.0,
    val first300Zeros: Double = 1.0,
) {
    fun describe() = if (!ok) "ошибка: $error" else
        "rms=${"%.1f".format(rms)} нулей=${"%.1f".format(zeroFraction * 100)}% сэмплов=$samples " +
            "первый буфер через ${tFirstBuffer?.let { it - tStart } ?: "—"} мс, первые 300 мс: rms=${"%.1f".format(first300Rms)} нулей=${"%.0f".format(first300Zeros * 100)}%, " +
            "silenced=$silenced, recordingState=$recordingState"
}

/** Запись 16 кГц моно [ms] миллисекунд. Блокирующее чтение — на IO. */
@SuppressLint("MissingPermission")
suspend fun recordFor(ctx: Context, ms: Long, pre: AudioRecord? = null): MicStats = withContext(Dispatchers.IO) {
    val tStart = SystemClock.uptimeMillis()
    val rec = pre ?: try {
        newRecord()
    } catch (t: Throwable) {
        return@withContext MicStats(false, "${t.javaClass.simpleName}: ${t.message}", tStart, null, 0.0, 1.0, null, 0, -1)
    }
    try {
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            return@withContext MicStats(false, "AudioRecord не инициализирован (state=${rec.state})", tStart, null, 0.0, 1.0, null, 0, -1)
        }
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            return@withContext MicStats(false, "startRecording: ${t.javaClass.simpleName}: ${t.message}", tStart, null, 0.0, 1.0, null, 0, rec.recordingState)
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        val buf = ShortArray(1600)
        var first: Long? = null
        var sum = 0.0
        var zeros = 0L
        var total = 0L
        var silenced: Boolean? = null
        var sum300 = 0.0
        var zeros300 = 0L
        var total300 = 0L
        val end = tStart + ms
        while (SystemClock.uptimeMillis() < end) {
            val n = rec.read(buf, 0, buf.size)
            if (n < 0) return@withContext MicStats(false, "read вернул $n", tStart, first, 0.0, 1.0, silenced, total.toInt(), rec.recordingState)
            if (n > 0 && first == null) first = SystemClock.uptimeMillis()
            for (i in 0 until n) {
                val v = buf[i].toDouble()
                sum += v * v
                if (buf[i].toInt() == 0) zeros++
                if (total + i < 4800) {
                    sum300 += v * v
                    if (buf[i].toInt() == 0) zeros300++
                    total300++
                }
            }
            total += n
            if (silenced == null && SystemClock.uptimeMillis() - tStart > 500) {
                silenced = am.activeRecordingConfigurations
                    .firstOrNull { it.clientAudioSessionId == rec.audioSessionId }?.isClientSilenced
            }
        }
        val state = rec.recordingState
        rec.stop()
        MicStats(
            true, null, tStart, first, if (total > 0) sqrt(sum / total) else 0.0, if (total > 0) zeros.toDouble() / total else 1.0,
            silenced, total.toInt(), state,
            if (total300 > 0) sqrt(sum300 / total300) else 0.0, if (total300 > 0) zeros300.toDouble() / total300 else 1.0,
        )
    } finally {
        rec.release()
    }
}

/**
 * AudioRecord 16 кГц моно, созданный, но не запущенный: индикатор микрофона не горит — он загорается
 * на startRecording [AOSP: AppOps OP_RECORD_AUDIO start]. Так «прогретый» микрофон держится без нарушения приватности.
 */
@SuppressLint("MissingPermission")
fun newRecord(): AudioRecord {
    val rate = 16_000
    val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    return AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
}

fun importance(): Int = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance

fun importanceName(i: Int) = when (i) {
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
    else -> i.toString()
} + "($i)"
