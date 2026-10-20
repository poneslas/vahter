package ru.poneslas.vahter.actions

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.poneslas.vahter.App
import ru.poneslas.vahter.app.MainActivity
import ru.poneslas.vahter.assistant.Sheet

/**
 * Открыть экран другого приложения. Из листа окно на S24 открывается под листом (проверки, 18.10),
 * поэтому лист сначала прячется, потом запуск — как у выбора фото.
 */
object Launch {
    fun activity(ctx: Context, intent: Intent): Result<Unit> = runCatching {
        val app = ctx.applicationContext
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Проверяем заранее: «нет приложения» — ошибка сразу, а не тихо после hide().
        if (intent.resolveActivity(app.packageManager) == null && intent.`package` == null) error("no_activity")
        val s = Sheet.session
        if (Sheet.visible.value && s != null) {
            s.hide()
            App.instance.scope.launch { delay(200); runCatching { app.startActivity(intent) } }
        } else app.startActivity(intent)
    }

    /**
     * Спросить доступы Android. Из листа системное окно ушло бы под лист, поэтому спрашивает приложение
     * и потом возвращает лист; из приложения — сразу.
     */
    fun askPermissions(ctx: Context, perms: List<String>) {
        val s = Sheet.session
        val list = perms.joinToString(",")
        if (Sheet.visible.value && s != null) s.openApp(settings = false, askPerm = list)
        else ctx.applicationContext.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_ASK_PERM, list))
    }
}
