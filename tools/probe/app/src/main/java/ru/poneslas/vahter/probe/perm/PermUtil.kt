package ru.poneslas.vahter.probe.perm

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.core.ProbeScope
import java.io.File
import kotlin.coroutines.resume

/**
 * Запустить батут из листа (startAssistantActivity; лист открывается, если закрыт) или из приложения.
 * Возвращает ошибку запуска или null.
 */
suspend fun ProbeScope.launchTrampoline(fromSheet: Boolean, fill: Intent.() -> Unit): String? {
    val i = Intent(ctx, PermTrampolineActivity::class.java).apply(fill)
    if (!fromSheet) {
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i) }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
    }
    if (!Sheet.visible.value && Sheet.open() == null) return "лист не открылся"
    val s = Sheet.session.value ?: return "нет сессии"
    return runCatching {
        s.startAssistantActivity(i, ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle())
    }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
}

/** Запросить разрешения через батут. Первый элемент null — ответа не дождались за [timeoutMs]. */
suspend fun ProbeScope.requestViaTrampoline(perms: Array<String>, fromSheet: Boolean, timeoutMs: Long = 120_000): Pair<PermResult?, String?> {
    val d = CompletableDeferred<PermResult>()
    PermBus.perm = d
    val err = launchTrampoline(fromSheet) { putExtra(PermTrampolineActivity.EXTRA_PERMISSIONS, perms) }
    if (err != null) return null to err
    return withTimeoutOrNull(timeoutMs) { d.await() } to null
}

/** Открыть чужой экран через батут и дождаться возврата «Назад». (ошибка, время возврата, время запуска) */
suspend fun ProbeScope.openViaTrampoline(target: Intent, fromSheet: Boolean, timeoutMs: Long = 180_000): Triple<String?, Long?, Long> {
    val launched = CompletableDeferred<String?>()
    val returned = CompletableDeferred<Long>()
    PermBus.launched = launched
    PermBus.returned = returned
    val t0 = SystemClock.uptimeMillis()
    val err = launchTrampoline(fromSheet) { putExtra(PermTrampolineActivity.EXTRA_TARGET, target) }
        ?: withTimeoutOrNull(5000) { launched.await() }
    if (err != null) return Triple(err, null, t0)
    val back = withTimeoutOrNull(timeoutMs) { returned.await() }
    return Triple(null, back, t0)
}

/**
 * Экраны Samsung, найденные человеком через scripts/top-activity.sh <ключ>: скрипт дописывает
 * «ключ=pkg/.Class» в files/components.properties (побеждает последняя строка). Ключей не угадываем.
 */
object Components {
    fun get(ctx: Context, key: String): ComponentName? = runCatching {
        File(ctx.filesDir, "components.properties").readLines()
            .mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 && it[0].trim() == key }?.get(1)?.trim() }
            .lastOrNull()?.let { ComponentName.unflattenFromString(it) }
    }.getOrNull()

    fun all(ctx: Context): String = runCatching { File(ctx.filesDir, "components.properties").readText().trim() }.getOrDefault("")
}

/** Открыть первую камеру через Camera2 и сразу закрыть: что бывает при выключенном в шторке доступе. */
@SuppressLint("MissingPermission")
suspend fun tryOpenCamera(ctx: Context): String {
    val cm = ctx.getSystemService(CameraManager::class.java)
    val id = runCatching { cm.cameraIdList.firstOrNull() }.getOrNull() ?: return "камер нет"
    val t0 = SystemClock.uptimeMillis()
    return withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { cont ->
            try {
                cm.openCamera(id, ctx.mainExecutor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        camera.close()
                        if (cont.isActive) cont.resume("открылась за ${SystemClock.uptimeMillis() - t0} мс")
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cont.isActive) cont.resume("onDisconnected")
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cont.isActive) cont.resume("onError $error")
                    }
                })
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume("${t.javaClass.simpleName}: ${t.message}")
            }
        }
    } ?: "нет ответа за 5 с"
}
