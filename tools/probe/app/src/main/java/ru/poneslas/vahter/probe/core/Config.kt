package ru.poneslas.vahter.probe.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Настройки прогона. Тестовый сервер по умолчанию — 127.0.0.1:8765 через `adb reverse`
 * (его делает scripts/install.sh): так Mac виден и с эмулятора, и с телефона по USB, и без
 * разрешения на локальную сеть — 10.0.2.2 и адреса Wi-Fi при targetSdk 37 без него недоступны.
 */
object Config {
    private lateinit var prefs: SharedPreferences

    val fields = listOf(
        Field("server", "Тестовый сервер (П9, П14, П15, П23)", "http://127.0.0.1:8765"),
        Field("lan", "Адрес в локальной сети host:port (П13)", "192.168.1.10:8765"),
        Field("domain", "Домен host:port (П13)", "example.com:443"),
        Field("tailscale", "Адрес Tailscale host:port (П13, пусто — пропустить)", ""),
    )

    data class Field(val key: String, val label: String, val default: String)

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
    }

    operator fun get(key: String): String =
        prefs.getString(key, null) ?: fields.first { it.key == key }.default

    operator fun set(key: String, value: String) = prefs.edit { putString(key, value) }

    /** Общие флажки, которые человек ставит руками (Auto Blocker и т. п.). */
    fun flag(key: String) = prefs.getBoolean("flag_$key", false)
    fun setFlag(key: String, v: Boolean) = prefs.edit { putBoolean("flag_$key", v) }

    val server get() = get("server").trimEnd('/')

    /** Свой тестовый сервер (scripts/serve.py) включён; иначе П23 берёт поток из интернета (httpbin.org). */
    var useOwnServer: Boolean
        get() = flag("own_server")
        set(v) = setFlag("own_server", v)
}
