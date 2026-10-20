package ru.poneslas.vahter.probe.entry

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.service.quicksettings.TileService
import androidx.core.content.edit
import kotlinx.coroutines.launch
import ru.poneslas.vahter.probe.App

/**
 * Плитка быстрых настроек. Режим — prefs entry/tile_mode:
 * service — 11.3, showSession из плитки, лист сам закрывает шторку (collapse=true);
 * activity — 11.4, startActivityAndCollapse(PendingIntent) → батут → сервис.
 */
class ProbeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "Вахтёр · ${tileMode(this@ProbeTileService)}"
            updateTile()
        }
    }

    override fun onClick() {
        val t = SystemClock.uptimeMillis()
        val locked = isLocked
        when (tileMode(this)) {
            "activity" -> {
                val i = Intent(this, ShortcutTrampolineActivity::class.java)
                    .putExtra("via", "service").putExtra("src", "tile_activity").putExtra("mode", "assistant")
                    .putExtra("t_entry", t).putExtra("locked", locked)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                EntryLauncher.note("tile_activity: startActivityAndCollapse, locked=$locked")
                runCatching { startActivityAndCollapse(pi) }
                    .onFailure { EntryLauncher.note("tile_activity: ${it.javaClass.simpleName}: ${it.message}") }
            }
            else -> App.scope.launch {
                val args = Bundle().apply {
                    putString("mode", "assistant")
                    putBoolean("collapse", true)
                    putBoolean("locked", locked)
                }
                EntryLauncher.viaService("tile_service", args, t)
            }
        }
    }

    companion object {
        fun tileMode(ctx: Context) = prefs(ctx).getString("tile_mode", "service") ?: "service"
        fun setTileMode(ctx: Context, mode: String) = prefs(ctx).edit { putString("tile_mode", mode) }
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("entry", Context.MODE_PRIVATE)
    }
}
