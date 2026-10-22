package ru.poneslas.vahter.app

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Профиль телефона (Архитектура 3.4): что стоит на боковой кнопке и как открыть её экран.
 * Samsung (One UI): Settings.Global function_key_config_longpress_type — 2 цифровой помощник, 0 Bixby;
 * двойное нажатие — function_key_config_doublepress_*. Публичного интента на экран нет —
 * ищем активность «Настроек» по имени (проверено на S24). Незнакомый телефон — «Неизвестно».
 */
object SideKey {
    /** Второй способ вызвать помощника, кроме кнопки: при навигации жестами — свайп из нижнего угла, при трёх кнопках — долгое «Домой». */
    fun gestureNav(ctx: Context): Boolean = android.provider.Settings.Secure.getInt(ctx.contentResolver, "navigation_mode", 2) == 2

    enum class State { ASSISTANT, OTHER, DOUBLE, UNKNOWN }

    fun read(ctx: Context): State {
        val cr = ctx.contentResolver
        val long = runCatching { Settings.Global.getInt(cr, "function_key_config_longpress_type") }.getOrNull()
        val longItem = runCatching { Settings.Global.getString(cr, "function_key_config_longpress_selected_item") }.getOrNull()
        val dbl = runCatching { Settings.Global.getString(cr, "function_key_config_doublepress_selected_item") }.getOrNull()
        return when {
            long == 2 || longItem?.contains("ai_agent") == true -> State.ASSISTANT
            dbl?.contains("ai_agent") == true -> State.DOUBLE
            long != null || longItem != null -> State.OTHER
            else -> State.UNKNOWN
        }
    }

    /** Хвост строки «Не настроено · …» в первом запуске: что сейчас на кнопке. */
    fun now(ctx: Context): String = when (read(ctx)) {
        State.DOUBLE -> tr(R.string.sidekey_now_double)
        else -> {
            val item = runCatching { Settings.Global.getString(ctx.contentResolver, "function_key_config_longpress_selected_item") }.getOrNull()
            val type = runCatching { Settings.Global.getInt(ctx.contentResolver, "function_key_config_longpress_type") }.getOrNull()
            if (item?.contains("bixby") == true || type == 0) tr(R.string.sidekey_now_bixby) else tr(R.string.sidekey_now_power)
        }
    }

    fun open(ctx: Context) {
        val pm = ctx.packageManager
        val re = Regex("side.?key|function.?key|side.?button|power.?key", RegexOption.IGNORE_CASE)
        val found = runCatching {
            pm.getPackageInfo("com.android.settings", PackageManager.GET_ACTIVITIES).activities.orEmpty()
                .filter { it.exported && re.containsMatchIn(it.name) }
                .sortedByDescending { it.name.contains("Settings") }
                .firstOrNull()
        }.getOrNull()
        val intent = if (found != null) Intent().setClassName(found.packageName, found.name) else Intent(Settings.ACTION_SETTINGS)
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
