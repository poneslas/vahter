package ru.poneslas.tasya.app

import ru.poneslas.tasya.R
import ru.poneslas.tasya.tr
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

    fun describe(ctx: Context) = when (read(ctx)) {
        State.ASSISTANT -> tr(R.string.side_nazhatie_i_uderzhanie_tsifrovoy_pomoshch)
        State.DOUBLE -> tr(R.string.side_pomoshchnik_na_dvoynom_nazhatii_dlya)
        State.OTHER -> tr(R.string.side_uderzhanie_otkryvaet_bixby_ili_menyu)
        State.UNKNOWN -> tr(R.string.side_proverka_knopki_zavisit_ot_telefona)
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
