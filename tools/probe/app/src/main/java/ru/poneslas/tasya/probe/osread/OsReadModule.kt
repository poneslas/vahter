package ru.poneslas.tasya.probe.osread

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.SensorPrivacyManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.assist.openAssistantSettings
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status
import ru.poneslas.tasya.probe.perm.Components

object OsReadModule : Module {
    override val probes = listOf(SettingsReadProbe)
}

/** Ключи, где обычно лежат помощник и боковая кнопка. Samsung-ключи — по памяти, не по документации [?]; потому и снимаем всё и сравниваем. */
private val interesting = Regex("assist|voice_interaction|voice_recognition|side|function_key|long_?press|bixby|power_button|power_key|double_?press|dbl", RegexOption.IGNORE_CASE)

private val tables = listOf("system", "secure", "global")

/**
 * Снимок настроек, видимых приложению: целиком через content://settings/<таблица>
 * (на новых Android может быть закрыто [?]); при отказе работают только точечные чтения.
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

/** Публичные API про состояние приложения — то, что точно разрешено читать. */
private fun ProbeScope.publicReads(): Map<String, String> {
    val am = ctx.getSystemService(ActivityManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val alarm = ctx.getSystemService(AlarmManager::class.java)
    val usm = ctx.getSystemService(UsageStatsManager::class.java)
    val spm = ctx.getSystemService(SensorPrivacyManager::class.java)
    fun safe(f: () -> Any?) = runCatching { f().toString() }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    return linkedMapOf(
        "role_held" to safe { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) },
        "battery_unrestricted" to safe { pm.isIgnoringBatteryOptimizations(ctx.packageName) },
        "background_restricted" to safe { am.isBackgroundRestricted },
        "standby_bucket" to safe { usm.appStandbyBucket },
        "auto_revoke_whitelisted" to safe { ctx.packageManager.isAutoRevokeWhitelisted },
        "can_request_installs" to safe { ctx.packageManager.canRequestPackageInstalls() },
        "notifications_enabled" to safe { nm.areNotificationsEnabled() },
        "channels" to safe { nm.notificationChannels.joinToString { "${it.id}=${it.importance}" }.ifEmpty { "нет" } },
        "exact_alarms" to safe { alarm.canScheduleExactAlarms() },
        "dnd_access" to safe { nm.isNotificationPolicyAccessGranted },
        "animator_duration_scale" to safe { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) },
        "sensor_toggle_mic" to safe { spm.supportsSensorToggle(SensorPrivacyManager.Sensors.MICROPHONE) },
        "sensor_toggle_camera" to safe { spm.supportsSensorToggle(SensorPrivacyManager.Sensors.CAMERA) },
    )
}

/** Шаг-дифф: человек меняет одно состояние в системе, пробник сравнивает снимки до и после. */
private class DiffStep(val key: String, val label: String, val instruction: String, val screenKey: String? = null)

private val diffSteps = listOf(
    DiffStep(
        "side_key", "Боковая кнопка: действие удержания",
        "Откройте Настройки → Дополнительные функции → Боковая кнопка. Пока экран открыт — на Mac: scripts/top-activity.sh side_key. Затем смените «Нажатие и удержание» на другое действие и вернитесь сюда.",
        screenKey = "side_key",
    ),
    DiffStep(
        "auto_blocker", "Auto Blocker",
        "Откройте Auto Blocker (Настройки → Безопасность и конфиденциальность → Auto Blocker). Пока экран открыт и Auto Blocker выключен — на Mac: scripts/top-activity.sh auto_blocker (при включённом adb по USB может не работать). Включите его и вернитесь сюда. Потом выключите обратно.",
        screenKey = "auto_blocker",
    ),
    DiffStep(
        "battery_restricted", "Батарея пробника: «Ограничено»",
        "Откройте батарею пробника (Настройки → Приложения → Пробник Таси → Батарея). Пока экран открыт — на Mac: scripts/top-activity.sh battery. Поставьте «Ограничено» и вернитесь.",
        screenKey = "battery",
    ),
    DiffStep("battery_optimized", "Батарея пробника: «Оптимизировано»", "Там же поставьте «Оптимизировано» и вернитесь."),
    DiffStep("battery_unrestricted", "Батарея пробника: «Без ограничений»", "Там же верните «Без ограничений» и вернитесь."),
    DiffStep(
        "auto_revoke", "Удалять разрешения, если не используется",
        "Настройки → Приложения → Пробник Таси: переключите «Удалять разрешения, если не используется» (на One UI может называться «Приостановить работу приложения, если оно не используется» [?]) и вернитесь.",
    ),
    DiffStep("mic_toggle", "Доступ к микрофону (шторка)", "Выключите в шторке быстрых настроек «Доступ к микрофону» и вернитесь. Потом включите обратно."),
    DiffStep("camera_toggle", "Доступ к камере (шторка)", "Выключите в шторке быстрых настроек «Доступ к камере» и вернитесь. Потом включите обратно."),
)

object SettingsReadProbe : Probe(
    "П18", "Чтение настроек: помощник, боковая кнопка, батарея, уведомления",
    "Какие системные состояния приложение может прочитать, чтобы предупредить «не настроено»: помощник, удержание боковой кнопки, Auto Blocker, батарея, автоотзыв, переключатели шторки.",
    needsHuman = true,
    steps = listOf(
        "Пробник читает публичные API и известные ключи, снимает все видимые ему настройки.",
        "По шагам человек меняет одно состояние (боковая кнопка, Auto Blocker, батарея, автоотзыв, микрофон/камера в шторке) — пробник снимает снова и ищет, что изменилось. Любой шаг можно пропустить.",
        "Заодно на экранах Samsung — scripts/top-activity.sh <ключ>: экран запоминается для П19.",
        "Смена цифрового помощника — последний шаг.",
        "Сверка с shell: scripts/settings-dump.sh before / after.",
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
        val p0 = publicReads()
        p0.forEach { (k, v) -> log("public", "$k = $v") }
        candidates.forEach { (t, k) -> log("read", "$t/$k = ${readOne(t, k)}") }

        var snap = snapshot()
        metric("visible_settings", snap.size)
        tables.forEach { t -> log("visible", "$t: ${snap.keys.count { it.startsWith("$t/") }} ключей") }
        snap.filterKeys { interesting.containsMatchIn(it) }.forEach { (k, v) -> log("interesting", "$k = $v") }

        // «состояние → чем читается» — строки итоговой таблицы.
        val table = linkedMapOf<String, String>()
        var pub = p0
        loop@ for (s in diffSteps) {
            when (ask("${s.label}: ${s.instruction}", listOf("Готово", "Пропустить", "Закончить"))) {
                "Пропустить" -> { table[s.label] = "пропущено"; continue@loop }
                "Закончить" -> break@loop
            }
            val snap2 = snapshot()
            val pub2 = publicReads()
            val keys = diff(snap, snap2)
            val pubs = diff(pub, pub2)
            keys.forEach { log("changed_${s.key}", it) }
            pubs.forEach { log("changed_${s.key}", "API $it") }
            s.screenKey?.let { k -> log("screen_${s.key}", Components.get(ctx, k)?.flattenToShortString() ?: "экран не записан (scripts/top-activity.sh $k)") }
            table[s.label] = when {
                keys.isEmpty() && pubs.isEmpty() -> "не видно приложению"
                else -> (pubs.map { "API ${it.substringBefore(':')}" } + keys.map { "ключ ${it.substringBefore(':')}" }).joinToString(", ")
            }
            snap = snap2
            pub = pub2
        }

        val assistBefore = readOne("secure", "assistant")
        if (ask("Последний шаг: сменить цифрового помощника на другого (Gemini/Bixby) — откроются настройки. Роль пробнику вернёт scripts/install.sh.", listOf("Открыть настройки", "Пропустить")) == "Открыть настройки") {
            log("settings", openAssistantSettings(ctx))
            tell("Выберите другого помощника, вернитесь сюда и нажмите «Готово».")
            val snap2 = snapshot()
            val keys = diff(snap, snap2)
            keys.forEach { log("changed_assistant", it) }
            val roleNow = ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
            log("assistant_after", "${readOne("secure", "assistant")} (до: $assistBefore); роль у пробника: $roleNow")
            table["Цифровой помощник"] = if (keys.isEmpty()) "только RoleManager (роль у нас: $roleNow)" else keys.joinToString { "ключ ${it.substringBefore(':')}" } + ", RoleManager"
        } else {
            table["Цифровой помощник"] = if (assistBefore.contains('/')) "читается secure/assistant (без смены не проверено)" else "secure/assistant не читается: $assistBefore"
        }

        table.forEach { (k, v) -> log("table", "$k → $v") }
        val sideSeen = table["Боковая кнопка: действие удержания"]?.let { it != "не видно приложению" && it != "пропущено" } == true
        summary("видно настроек: ${snap.size}. " + table.entries.joinToString("; ") { "${it.key} → ${it.value}" })
        return if (sideSeen) Status.PASS else Status.PARTIAL
    }

    private fun diff(a: Map<String, String?>, b: Map<String, String?>): List<String> =
        (a.keys + b.keys).filter { a[it] != b[it] }.map { "$it: ${a[it]} → ${b[it]}" }
}
