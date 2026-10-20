package ru.poneslas.vahter.probe.core

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.ext.SdkExtensions
import android.service.voice.VoiceInteractionService
import ru.poneslas.vahter.probe.assist.ProbeAssistService

object Header {
    fun roleHeld(ctx: Context) = ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

    fun visActive(ctx: Context) =
        VoiceInteractionService.isActiveService(ctx, ComponentName(ctx, ProbeAssistService::class.java))

    /** One UI: SEM_PLATFORM_INT = 90000 → «9.0». Поле скрытое, читаем отражением. */
    fun oneUi(): String = runCatching {
        val v = Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null) - 90000
        "${v / 10000 + 1}.${v % 10000 / 100}"
    }.getOrElse { "нет (не Samsung или поле скрыто)" }

    fun collect(ctx: Context): Map<String, String> {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return linkedMapOf(
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "fingerprint" to Build.FINGERPRINT,
            "sdk" to "${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
            "security_patch" to Build.VERSION.SECURITY_PATCH,
            "one_ui" to oneUi(),
            "sdk_ext_34" to runCatching { SdkExtensions.getExtensionVersion(34).toString() }.getOrDefault("?"),
            "assistant_role" to if (roleHeld(ctx)) "пробник" else "не пробник",
            "vis_active" to visActive(ctx).toString(),
            "auto_blocker_off" to Config.flag("autoblocker_off").toString(),
            "battery_unrestricted" to pm.isIgnoringBatteryOptimizations(ctx.packageName).toString(),
            "free_mb" to (ctx.filesDir.usableSpace / 1_000_000).toString(),
            "probe_version" to "${pi.versionName} (${pi.longVersionCode})",
        )
    }

    fun short(ctx: Context) = "${Build.MODEL}, SDK ${Build.VERSION.SDK_INT}, роль=${roleHeld(ctx)}"
}
