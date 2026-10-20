package ru.poneslas.vahter.probe.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.vahter.probe.Module
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.core.Config
import ru.poneslas.vahter.probe.core.Header
import ru.poneslas.vahter.probe.core.Probe
import ru.poneslas.vahter.probe.core.ProbeScope
import ru.poneslas.vahter.probe.core.ReplaceLog
import ru.poneslas.vahter.probe.core.Status
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object UpdateModule : Module {
    override val probes = listOf(SelfUpdateProbe)
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("probes", Context.MODE_PRIVATE)

object SelfUpdateProbe : Probe(
    "П14", "Самообновление без окна и роль после него",
    "PackageInstaller с USER_ACTION_NOT_REQUIRED ставит новую версию без окна; роль помощника после этого остаётся.",
    needsHuman = true,
    steps = listOf(
        "На Mac: python3 probe/scripts/serve.py и probe/scripts/bump.sh 4 --serve (сборка versionCode 5 в probe/serve/).",
        "Запустить П14 → «Новое обновление»: один раз разрешить «Установка неизвестных приложений», дальше пробник скачивает, сверяет sha256 и ставит сам. Процесс перезапустится.",
        "Повторить с bump.sh 5 --serve (вторая подряд), затем ещё раз — уже установщик записи сам пробник; одну из установок — с открытым листом.",
        "Снова П14 → «Посчитать».",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val p = prefs(ctx)
        val marker = p.getLong("p14_marker", 0)
        val choice = if (marker == 0L) "Новое обновление" else ask(
            "Есть начатый замер (${InstallLog.since(ctx, marker).count { it.tag == "status" }} статусов, ${ReplaceLog.since(ctx, marker).size} записей о замене). Что делаем?",
            listOf("Посчитать", "Новое обновление", "Сбросить и начать"),
        )
        if (choice == "Посчитать") return evaluate(marker)
        if (choice == "Сбросить и начать" || marker == 0L) p.edit { putLong("p14_marker", System.currentTimeMillis()) }

        if (!ctx.packageManager.canRequestPackageInstalls() && !grantUnknownSources()) {
            summary("«Установка неизвестных приложений» не выдана")
            return Status.FAIL
        }
        val apk = download() ?: return Status.FAIL
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        log("versions", "текущая $current, на сервере ${info?.longVersionCode} (${info?.versionName})")
        if (info == null || info.longVersionCode <= current) {
            summary("На сервере не новее текущей ($current) — bump.sh N --serve с бо́льшим N")
            return Status.FAIL
        }
        // Шаг-опыт: что отвечает PackageInstaller при включённом Auto Blocker (статус и текст ошибки).
        val autoBlocker = ask(
            "Auto Blocker сейчас? Для опыта включите его (Безопасность → Auto Blocker) — посмотрим статус установки. Не забудьте потом выключить.",
            listOf("Выключен", "Включён (опыт)"),
        ) == "Включён (опыт)"
        InstallLog.add(ctx, "autoblocker", if (autoBlocker) "on" else "off")
        val withSheet = ask("Ставить с открытым листом (шаг 3)?", listOf("Нет", "С листом")) == "С листом"
        if (withSheet) log("sheet", "открыт=${Sheet.open() != null}")
        install(apk, info.longVersionCode, withSheet)
        // При успехе процесс убьют. Если дошли сюда — ждём статус до 60 с.
        delay(60_000)
        summary("Процесс не перезапустился за 60 с — см. статусы в логе (InstallLog)")
        InstallLog.since(ctx, p.getLong("p14_marker", 0)).forEach { log("install_log", "${it.tag}: ${it.text}") }
        return Status.PARTIAL
    }

    private suspend fun ProbeScope.grantUnknownSources(): Boolean = coroutineScope {
        val answer = async { ask("Разрешите пробнику «Установку неизвестных приложений» и вернитесь.", listOf("Не получается")) }
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val ok = withTimeoutOrNull(180_000) {
            while (!ctx.packageManager.canRequestPackageInstalls() && !answer.isCompleted) delay(500)
            ctx.packageManager.canRequestPackageInstalls()
        } ?: false
        answer.cancel()
        log("unknown_sources", "granted=$ok")
        ok
    }

    private suspend fun ProbeScope.download(): File? = withContext(Dispatchers.IO) {
        val base = "${Config.server}/apk"
        try {
            val expected = fetch("$base/next.sha256").toString(Charsets.UTF_8).trim()
            val t0 = SystemClock.uptimeMillis()
            val bytes = fetch("$base/next.apk")
            val ms = SystemClock.uptimeMillis() - t0
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            metric("download_ms", ms)
            metric("apk_mb", bytes.size / 1_048_576.0)
            log("download", "${bytes.size} байт за $ms мс, sha256 ${if (sha == expected) "совпал" else "НЕ совпал: $sha ≠ $expected"}")
            if (sha != expected) {
                summary("sha256 не совпал")
                return@withContext null
            }
            File(ctx.cacheDir, "next.apk").apply { writeBytes(bytes) }
        } catch (e: Exception) {
            error(e, "скачивание $base")
            summary("Не скачалось с ${Config.server} — запущен ли scripts/serve.py и есть ли bump.sh N --serve")
            null
        }
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode} $url")
            return c.inputStream.readBytes()
        } finally {
            c.disconnect()
        }
    }

    private fun ProbeScope.install(apk: File, versionCode: Long, withSheet: Boolean) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setSize(apk.length())
        }
        val sid = pi.createSession(params)
        pi.openSession(sid).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val pending = PendingIntent.getBroadcast(
                ctx, sid, Intent(ctx, InstallStatusReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            InstallLog.add(ctx, "commit", "versionCode=$versionCode роль=${Header.roleHeld(ctx)} лист=$withSheet")
            log("commit", "сессия $sid, versionCode $versionCode")
            s.commit(pending.intentSender)
        }
    }

    private fun ProbeScope.evaluate(marker: Long): Status {
        val inst = InstallLog.since(ctx, marker)
        val repl = ReplaceLog.since(ctx, marker)
        inst.forEach { log("install_log", "${it.tag}: ${it.text}") }
        repl.forEach { log("replaced", "${it.versionName} (${it.versionCode}) ${it.stage}: роль=${it.roleHeld} vis=${it.visActive} installer=${it.installer}") }
        val commits = inst.filter { it.tag == "commit" }
        // Статусы установок при включённом Auto Blocker — отдельный опыт, в общий итог не идут.
        val abMarks = inst.filter { it.tag == "autoblocker" }
        val (abStatuses, statuses) = inst.filter { it.tag == "status" }
            .partition { s -> abMarks.lastOrNull { it.wall <= s.wall }?.text == "on" }
        abStatuses.forEach { log("autoblocker_status", it.text) }
        if (abStatuses.isNotEmpty()) metric("autoblocker_installs", abStatuses.size)
        val silent = statuses.count { it.text.startsWith("SUCCESS") }
        val askedUser = statuses.count { it.text.startsWith("PENDING_USER_ACTION") }
        val failed = statuses.filter { it.text.startsWith("FAILURE") }
        // Время от commit до MY_PACKAGE_REPLACED («сразу») для каждой установки.
        commits.forEach { c ->
            repl.firstOrNull { it.stage == "сразу" && it.wall >= c.wall }?.let { metric("commit_to_replaced_ms", it.wall - c.wall) }
        }
        val late = repl.filter { it.stage != "сразу" }.ifEmpty { repl }
        val roleLost = late.count { !it.roleHeld }
        metric("installs", commits.size)
        metric("silent_success", silent)
        metric("pending_user_action", askedUser)
        metric("role_lost", roleLost)
        summary(
            "установок ${commits.size}: без окна $silent, с окном $askedUser, ошибок ${failed.size}" +
                (failed.firstOrNull()?.let { " (${it.text})" } ?: "") +
                "; замен пакета ${late.size}, роль слетела в $roleLost; installer: ${repl.map { it.installer }.distinct().joinToString()}" +
                (if (abStatuses.isNotEmpty()) "; с Auto Blocker: ${abStatuses.joinToString { it.text }}" else ""),
        )
        return when {
            commits.isEmpty() -> Status.NOT_RUN
            failed.isNotEmpty() || roleLost > 0 -> Status.FAIL
            silent >= 2 && askedUser == 0 && late.isNotEmpty() -> Status.PASS
            else -> Status.PARTIAL
        }
    }
}
