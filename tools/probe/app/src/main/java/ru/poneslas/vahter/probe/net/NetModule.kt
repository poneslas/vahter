package ru.poneslas.vahter.probe.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.Module
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.assist.SheetAction
import ru.poneslas.vahter.probe.core.Config
import ru.poneslas.vahter.probe.core.Probe
import ru.poneslas.vahter.probe.core.ProbeScope
import ru.poneslas.vahter.probe.core.Status
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

object NetModule : Module {
    override val probes = listOf(NetProbe)
    override val actions = listOf(
        SheetAction("net", "Сеть из листа") { _ ->
            val scope = ProbeScope(App.instance, NetProbe.id)
            val r = connectAll(App.instance, "лист_вручную")
            Sheet.setStatus("П13: " + r.joinToString("; ") { it.short() })
            r.forEach { scope.log("connect", it.full()) }
        },
    )
}

data class Target(val name: String, val hostPort: String)

data class Attempt(val target: Target, val where: String, val permitted: Boolean, val ok: Boolean, val ms: Long, val dnsMs: Long?, val error: String?) {
    fun short() = "${target.name} ${if (ok) "ok" else "нет"} $ms мс"
    fun full() = "${target.name} (${target.hostPort}) [$where, разрешение=$permitted]: " +
        (if (ok) "ok" else "ошибка $error") + ", $ms мс" + (dnsMs?.let { ", DNS $it мс" } ?: "")
}

fun localNetworkGranted(ctx: Context) = ctx.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

fun targets(): List<Target> = listOf(
    Target("lan", Config["lan"]),
    Target("domain", Config["domain"]),
    Target("tailscale", Config["tailscale"]),
).filter { it.hostPort.isNotBlank() }

/** TCP-подключение с таймаутом 5 с: при запрете LAN TCP обычно падает по таймауту, UDP — EPERM [док]. */
suspend fun connect(ctx: Context, t: Target, where: String): Attempt = withContext(Dispatchers.IO) {
    val permitted = localNetworkGranted(ctx)
    val host = t.hostPort.substringBeforeLast(':')
    val port = t.hostPort.substringAfterLast(':').toIntOrNull() ?: 443
    val t0 = SystemClock.uptimeMillis()
    var dnsMs: Long? = null
    try {
        val addr = InetAddress.getByName(host)
        dnsMs = SystemClock.uptimeMillis() - t0
        Socket().use { it.connect(InetSocketAddress(addr, port), 5000) }
        Attempt(t, where, permitted, true, SystemClock.uptimeMillis() - t0, dnsMs, null)
    } catch (e: Exception) {
        Attempt(t, where, permitted, false, SystemClock.uptimeMillis() - t0, dnsMs, "${e.javaClass.simpleName}: ${e.message}")
    }
}

suspend fun connectAll(ctx: Context, where: String) = targets().map { connect(ctx, it, where) }

object NetProbe : Probe(
    "П13", "Сервер в локальной сети, Auto Blocker",
    "При targetSdk 37 LAN закрыт без ACCESS_LOCAL_NETWORK; домен работает всегда; что с адресом Tailscale.",
    needsHuman = true,
    steps = listOf(
        "Впишите адреса в «Отчёт и настройки»: LAN (Mac в той же Wi-Fi, python3 probe/scripts/serve.py), домен, Tailscale (если стоит).",
        "install.sh ставит с -g — разрешение уже выдано. Для опыта «без разрешения»: Настройки → Приложения → Пробник → Разрешения → Устройства поблизости → Не разрешать, потом снова П13.",
        "Пробник подключается без разрешения, просит его, повторяет — из активности и из открытого листа.",
        "Auto Blocker: один раз включить, попробовать adb install, текст ошибки — в заметку.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val ts = targets()
        if (ts.isEmpty()) {
            summary("Нет адресов — заполните «Отчёт и настройки»")
            return Status.FAIL
        }
        logLinks()
        val before = mutableListOf<Attempt>()
        if (localNetworkGranted(ctx)) {
            ask(
                "ACCESS_LOCAL_NETWORK уже выдано (install.sh ставит с -g). Без отзыва (шаг 2 на экране проверки) замер «без разрешения» не получится.",
                listOf("Продолжить только с разрешением"),
            )
        } else {
            before += round("без разрешения")
            val granted = request()
            log("permission_request", "granted=$granted")
            if (!granted) {
                summary("Без разрешения: " + before.joinToString("; ") { it.short() } + ". Разрешение не выдано — второй половины нет")
                return Status.PARTIAL
            }
        }
        val after = round("с разрешением")

        val ab = ask("Auto Blocker: включите его, на Mac попробуйте `adb install -r` любой сборки, текст ошибки — в заметку. Потом выключите.", listOf("Сделано", "Пропустить"))
        log("autoblocker", ab)

        fun at(list: List<Attempt>, name: String) = list.filter { it.target.name == name }
        val lanBefore = at(before, "lan")
        val lanAfter = at(after, "lan")
        val domainOk = (before + after).filter { it.target.name == "domain" }.all { it.ok }
        val ts2 = at(before, "tailscale") + at(after, "tailscale")
        summary(buildString {
            append("LAN: без разрешения ${lanBefore.map { it.ok }.ifEmpty { listOf("не мерили") }}, с ним ${lanAfter.map { it.ok }}")
            append("; домен ${if (domainOk) "работает" else "НЕ работает"}")
            if (ts2.isNotEmpty()) append("; Tailscale: " + ts2.joinToString { "${if (it.permitted) "с" else "без"} разр. ${if (it.ok) "ok" else "нет"}" })
        })
        return when {
            !domainOk || lanAfter.any { !it.ok } -> Status.FAIL
            lanBefore.isEmpty() -> Status.PARTIAL
            lanBefore.all { !it.ok } -> Status.PASS
            // LAN работает и без разрешения — ограничение не действует; для дизайна это не плохо, но не то, что ждали.
            else -> Status.PARTIAL
        }
    }

    /** Один круг: из активности (лист закрыт), затем из открытого листа. */
    private suspend fun ProbeScope.round(label: String): List<Attempt> {
        Sheet.close()
        val fromActivity = connectAll(ctx, "активность, $label")
        fromActivity.forEach { log("connect", it.full()); metric("${it.target.name}_${if (it.permitted) "perm" else "noperm"}_ms", it.ms) }
        val fromSheet = if (Sheet.open() != null) {
            connectAll(ctx, "лист, $label").also { Sheet.close() }
        } else {
            log("sheet", "лист не открылся — круг из листа пропущен"); emptyList()
        }
        fromSheet.forEach { log("connect", it.full()) }
        return fromActivity + fromSheet
    }

    private suspend fun ProbeScope.request(): Boolean {
        val d = CompletableDeferred<Boolean>()
        PermissionActivity.pending = d
        ctx.startActivity(
            Intent(ctx, PermissionActivity::class.java)
                .putExtra(PermissionActivity.EXTRA_PERMISSION, LOCAL_NETWORK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        // Окно разрешения может не открыться (запуск активности из фона) — тогда человек жмёт «Разрешения» на главном.
        return withTimeoutOrNull(60_000) { d.await() } ?: run {
            delay(100)
            localNetworkGranted(ctx)
        }
    }

    private fun ProbeScope.logLinks() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork
        val lp = cm.getLinkProperties(n)
        log("link", "адреса=${lp?.linkAddresses} dns=${lp?.dnsServers} iface=${lp?.interfaceName}")
        log("caps", cm.getNetworkCapabilities(n).toString())
    }
}
