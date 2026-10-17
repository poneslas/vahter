package ru.poneslas.tasya.probe.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

fun deviceName(d: AudioDeviceInfo?): String {
    d ?: return "нет"
    val type = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "встроенный микрофон"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE-гарнитура"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "проводная гарнитура"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-гарнитура"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_TELEPHONY -> "телефония"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "динамик-трубка"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "динамик"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "проводные наушники"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
        else -> "тип ${d.type}"
    }
    return "$type «${d.productName}» id=${d.id}"
}

fun modeName(m: Int) = when (m) {
    AudioManager.MODE_NORMAL -> "NORMAL"
    AudioManager.MODE_RINGTONE -> "RINGTONE"
    AudioManager.MODE_IN_CALL -> "IN_CALL"
    AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
    AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
    AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
    AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMMUNICATION_REDIRECT"
    else -> "mode $m"
}

/** Итог записи: уровень, заглушён ли клиент, какое устройство реально писало (по маршруту и по конфигурации записи). */
data class RecResult(
    val ok: Boolean,
    val error: String?,
    val rms: Double,
    val zeroFraction: Double,
    val silenced: Boolean?,
    val routed: String,
    val configDevice: String,
    val routeChanges: List<String>,
) {
    fun describe() = if (!ok) "ошибка: $error" else
        "rms=${"%.1f".format(rms)}, нулей ${"%.0f".format(zeroFraction * 100)}%, silenced=$silenced, маршрут: $routed, по конфигурации записи: $configDevice" +
            if (routeChanges.isNotEmpty()) ", смены маршрута: ${routeChanges.joinToString()}" else ""
}

/** Запись 16 кГц моно [ms] мс; [preferred] — setPreferredDevice. Смены маршрута во время записи собираются. */
@SuppressLint("MissingPermission")
suspend fun record(ctx: Context, ms: Long, preferred: AudioDeviceInfo? = null, source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION): RecResult =
    withContext(Dispatchers.IO) {
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        } catch (t: Throwable) {
            return@withContext RecResult(false, "${t.javaClass.simpleName}: ${t.message}", 0.0, 1.0, null, "—", "—", emptyList())
        }
        val changes = mutableListOf<String>()
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext RecResult(false, "AudioRecord state=${rec.state}", 0.0, 1.0, null, "—", "—", emptyList())
            }
            preferred?.let { rec.preferredDevice = it }
            val t0 = SystemClock.uptimeMillis()
            rec.addOnRoutingChangedListener({ r -> changes += "+${SystemClock.uptimeMillis() - t0} мс → ${deviceName(r.routedDevice)}" }, null)
            try {
                rec.startRecording()
            } catch (t: Throwable) {
                return@withContext RecResult(false, "startRecording: ${t.javaClass.simpleName}: ${t.message}", 0.0, 1.0, null, "—", "—", emptyList())
            }
            val am = ctx.getSystemService(AudioManager::class.java)
            val buf = ShortArray(1600)
            var sum = 0.0
            var zeros = 0L
            var total = 0L
            var silenced: Boolean? = null
            var cfgDevice = "—"
            while (SystemClock.uptimeMillis() < t0 + ms) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) return@withContext RecResult(false, "read вернул $n", 0.0, 1.0, silenced, deviceName(rec.routedDevice), cfgDevice, changes)
                for (i in 0 until n) {
                    val v = buf[i].toDouble()
                    sum += v * v
                    if (buf[i].toInt() == 0) zeros++
                }
                total += n
                if (silenced == null && SystemClock.uptimeMillis() - t0 > 500) {
                    val cfg = am.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == rec.audioSessionId }
                    silenced = cfg?.isClientSilenced
                    cfgDevice = deviceName(cfg?.audioDevice)
                }
            }
            val routed = deviceName(rec.routedDevice)
            rec.stop()
            RecResult(true, null, if (total > 0) sqrt(sum / total) else 0.0, if (total > 0) zeros.toDouble() / total else 1.0, silenced, routed, cfgDevice, changes)
        } finally {
            rec.release()
        }
    }
