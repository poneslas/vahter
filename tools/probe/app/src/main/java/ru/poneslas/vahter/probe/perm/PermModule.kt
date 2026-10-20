package ru.poneslas.vahter.probe.perm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.vahter.probe.Module
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.core.Probe
import ru.poneslas.vahter.probe.core.ProbeScope
import ru.poneslas.vahter.probe.core.Status
import ru.poneslas.vahter.probe.session.recordFor

object PermModule : Module {
    override val probes = listOf(SettingsIntentsProbe, PermsFromSheetProbe)
}

private fun pkgUri(c: Context) = Uri.parse("package:${c.packageName}")
private const val CHANNEL = "probe_p19"

/** Экран настроек: цепочка интентов (берётся первый, который резолвится) и что смотреть до/после. */
private class Target(
    val key: String,
    val label: String,
    val chain: (Context) -> List<Intent>,
    val state: ((Context) -> String)? = null,
)

private val roleState: (Context) -> String = { "роль=${it.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)}" }
private val micState: (Context) -> String = { "микрофон=${it.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}" }
private val notifState: (Context) -> String = { "уведомления=${it.getSystemService(NotificationManager::class.java).areNotificationsEnabled()}" }
private val channelState: (Context) -> String = { "важность канала=${it.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance}" }
private val batteryState: (Context) -> String = { "без ограничений=${it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName)}" }
private val autoRevokeState: (Context) -> String = { "исключён из автоотзыва=${it.packageManager.isAutoRevokeWhitelisted}" }
private val dndState: (Context) -> String = { "доступ к DND=${it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted}" }
private val exactState: (Context) -> String = { "точные будильники=${it.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()}" }
private val installsState: (Context) -> String = { "установка неизвестных=${it.packageManager.canRequestPackageInstalls()}" }

private fun component(c: Context, key: String) = Components.get(c, key)?.let { Intent().setComponent(it) }

private val targets = listOf(
    Target("voice_input", "Голосовой ввод / цифровой помощник", { listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }, roleState),
    Target("default_apps", "Приложения по умолчанию", { listOf(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }, roleState),
    // Экран Samsung находит человек (scripts/top-activity.sh side_key); запасной — корень настроек.
    Target("side_key", "Боковая кнопка → Нажатие и удержание", { c -> listOfNotNull(component(c, "side_key"), Intent(Settings.ACTION_SETTINGS)) }),
    Target("app_details", "О приложении (разрешения)", { c -> listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c))) }, micState),
    Target("app_notifications", "Уведомления приложения", { c -> listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)) }, notifState),
    Target("channel_notifications", "Канал уведомлений «Пробник: П19»", { c ->
        c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Пробник: П19", NotificationManager.IMPORTANCE_DEFAULT))
        listOf(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL),
        )
    }, channelState),
    Target("battery_request", "Запрос «батарея без ограничений»", { c -> listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(c))) }, batteryState),
    Target("battery_list", "Список оптимизации батареи", { listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, batteryState),
    Target("battery_samsung", "Батарея пробника (экран Samsung)", { c -> listOfNotNull(component(c, "battery"), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c))) }, batteryState),
    Target("auto_revoke", "Удалять разрешения, если не используется", { c ->
        listOf(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, pkgUri(c)), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c)))
    }, autoRevokeState),
    Target("dnd_access", "Доступ к «Не беспокоить»", { listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }, dndState),
    Target("exact_alarm", "Точные будильники", { c -> listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(c))) }, exactState),
    Target("unknown_sources", "Установка неизвестных приложений", { c -> listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri(c))) }, installsState),
    // Публичного интента Auto Blocker нет [?]; запасной — «Безопасность».
    Target("auto_blocker", "Auto Blocker (экран Samsung)", { c -> listOfNotNull(component(c, "auto_blocker"), Intent(Settings.ACTION_SECURITY_SETTINGS)) }),
    Target("storage", "Хранилище", { listOf(Intent(StorageManager.ACTION_MANAGE_STORAGE)) }),
    Target("bluetooth", "Bluetooth", { listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }),
    Target("wifi", "Wi-Fi", { listOf(Intent(Settings.ACTION_WIFI_SETTINGS)) }),
)

private val verdicts = listOf("Нужный экран", "Рядом (одно касание)", "Не тот", "Ничего не открылось")

object SettingsIntentsProbe : Probe(
    "П19", "Переходы в настройки: интенты на One UI",
    "Для каждого «Открыть настройки» из продукта есть цепочка интентов, ведущая на нужный экран или в одно касание от него — из приложения и из листа.",
    needsHuman = true,
    steps = listOf(
        "Заранее (по желанию): открыть на телефоне экран боковой кнопки и выполнить на Mac scripts/top-activity.sh side_key; так же battery (батарея пробника) и auto_blocker. Без этого берутся запасные экраны.",
        "Выбрать, откуда запускать: из приложения, из листа или оба.",
        "Для каждого экрана: «Открыть» → посмотреть → «Назад» → ответить, что открылось. «Пропустить» и «Закончить» — в любой момент.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        log("components", Components.all(ctx).ifEmpty { "нет (scripts/top-activity.sh не запускали)" })
        val src = ask("Откуда открывать экраны?", listOf("Из приложения", "Из листа", "Оба"))
        val sources = when (src) {
            "Из приложения" -> listOf(false)
            "Из листа" -> listOf(true)
            else -> listOf(false, true)
        }
        val rows = mutableListOf<String>()
        var good = 0
        var bad = 0
        var tested = 0
        loop@ for ((n, t) in targets.withIndex()) {
            val chain = t.chain(ctx)
            val resolved = chain.map { i -> i to ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) }
            resolved.forEach { (i, ri) ->
                log("resolve", "${t.key}: ${i.action ?: i.component?.flattenToShortString()} → ${ri?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "не резолвится"}")
            }
            val intent = resolved.firstOrNull { it.second != null }?.first ?: chain.first()
            for (fromSheet in sources) {
                val where = if (fromSheet) "из листа" else "из приложения"
                when (ask("[${n + 1}/${targets.size}] ${t.label} ($where): откроется экран — посмотрите и вернитесь «Назад».", listOf("Открыть", "Пропустить", "Закончить"))) {
                    "Пропустить" -> continue
                    "Закончить" -> break@loop
                }
                tested++
                val before = t.state?.invoke(ctx)
                val (verdict, opened) = coroutineScope {
                    val open = async { openViaTrampoline(Intent(intent), fromSheet) }
                    val v = ask("${t.label} ($where): что открылось? Ответьте, когда вернётесь.", verdicts)
                    val o = withTimeoutOrNull(2000) { open.await() }
                    open.cancel()
                    v to o
                }
                val (err, back, t0) = opened ?: Triple(null, null, 0L)
                val after = t.state?.invoke(ctx)
                val sheetNow = if (fromSheet) ", лист после возврата виден=${Sheet.visible.value}" else ""
                val row = "${t.key} $where: $verdict" +
                    (err?.let { ", ошибка $it" } ?: "") +
                    (if (back != null) ", «Назад» вернул в пробник через ${back - t0} мс" else ", «Назад» в пробник не вернул") +
                    (if (before != null) ", $before → $after" else "") + sheetNow
                log("screen", row)
                rows += "${t.key}/${if (fromSheet) "лист" else "прил"}: $verdict"
                when (verdict) {
                    verdicts[0], verdicts[1] -> good++
                    else -> bad++
                }
            }
        }
        summary("проверено $tested: нужный или рядом — $good, мимо — $bad. " + rows.joinToString("; "))
        return when {
            tested == 0 -> Status.NOT_RUN
            bad == 0 && tested >= targets.size * sources.size -> Status.PASS
            bad == 0 -> Status.PARTIAL
            else -> Status.FAIL
        }
    }
}

private class PermGroup(val key: String, val label: String, val perms: Array<String>)

private val groups = listOf(
    PermGroup("mic", "Микрофон", arrayOf(Manifest.permission.RECORD_AUDIO)),
    PermGroup("camera", "Камера", arrayOf(Manifest.permission.CAMERA)),
    PermGroup("notifications", "Уведомления", arrayOf(Manifest.permission.POST_NOTIFICATIONS)),
    PermGroup("calendar", "Календарь", arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
    PermGroup("location", "Местоположение", arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    PermGroup("contacts", "Контакты", arrayOf(Manifest.permission.READ_CONTACTS)),
    // READ_CALL_LOG — hard-restricted: выдаётся, только если установщик разрешил [док/AOSP].
    PermGroup("call_log", "Журнал звонков", arrayOf(Manifest.permission.READ_CALL_LOG)),
)

object PermsFromSheetProbe : Probe(
    "П20", "Разрешения из листа",
    "Микрофон и остальные разрешения запрашиваются прямо из листа через батут; лист после ответа виден, запись стартует без второго нажатия. Плюс двойной отказ, «только в этот раз», выключенные в шторке микрофон и камера.",
    needsHuman = true,
    steps = listOf(
        "Поставить без выдачи разрешений: scripts/install-nogrant.sh (или pm revoke — проверка подскажет команду).",
        "По каждому разрешению: открыть лист, запросить из него, ответить «Разрешить», сказать, где было окно.",
        "Двойной отказ микрофона → «О приложении» → разрешить там → вернуться.",
        "«Только в этот раз» (нужен снова отозванный микрофон) — отзыв через 1/5/30 минут после закрытия листа.",
        "Выключить «Доступ к микрофону» и «Доступ к камере» в шторке — что отдают AudioRecord и Camera2.",
    ),
) {
    /** Отметки проверки отзыва «только в этот раз», минуты после закрытия листа. */
    var oneTimeChecksMin = listOf(1L, 5L, 30L)

    private fun ProbeScope.granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override suspend fun ProbeScope.run(): Status {
        val already = groups.flatMap { it.perms.toList() }.filter { granted(it) }
        if (already.isNotEmpty()) {
            log("already_granted", already.joinToString())
            val cmd = already.joinToString("; ") { "adb shell pm revoke ${ctx.packageName} $it" }
            log("revoke_cmd", cmd)
            val a = ask(
                "Уже выданы: ${already.joinToString { it.substringAfterLast('.') }}. Для честного опыта — scripts/install-nogrant.sh или на Mac: $cmd (процесс перезапустится, потом снова П20).",
                listOf("Продолжить как есть", "Стоп"),
            )
            if (a == "Стоп") {
                summary("остановлено: разрешения уже выданы — нужен install-nogrant.sh")
                return Status.NOT_RUN
            }
        }

        var micOk: Boolean? = null
        var micWindowMissing = false
        val rows = mutableListOf<String>()
        loop@ for ((n, g) in groups.withIndex()) {
            if (g.perms.all { granted(it) }) {
                rows += "${g.key}: уже выдано, пропуск"
                continue
            }
            when (ask("[${n + 1}/${groups.size}] ${g.label}: открою лист и запрошу из него. Разрешите.", listOf("Запросить", "Пропустить", "Закончить"))) {
                "Пропустить" -> continue
                "Закончить" -> break@loop
            }
            val (res, err, reopened) = coroutineScope {
                // Вернулся ли лист сам после ответа — ловим новое открытие в течение 3 с после результата.
                val reopen = async { withTimeoutOrNull(130_000) { Sheet.opens.first() } }
                val t0 = SystemClock.uptimeMillis()
                val (r, e) = requestViaTrampoline(g.perms, fromSheet = true)
                if (r != null) metric("${g.key}_request_to_result_ms", r.tResult - t0)
                delay(800)
                val ro = withTimeoutOrNull(2200) { reopen.await() }
                reopen.cancel()
                Triple(r, e, ro)
            }
            val ok = res?.granted?.values?.any { it } == true
            val visible = Sheet.visible.value
            val where = ask("${g.label}: где было системное окно?", listOf("Под листом", "Над листом", "Лист пропал, окно на экране", "Окна не было"))
            val row = "${g.key}: " + when {
                err != null -> "ошибка запуска $err"
                res == null -> "ответа не дождались"
                res.granted.isEmpty() -> "закрыто без ответа"
                else -> "выдано=${res.granted.filterValues { it }.keys.map { it.substringAfterLast('.') }}, отказ=${res.granted.filterValues { !it }.keys.map { it.substringAfterLast('.') }}, rationale=${res.rationale.values}"
            } + "; окно: $where; лист после ответа виден=$visible" + (reopened?.let { ", лист открылся заново (#${it.seq})" } ?: "")
            log("perm", row)
            rows += row
            if (g.key == "mic") {
                micWindowMissing = where == "Окна не было"
                micOk = ok
                if (ok && visible) {
                    val st = recordFor(ctx, 1500)
                    log("mic_after_grant", st.describe())
                    metric("mic_after_grant_rms", st.rms)
                    if (!st.ok) micOk = false
                }
            }
            if (g.key == "call_log") {
                val installer = runCatching { ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName }.getOrNull()
                log("call_log_installer", "установщик=$installer, выдано=$ok")
            }
        }

        doubleDenial()
        oneTime()
        sensorToggles()

        summary(rows.joinToString("; "))
        return when {
            micOk == true -> Status.PASS
            micOk == false && micWindowMissing -> Status.FAIL
            else -> Status.PARTIAL
        }
    }

    private suspend fun ProbeScope.doubleDenial() {
        val mic = Manifest.permission.RECORD_AUDIO
        if (granted(mic)) {
            log("double_denial", "пропуск: микрофон выдан (нужен adb shell pm revoke ${ctx.packageName} $mic)")
            return
        }
        if (ask("Двойной отказ: запрошу микрофон дважды из приложения — оба раза «Не разрешать».", listOf("Начать", "Пропустить")) == "Пропустить") return
        repeat(2) { i ->
            val (r, e) = requestViaTrampoline(arrayOf(mic), fromSheet = false)
            log("double_denial", "запрос ${i + 1}: ${e ?: r}")
        }
        // Третий запрос после «больше не спрашивать» должен вернуться сразу, без окна.
        val t0 = SystemClock.uptimeMillis()
        val (r3, _) = requestViaTrampoline(arrayOf(mic), fromSheet = false, timeoutMs = 10_000)
        val dt = SystemClock.uptimeMillis() - t0
        metric("third_request_ms", dt)
        log("double_denial", "третий запрос: $r3 за $dt мс (быстро и rationale=false — система больше не спрашивает)")
        if (granted(mic)) return
        val target = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
        coroutineScope {
            val open = async { openViaTrampoline(target, fromSheet = false) }
            tell("Откроется «О приложении»: разрешите микрофон в «Разрешениях» и вернитесь «Назад».")
            val o = withTimeoutOrNull(2000) { open.await() }
            open.cancel()
            log("app_details", "ошибка=${o?.first}, «Назад» вернул=${o?.second != null}, микрофон теперь=${granted(mic)}")
        }
    }

    private suspend fun ProbeScope.oneTime() {
        val mic = Manifest.permission.RECORD_AUDIO
        if (granted(mic)) {
            log("one_time", "пропуск: микрофон выдан; для опыта: adb shell pm revoke ${ctx.packageName} $mic и снова П20")
            return
        }
        if (ask("«Только в этот раз»: запрошу микрофон из листа — выберите «Только в этот раз».", listOf("Запросить", "Пропустить")) == "Пропустить") return
        val (r, e) = requestViaTrampoline(arrayOf(mic), fromSheet = true)
        log("one_time", "ответ: ${e ?: r}")
        if (!granted(mic)) return
        Sheet.close()
        val tHide = SystemClock.uptimeMillis()
        coroutineScope {
            val stop = async { ask("Жду отзыва разрешения: отметки ${oneTimeChecksMin.joinToString()} мин после закрытия листа. Телефон можно отложить.", listOf("Хватит")) }
            for (m in oneTimeChecksMin) {
                while (!stop.isCompleted && SystemClock.uptimeMillis() - tHide < m * 60_000) delay(1000)
                if (stop.isCompleted) break
                val g = granted(mic)
                log("one_time", "через $m мин: выдано=$g")
                if (!g) {
                    metric("one_time_revoked_by_min", m)
                    break
                }
            }
            stop.cancel()
        }
    }

    private suspend fun ProbeScope.sensorToggles() {
        if (ask("Выключите в шторке «Доступ к микрофону» и нажмите «Готово».", listOf("Готово", "Пропустить")) == "Готово") {
            if (!granted(Manifest.permission.RECORD_AUDIO)) {
                log("mic_toggle", "пропуск: нет разрешения на микрофон")
            } else {
                Sheet.open()
                val st = recordFor(ctx, 3000)
                log("mic_toggle", "при выключенном доступе: ${st.describe()}")
                val w = ask("Показала ли система своё окно «Разблокировать микрофон» поверх листа?", listOf("Да", "Нет"))
                log("mic_toggle", "окно системы: $w")
                Sheet.close()
            }
        }
        if (ask("Выключите в шторке «Доступ к камере» и нажмите «Готово».", listOf("Готово", "Пропустить")) == "Готово") {
            if (!granted(Manifest.permission.CAMERA)) {
                log("camera_toggle", "пропуск: нет разрешения на камеру")
            } else {
                Sheet.open()
                log("camera_toggle", "Camera2 при выключенном доступе: ${tryOpenCamera(ctx)}")
                val w = ask("Показала ли система своё окно поверх листа?", listOf("Да", "Нет"))
                log("camera_toggle", "окно системы: $w")
                Sheet.close()
            }
        }
        tell("Верните в шторке доступ к микрофону и камере.")
    }
}
