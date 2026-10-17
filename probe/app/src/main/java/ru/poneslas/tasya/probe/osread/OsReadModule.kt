package ru.poneslas.tasya.probe.osread

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.assist.openAssistantSettings
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status

object OsReadModule : Module {
    override val probes = listOf(SettingsReadProbe)
}

/** Ключи, где обычно лежат помощник и боковая кнопка. Samsung-ключи — по памяти, не по документации [?]; потому и снимаем всё и сравниваем. */
private val interesting = Regex("assist|voice_interaction|voice_recognition|side|function_key|long_?press|bixby|power_button|power_key|double_?press|dbl", RegexOption.IGNORE_CASE)

private val tables = listOf("system", "secure", "global")

/**
 * Снимок настроек, видимых приложению. Сначала целиком через content://settings/<таблица>
 * (на новых Android может быть закрыто [?]), иначе — пусто, и тогда работают только точечные чтения.
 */
private fun ProbeScope.snapshot(): Map<String, String?> {
    val out = linkedMapOf<String, String?>()
    for (t in tables) {
        try {
            ctx.contentResolver.query(Uri.parse("content://settings/$t"), arrayOf("name", "value"), null, null, null)?.use { c ->
                while (c.moveToNext()) out["$t/${c.getString(0)}"] = c.getString(1)
            } ?: log("table", "$t: query вернул null")
        } catch (e: Exception) {
            log("table", "$t: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    return out
}

private fun ProbeScope.readOne(table: String, key: String): String = try {
    when (table) {
        "system" -> Settings.System.getString(ctx.contentResolver, key)
        "secure" -> Settings.Secure.getString(ctx.contentResolver, key)
        else -> Settings.Global.getString(ctx.contentResolver, key)
    } ?: "null"
} catch (e: Exception) {
    "${e.javaClass.simpleName}: ${e.message}"
}

object SettingsReadProbe : Probe(
    "П18", "Чтение настроек: помощник, боковая кнопка, батарея, уведомления",
    "Может ли приложение прочитать, кто выбран помощником и что назначено на удержание боковой кнопки, — чтобы предупредить, что не настроено.",
    needsHuman = true,
    steps = listOf(
        "Пробник снимает все видимые ему настройки и читает известные ключи точечно.",
        "Человек меняет действие удержания боковой кнопки (например, Bixby ↔ Цифровой помощник) — пробник снимает снова и ищет изменившиеся ключи.",
        "То же с выбором цифрового помощника.",
        "Сверка с тем, что видит shell: scripts/settings-dump.sh до и после.",
    ),
) {
    // Кандидаты: AOSP-ключи (assistant, voice_interaction_service — Settings.Secure, скрытые;
    // power_button_long_press — Settings.Global) и Samsung-ключи боковой кнопки [?].
    private val candidates = listOf(
        "secure" to "assistant", "secure" to "voice_interaction_service", "secure" to "voice_recognition_service",
        "global" to "power_button_long_press", "global" to "key_chord_power_volume_up",
        "global" to "function_key_config_longpress_type", "global" to "function_key_config_doublepress_type",
        "global" to "function_key_config_doublepress_value", "system" to "function_key_config_longpress_type",
    )

    override suspend fun ProbeScope.run(): Status {
        val rm = ctx.getSystemService(RoleManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AlarmManager::class.java)
        log("role_held", rm.isRoleHeld(RoleManager.ROLE_ASSISTANT).toString())
        log("battery_unrestricted", pm.isIgnoringBatteryOptimizations(ctx.packageName).toString())
        log("notifications_enabled", nm.areNotificationsEnabled().toString())
        log("exact_alarms", am.canScheduleExactAlarms().toString())
        log("dnd_access", nm.isNotificationPolicyAccessGranted.toString())
        candidates.forEach { (t, k) -> log("read", "$t/$k = ${readOne(t, k)}") }

        val s0 = snapshot()
        metric("visible_settings", s0.size)
        tables.forEach { t -> log("visible", "$t: ${s0.keys.count { it.startsWith("$t/") }} ключей") }
        s0.filterKeys { interesting.containsMatchIn(it) }.forEach { (k, v) -> log("interesting", "$k = $v") }

        tell("Откройте Настройки → Дополнительные функции → Боковая кнопка и смените действие «Нажатие и удержание» на другое (потом можно вернуть). Вернитесь сюда.")
        val s1 = snapshot()
        val sideDiff = diff(s0, s1)
        sideDiff.forEach { log("side_changed", it) }
        val pointSide = candidates.map { (t, k) -> "$t/$k = ${readOne(t, k)}" }
        pointSide.forEach { log("read_after_side", it) }

        val assistBefore = readOne("secure", "assistant")
        val ans = ask("Сменить цифрового помощника на другого (например Gemini/Bixby) и обратно на пробник? Откроются настройки.", listOf("Открыть настройки", "Пропустить"))
        var assistDiff = emptyList<String>()
        if (ans == "Открыть настройки") {
            log("settings", openAssistantSettings(ctx))
            tell("Выберите другого помощника, вернитесь сюда и нажмите «Готово» (роль пробнику потом вернёт П1 или scripts/install.sh).")
            val s2 = snapshot()
            assistDiff = diff(s1, s2)
            assistDiff.forEach { log("assist_changed", it) }
            log("assistant_after", "${readOne("secure", "assistant")} (до: $assistBefore); роль у пробника: ${rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)}")
        }

        val canSeeSide = sideDiff.isNotEmpty()
        val canSeeAssistant = !readOne("secure", "assistant").startsWith("SecurityException") && readOne("secure", "assistant") != "null"
        summary(buildString {
            append("видно настроек: ${s0.size}; ")
            append(if (canSeeSide) "боковая кнопка видна: ${sideDiff.joinToString()}; " else "смену боковой кнопки приложение не увидело; ")
            append(if (canSeeAssistant) "помощник читается (secure/assistant); " else "помощник читается только как «роль у нас / не у нас» (RoleManager); ")
            if (assistDiff.isNotEmpty()) append("при смене помощника изменилось: ${assistDiff.joinToString()}")
        })
        return when {
            canSeeSide -> Status.PASS
            else -> Status.PARTIAL
        }
    }

    private fun diff(a: Map<String, String?>, b: Map<String, String?>): List<String> =
        (a.keys + b.keys).filter { a[it] != b[it] }.map { "$it: ${a[it]} → ${b[it]}" }
}
