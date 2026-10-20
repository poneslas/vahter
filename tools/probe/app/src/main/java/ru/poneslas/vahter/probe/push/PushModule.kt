package ru.poneslas.vahter.probe.push

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.UnifiedPush
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.Module
import ru.poneslas.vahter.probe.assist.OpenRecord
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.core.Probe
import ru.poneslas.vahter.probe.core.ProbeScope
import ru.poneslas.vahter.probe.core.Status
import ru.poneslas.vahter.probe.core.fmt
import ru.poneslas.vahter.probe.core.median
import ru.poneslas.vahter.probe.entry.EntryLauncher
import ru.poneslas.vahter.probe.entry.entryToFrame

object PushModule : Module {
    override val probes = listOf(PushProbe)

    override fun init(ctx: Context) {
        Notifications.ensureChannel(ctx)
        PushLog.endpoint.value = PushLog.savedEndpoint(ctx)
    }
}

object PushProbe : Probe(
    "П12", "Пуш ntfy открывает лист",
    "Пуш через UnifiedPush/ntfy доходит ≤ 5 с; нажатие на уведомление открывает лист с нужными args ≤ 500 мс, шторка закрыта.",
    needsHuman = true,
    steps = listOf(
        "Поставить ntfy (дистрибьютор UnifiedPush); батарея ntfy и пробника — «Без ограничений».",
        "Пробник регистрируется, показывает endpoint и кладёт в буфер команду curl — отправить её с Mac 3 раза.",
        "По желанию: заблокировать телефон на 30 мин и отправить curl — проверка сна.",
        "Пробник показывает три уведомления (а, б, в) — нажать каждое по 3 раза, часть — с заблокированного экрана; после нажатия уведомление приходит снова через 3 с.",
        "Время меряется от старта батута или приёмника до кадра листа: само нажатие на уведомление пробнику не видно.",
        "Без ntfy проверяется только нажатие на уведомление (локальный «пуш»).",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val pm = ctx.getSystemService(PowerManager::class.java)
        log("battery", "пробник без ограничений=${pm.isIgnoringBatteryOptimizations(ctx.packageName)}, ntfy=${pm.isIgnoringBatteryOptimizations("io.heckel.ntfy")}")
        if (!Notifications.enabled(ctx)) {
            tell("Уведомления пробнику запрещены. Дайте разрешение («Разрешения» на главном экране) и нажмите «Готово».")
            if (!Notifications.enabled(ctx)) {
                summary("Уведомления запрещены — проверять нечего")
                return Status.FAIL
            }
        }
        val up = unifiedPush()
        val tap = taps()
        summary("${up.second}; ${tap.second}")
        return when {
            tap.first == Status.FAIL -> Status.FAIL
            tap.first == Status.PASS && up.first == Status.PASS -> Status.PASS
            else -> Status.PARTIAL
        }
    }

    /** Регистрация, endpoint, задержка доставки. */
    private suspend fun ProbeScope.unifiedPush(): Pair<Status, String> {
        val distributors = UnifiedPush.getDistributors(ctx)
        log("distributors", distributors.joinToString().ifEmpty { "нет" })
        if (distributors.isEmpty()) return Status.NOT_RUN to "UnifiedPush: дистрибьютора нет (ntfy не стоит) — доставка не проверена"
        if (ask("UnifiedPush: дистрибьюторы ${distributors.joinToString()}. Проверяем доставку?", listOf("Да", "Пропустить")) == "Пропустить")
            return Status.NOT_RUN to "UnifiedPush пропущен"

        PushLog.failure.value = null
        val picked = withTimeoutOrNull(30_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { c ->
                UnifiedPush.tryUseCurrentOrDefaultDistributor(ctx) { ok -> if (c.isActive) c.resumeWith(Result.success(ok)) }
            }
        }
        log("distributor", "выбран=$picked сохранён=${UnifiedPush.getSavedDistributor(ctx)}")
        if (picked != true) {
            val d = distributors.first()
            UnifiedPush.saveDistributor(ctx, d)
            log("distributor", "дистрибьютор по умолчанию не выбран, сохраняю $d")
        }
        UnifiedPush.register(ctx)
        val endpoint = withTimeoutOrNull(20_000) { PushLog.endpoint.filterNotNull().first() }
        if (endpoint == null) {
            log("register", "endpoint не пришёл за 20 с, ошибка=${PushLog.failure.value}")
            return Status.FAIL to "UnifiedPush: endpoint не получен (${PushLog.failure.value ?: "без ошибки"})"
        }
        val curl = "curl -d \"{\\\"t\\\":\$(python3 -c 'import time;print(int(time.time()*1000))')}\" $endpoint"
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("curl", curl))
        log("endpoint", endpoint)
        log("curl", curl)

        val start = System.currentTimeMillis()
        coroutineScope {
            val listen = launch {
                PushLog.received.collect { r ->
                    log("push", "задержка ${r.delayMs} мс, расшифрован=${r.decrypted}, idle=${r.idle}, экран=${r.screenOn}")
                    Sheet.setStatus("П12: пуш за ${r.delayMs} мс")
                }
            }
            ask("Endpoint: $endpoint\nКоманда curl в буфере обмена — перешлите её на Mac и выполните 3 раза (экран включён). Потом «Готово».", listOf("Готово"))
            listen.cancel()
        }
        val awake = PushLog.since(ctx, start)
        val sleepStart = System.currentTimeMillis()
        val sleep = ask("Сон: заблокируйте телефон на 30 минут, потом выполните curl ещё раз, разблокируйте и нажмите «Готово».", listOf("Готово", "Пропустить"))
        val asleep = if (sleep == "Готово") PushLog.since(ctx, sleepStart) else emptyList()

        awake.mapNotNull { it.delayMs }.forEach { metric("push_delay_awake_ms", it) }
        asleep.mapNotNull { it.delayMs }.forEach { metric("push_delay_sleep_ms", it) }
        val a = awake.mapNotNull { it.delayMs?.toDouble() }
        val s = asleep.mapNotNull { it.delayMs?.toDouble() }
        val text = buildString {
            append("UnifiedPush: при экране ${awake.size} пушей")
            if (a.isNotEmpty()) append(", медиана ${fmt(median(a))} мс")
            if (sleep == "Готово") append("; после сна ${asleep.size}" + if (s.isNotEmpty()) ", ${fmt(median(s))} мс" else "")
        }
        val ok = a.isNotEmpty() && median(a) <= 5000 && (sleep != "Готово" || (s.isNotEmpty() && median(s) <= 5000))
        return (if (ok) Status.PASS else if (a.isEmpty()) Status.FAIL else Status.PARTIAL) to text
    }

    /** Нажатия на три варианта уведомления. */
    private suspend fun ProbeScope.taps(): Pair<Status, String> {
        val srcs = Notifications.variants.associateBy { it.src }
        val whenPost = ask("Уведомления: прислать сейчас или через 15 с (успеете заблокировать экран)?", listOf("Сейчас", "Через 15 с"))
        if (whenPost != "Сейчас") delay(15_000)
        Notifications.postAll(ctx, "Локальный пуш пробника")
        val got = mutableListOf<OpenRecord>()
        coroutineScope {
            val opens = launch {
                Sheet.opens.collect { r ->
                    val v = srcs[r.args.getString("src")] ?: return@collect
                    got += r
                    log("open", "${v.key}: старт→кадр ${r.entryToFrame()} мс, mode=${r.mode}, ждали сервис ${r.args.getLong("wait_ms", -1)} мс, ${r.describe()}")
                    Sheet.setStatus("П12 ${v.key}: ${got.count { it.args.getString("src") == v.src }} из 3")
                    App.scope.launch { delay(3000); Notifications.post(ctx, v, "Повтор для варианта ${v.key}") }
                }
            }
            val events = launch { EntryLauncher.events.collect { log("entry", it) } }
            val done = async { ask("Нажмите каждое уведомление (а, б, в) по 3 раза, закрывая лист; часть — с блокировки. Потом «Готово».", listOf("Готово")) }
            done.await()
            opens.cancel(); events.cancel()
        }
        Sheet.setStatus("")
        val shade = ask("После нажатия шторка уведомлений закрывалась сама?", listOf("Всегда", "Не всегда", "Оставалась открытой"))
        val parts = mutableListOf<String>()
        var anyOk = false
        Notifications.variants.forEach { v ->
            val rs = got.filter { it.args.getString("src") == v.src }
            val argsOk = rs.count { it.mode == "assistant" }
            val t = rs.mapNotNull { it.entryToFrame()?.toDouble() }
            t.forEach { metric("tap_to_frame_ms_${v.key}", it) }
            metric("opens_${v.key}", rs.size)
            if (t.isNotEmpty() && median(t) <= 500 && argsOk == rs.size) anyOk = true
            parts += "${v.key} ${rs.size} откр." + (if (t.isNotEmpty()) " ${fmt(median(t))} мс" else "") + if (argsOk < rs.size) " (args потеряны)" else ""
        }
        val status = when {
            got.isEmpty() -> Status.FAIL
            anyOk && shade == "Всегда" -> Status.PASS
            else -> Status.PARTIAL
        }
        return status to "нажатия: ${parts.joinToString(", ")}; шторка: $shade"
    }
}
