package ru.poneslas.vahter.assistant

import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import ru.poneslas.vahter.R
import ru.poneslas.vahter.app.MainActivity
import ru.poneslas.vahter.tr

/**
 * Плитка «Вахтёр» в быстрых настройках — запасной вызов на телефонах, где боковую кнопку не настроить (Продукт 5.13).
 * Нажатие сворачивает шторку и открывает лист: из плитки showSession напрямую оставил бы шторку поверх, поэтому —
 * через прозрачную активность [TileOpen]. Если Вахтёр не помощник, листа нет — открывается приложение.
 */
class VahterTile : TileService() {
    override fun onClick() {
        val i = Intent(this, TileOpen::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }

    companion object {
        /** Системное окно «Добавить плитку?» (Android 13+); [done] — плитка в шторке (добавлена сейчас или уже была). */
        fun request(ctx: Context, done: (Boolean) -> Unit) {
            val sbm = ctx.getSystemService(StatusBarManager::class.java) ?: return done(false)
            sbm.requestAddTileService(ComponentName(ctx, VahterTile::class.java), tr(R.string.app_name),
                Icon.createWithResource(ctx, R.drawable.ic_launcher_monochrome), ctx.mainExecutor) { r ->
                done(r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
            }
        }
    }
}

/** Прозрачная активность плитки и действия «Помощь» (ACTION_ASSIST, так зовут помощника некоторые прошивки): открыть лист и сразу закрыться. */
class TileOpen : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = if (intent?.action == Intent.ACTION_ASSIST) "assist" else "tile"
        if (Sheet.service != null) Sheet.show(Bundle().apply { putString("src", src) })
        else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
