package ru.poneslas.tasya.probe.push

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import ru.poneslas.tasya.probe.TAG
import java.io.File

/**
 * Приёмник UnifiedPush (дистрибьютор — ntfy). Тело пуша — JSON {"t": <мс эпохи на Mac>, "seq": n};
 * задержка = время приёма − t. Часы Mac и телефона синхронизированы по NTP [?], погрешность — их рассинхрон.
 */
class ProbePushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "push endpoint ${endpoint.url} temporary=${endpoint.temporary}")
        PushLog.saveEndpoint(this, endpoint.url)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val now = System.currentTimeMillis()
        val body = String(message.content)
        val t = runCatching { JSONObject(body).getLong("t") }.getOrNull()
        val pm = getSystemService(PowerManager::class.java)
        val rec = PushLog.Received(now, t?.let { now - it }, message.decrypted, pm.isDeviceIdleMode, pm.isInteractive, body.take(200))
        Log.i(TAG, "push message $rec")
        PushLog.add(this, rec)
        // Ответ пришёл при закрытом листе — уведомления, как в продукте.
        Notifications.postAll(this, "Пуш ${t?.let { "за ${now - it} мс" } ?: ""}: $body".take(120))
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "push registration failed $reason")
        PushLog.failure.value = reason.name
    }

    override fun onUnregistered(instance: String) {
        Log.i(TAG, "push unregistered")
        PushLog.saveEndpoint(this, "")
    }
}

/** Журнал пушей: переживает процесс (пуш может поднять его с нуля). */
object PushLog {
    data class Received(val wall: Long, val delayMs: Long?, val decrypted: Boolean, val idle: Boolean, val screenOn: Boolean, val body: String)

    val endpoint = MutableStateFlow<String?>(null)
    val failure = MutableStateFlow<String?>(null)
    val received = MutableSharedFlow<Received>(extraBufferCapacity = 32)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("push", Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(ctx.filesDir, "push.jsonl")

    fun saveEndpoint(ctx: Context, url: String) {
        prefs(ctx).edit().putString("endpoint", url).apply()
        endpoint.value = url.ifEmpty { null }
    }

    fun savedEndpoint(ctx: Context) = prefs(ctx).getString("endpoint", null)?.ifEmpty { null }

    fun add(ctx: Context, r: Received) {
        val o = JSONObject().put("wall", r.wall).put("delay", r.delayMs ?: -1).put("decrypted", r.decrypted)
            .put("idle", r.idle).put("screenOn", r.screenOn).put("body", r.body)
        runCatching { file(ctx).appendText(o.toString() + "\n") }
        received.tryEmit(r)
    }

    fun since(ctx: Context, wall: Long): List<Received> = runCatching {
        file(ctx).readLines().mapNotNull { l ->
            runCatching {
                val o = JSONObject(l)
                Received(o.getLong("wall"), o.getLong("delay").takeIf { it >= 0 }, o.getBoolean("decrypted"), o.getBoolean("idle"), o.getBoolean("screenOn"), o.getString("body"))
            }.getOrNull()
        }.filter { it.wall >= wall }
    }.getOrDefault(emptyList())
}
