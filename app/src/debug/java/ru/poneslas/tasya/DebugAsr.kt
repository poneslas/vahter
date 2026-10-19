package ru.poneslas.tasya

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Только отладочная сборка: прогнать WAV (16 кГц, моно, PCM16) через распознавание приложения.
 *   adb shell am broadcast -n ru.poneslas.tasya.dev/ru.poneslas.tasya.DebugAsr --es wav files/x.wav
 * Результат — в logcat с тегом TasyaAsr.
 */
class DebugAsr : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = App.instance
        val path = intent.getStringExtra("wav") ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                val bytes = File(context.filesDir.parentFile, path).readBytes()
                val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val audio = ShortArray(pcm.remaining()) { pcm.get(it) }
                val t = System.currentTimeMillis()
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.voice.transcribeForTest(audio) }
                Log.i("TasyaAsr", "${audio.size / 16000.0} с → ${System.currentTimeMillis() - t} мс: $text")
            } catch (e: Throwable) {
                Log.e("TasyaAsr", "ошибка", e)
            } finally {
                pending.finish()
            }
        }
    }
}
