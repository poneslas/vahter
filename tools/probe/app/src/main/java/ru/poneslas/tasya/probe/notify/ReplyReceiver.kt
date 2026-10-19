package ru.poneslas.tasya.probe.notify

import android.app.KeyguardManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.TAG
import ru.poneslas.tasya.probe.core.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Ответ из шторки (RemoteInput): приёмник → goAsync (у приёмника ~10 с [док]) → POST на serve.py /reply →
 * новое уведомление с итогом. Каждая попытка пишется в files/reply.jsonl и в [events].
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val t0 = SystemClock.uptimeMillis()
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AnswerNotifier.KEY_REPLY)?.toString() ?: ""
        val pm = context.getSystemService(PowerManager::class.java)
        val screenOn = pm.isInteractive
        val idle = pm.isDeviceIdleMode
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val pending = goAsync()
        App.scope.launch(Dispatchers.IO) {
            var ok = false
            var err: String? = null
            try {
                ok = withTimeoutOrNull(8000) {
                    val c = URL("${Config.server}/reply?text=" + URLEncoder.encode(text, "UTF-8")).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 4000
                    c.readTimeout = 4000
                    c.doOutput = true
                    c.outputStream.use { it.write(ByteArray(0)) }
                    val code = c.responseCode
                    c.inputStream.close()
                    code == 200
                } ?: run { err = "таймаут 8 с"; false }
            } catch (t: Throwable) {
                err = "${t.javaClass.simpleName}: ${t.message}"
            }
            val ms = SystemClock.uptimeMillis() - t0
            AnswerNotifier.post(
                context, AnswerNotifier.ID_REPLY, "Тася получила ответ",
                if (ok) "«$text» дошло до сервера за $ms мс" else "«$text» не ушло: $err",
            )
            val o = JSONObject().put("wall", System.currentTimeMillis()).put("text", text).put("ok", ok).put("ms", ms)
                .put("error", err ?: "").put("screenOn", screenOn).put("idle", idle).put("locked", locked)
            runCatching { File(context.filesDir, "reply.jsonl").appendText(o.toString() + "\n") }
            Log.i(TAG, "reply $o")
            events.tryEmit(o.toString())
            pending.finish()
        }
    }

    companion object {
        val events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    }
}
