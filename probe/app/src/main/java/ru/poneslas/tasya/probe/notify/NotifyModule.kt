package ru.poneslas.tasya.probe.notify

import android.app.role.RoleManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.TAG
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.core.Config
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status
import ru.poneslas.tasya.probe.core.fmt
import ru.poneslas.tasya.probe.core.median
import ru.poneslas.tasya.probe.entry.collectEntries
import ru.poneslas.tasya.probe.entry.entryToFrame
import ru.poneslas.tasya.probe.session.importance
import ru.poneslas.tasya.probe.session.importanceName
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object NotifyModule : Module {
    override val probes = listOf(AnswerAfterCloseProbe, AnswerHumanProbe, VisLossProbe)

    override fun init(ctx: Context) {
        // П23в: переходы «сервис помощника жив ↔ нет» пишутся в файл — так видно, успевает ли процесс
        // заметить потерю роли и показать уведомление, даже если прогон проверки при этом оборвётся.
        App.scope.launch {
            var prev = Sheet.serviceReady.value
            Sheet.serviceReady.collect { v ->
                if (v == prev) return@collect
                prev = v
                val role = ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
                val notified = if (!v) {
                    AnswerNotifier.post(ctx, AnswerNotifier.ID_VIS, "Тася больше не помощник?", "Сервис помощника остановлен; роль у пробника: $role")
                } else false
                val o = JSONObject().put("wall", System.currentTimeMillis()).put("ready", v).put("role", role).put("notified", notified)
                Log.i(TAG, "vis_state $o")
                runCatching { File(ctx.filesDir, "vis_state.jsonl").appendText(o.toString() + "\n") }
            }
        }
    }
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("probes", Context.MODE_PRIVATE)

/** Сервер на месте? Иначе П23 бессмысленна. */
private suspend fun serverAlive(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("${Config.server}/status").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        val code = c.responseCode
        c.disconnect()
        if (code == 200) null else "HTTP $code"
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
}

/**
 * Один прогон: открыть лист → начать читать поток вариантом [variant] → через 2 с закрыть лист →
 * ждать конца, снимая важность процесса. Возвращает прогон (null — не стартовал).
 */
private suspend fun ProbeScope.oneRun(variant: String, seconds: Int, closeAfterMs: Long = 2000): Sse.Run? {
    val id = "sse_${variant}_${System.currentTimeMillis()}"
    val r = Sheet.open()
    log("open", r?.describe() ?: "лист не открылся")
    val run = Sse.start(id, variant)
    when (variant) {
        "coroutine" -> App.scope.launch(Dispatchers.IO) { Sse.readBlocking(ctx.applicationContext, id, seconds) }
        "workmanager" -> {
            val req = OneTimeWorkRequestBuilder<SseWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf("id" to id, "seconds" to seconds))
                .build()
            runCatching { WorkManager.getInstance(ctx).enqueue(req) }.onFailure {
                error(it, "enqueue")
                return null
            }
        }
    }
    delay(closeAfterMs)
    val closed = Sheet.close()
    val tClose = SystemClock.uptimeMillis()
    log("close", "лист закрыт=$closed, событий к закрытию ${run.events}")
    val pm = ctx.getSystemService(PowerManager::class.java)
    val deadline = tClose + (seconds + 40) * 1000L
    var lastImp = -1
    while (!run.finished && SystemClock.uptimeMillis() < deadline) {
        val imp = importance()
        if (imp != lastImp) {
            log("importance", "+${(SystemClock.uptimeMillis() - tClose) / 1000} с: ${importanceName(imp)}, экран=${pm.isInteractive}")
            lastImp = imp
        }
        delay(5000)
    }
    val total = ((run.finishedAt ?: SystemClock.uptimeMillis()) - run.startedAt) / 1000.0
    log("result", "$variant: событий ${run.events}, пропусков ${run.skipped}, макс. пауза ${run.maxGapMs} мс, done=${run.done}, уведомление=${run.notified}, ошибка=${run.error}, ${fmt(total)} с")
    metric("${variant}_done", if (run.done) 1 else 0)
    metric("${variant}_events", run.events)
    metric("${variant}_max_gap_ms", run.maxGapMs)
    if (!run.finished) log("timeout", "$variant не закончил за ${seconds + 40} с после закрытия")
    return run
}

object AnswerAfterCloseProbe : Probe(
    "П23", "Ответ дочитывается после закрытия листа",
    "Поток ответа (SSE с scripts/serve.py) идёт 90 с; лист закрывают через 2 с. Процесс помощника дочитывает поток и сам показывает уведомление «ответ готов». Варианты: корутина в процессе, ускоренный WorkManager.",
    needsHuman = false,
    steps = listOf(
        "На Mac: python3 probe/scripts/serve.py (адрес — «Отчёт и настройки → Тестовый сервер»).",
        "Пробник сам: лист → поток → закрыть лист → ждать до конца потока → уведомление. Два варианта по очереди, ~4 минуты.",
        "С выключенным экраном — П23ч.",
    ),
) {
    /** Длина «ответа» на сервере, с. */
    var seconds = 90

    override suspend fun ProbeScope.run(): Status {
        serverAlive()?.let {
            summary("сервер ${Config.server} недоступен ($it) — запустите scripts/serve.py")
            return Status.FAIL
        }
        val results = listOf("coroutine", "workmanager").mapNotNull { v -> oneRun(v, seconds)?.let { v to it } }
        val ok = results.filter { it.second.done }
        summary(results.joinToString("; ") { (v, r) ->
            "$v: ${if (r.done) "дочитал" else "не дочитал"} (${r.events} событий, макс. пауза ${r.maxGapMs} мс${r.error?.let { ", $it" } ?: ""}), уведомление=${r.notified}"
        })
        return when {
            ok.size == results.size && results.isNotEmpty() && ok.all { it.second.notified } -> Status.PASS
            ok.isNotEmpty() -> Status.PARTIAL
            else -> Status.FAIL
        }
    }
}

object AnswerHumanProbe : Probe(
    "П23ч", "Уведомление «ответ готов»: экран выключен, нажатие, ответ из шторки",
    "То же, что П23, но с выключенным экраном; нажатие на уведомление открывает лист; ответ текстом из шторки уходит на сервер.",
    needsHuman = true,
    steps = listOf(
        "Нужен serve.py (как в П23).",
        "Поток с выключенным экраном: после «Старт» сразу выключить экран, включить через 2 минуты.",
        "Нажать на уведомление «Тася ответила» 3 раза: с разблокированного и заблокированного экрана.",
        "Ответить из шторки кнопкой «Ответить» — с заблокированным экраном.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        serverAlive()?.let {
            summary("сервер ${Config.server} недоступен ($it) — запустите scripts/serve.py")
            return Status.FAIL
        }
        val lines = mutableListOf<String>()
        val verdicts = mutableListOf<Status>()

        // 1. Экран выключен
        if (ask("Шаг 1: поток 90 с с выключенным экраном. После «Старт» сразу выключите экран кнопкой питания и включите через 2 минуты.", listOf("Старт", "Пропустить")) == "Старт") {
            val r = oneRun("coroutine", 90, closeAfterMs = 4000)
            if (r != null) {
                val seen = ask("Пришло уведомление «Тася ответила»? (результат: ${if (r.done) "поток дочитан" else "не дочитан"}, ${r.events} событий)", listOf("Да", "Нет"))
                lines += "экран выкл.: done=${r.done}, ${r.events} событий, уведомление видно=$seen"
                verdicts += if (r.done && seen == "Да") Status.PASS else if (r.done) Status.PARTIAL else Status.FAIL
            }
        }

        // 2. Нажатие на уведомление
        if (ask("Шаг 2: нажатие на уведомление открывает лист. Показать уведомление?", listOf("Да", "Пропустить")) == "Да") {
            AnswerNotifier.postAnswer(ctx, "Нажмите — должен открыться лист")
            val opens = coroutineScope {
                // После каждого нажатия то же уведомление приходит снова через 3 с.
                val repost = launch {
                    Sheet.opens.collect { r ->
                        if (r.args.getString("src") != AnswerNotifier.SRC) return@collect
                        launch { delay(3000); AnswerNotifier.postAnswer(ctx, "Нажмите ещё раз") }
                    }
                }
                collectEntries(
                    setOf(AnswerNotifier.SRC),
                    "Нажмите на «Тася ответила» 3 раза (после каждого нажатия закройте лист, уведомление придёт снова через 3 с). Одно — с заблокированного экрана. Потом «Готово».",
                    3,
                ).also { repost.cancel() }
            }
            val times = opens.mapNotNull { it.entryToFrame()?.toDouble() }
            times.forEach { metric("tap_to_frame_ms", it) }
            val clean = ask("Шторка закрылась и лист был сверху?", listOf("Да", "Шторка осталась", "Лист не открылся"))
            lines += "нажатие: ${opens.size} откр., медиана ${if (times.isEmpty()) "—" else fmt(median(times)) + " мс"} (от батута), $clean"
            verdicts += when {
                opens.isEmpty() -> Status.FAIL
                clean == "Да" && times.isNotEmpty() && median(times) <= 500 -> Status.PASS
                else -> Status.PARTIAL
            }
        }

        // 3. RemoteInput
        if (ask("Шаг 3: ответ из шторки. Заблокируйте экран, опустите шторку, «Ответить» в уведомлении, напишите что-нибудь.", listOf("Показать уведомление", "Пропустить")) == "Показать уведомление") {
            AnswerNotifier.postAnswer(ctx, "Ответьте прямо из шторки")
            val got = mutableListOf<String>()
            coroutineScope {
                val collector = launch { ReplyReceiver.events.collect { got += it; log("reply", it); Sheet.setStatus("ответов: ${got.size}") } }
                ask("Ответьте из шторки (можно несколько раз). Потом «Готово».", listOf("Готово"))
                collector.cancel()
            }
            val ok = got.count { JSONObject(it).optBoolean("ok") }
            got.forEach { metric("reply_ms", JSONObject(it).optLong("ms")) }
            lines += "ответ из шторки: ${got.size} попыток, дошло $ok"
            verdicts += when {
                got.isEmpty() -> Status.FAIL
                ok == got.size -> Status.PASS
                else -> Status.PARTIAL
            }
        }

        summary(lines.joinToString("; ").ifEmpty { "все шаги пропущены" })
        return when {
            verdicts.isEmpty() -> Status.NOT_RUN
            verdicts.all { it == Status.PASS } -> Status.PASS
            verdicts.all { it == Status.FAIL } -> Status.FAIL
            else -> Status.PARTIAL
        }
    }
}

object VisLossProbe : Probe(
    "П23в", "Потеря роли помощника: узнаёт ли приложение без запуска",
    "Когда пользователь выбирает другого помощника, система отвязывает наш сервис. Пробник пишет переход «сервис жив → нет» в files/vis_state.jsonl и пробует сразу показать уведомление. Колбэк смены держателя роли — системный API, поэтому смотрим onShutdown косвенно, через Sheet.serviceReady [?].",
    needsHuman = true,
    steps = listOf(
        "Пробник запоминает время и открывает настройки помощника.",
        "Выберите другого помощника, подождите 10 с, посмотрите, появилось ли уведомление «Тася больше не помощник?».",
        "Верните роль пробнику (настройки или scripts/install.sh) и снова запустите П23в → «Посчитать».",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val p = prefs(ctx)
        val marker = p.getLong("p23v_marker", 0)
        val choice = if (marker == 0L) "Новый замер" else ask("Есть начатый замер. Что делаем?", listOf("Посчитать", "Новый замер"))
        if (choice == "Новый замер") {
            p.edit { putLong("p23v_marker", System.currentTimeMillis()) }
            log("start", "serviceReady=${Sheet.serviceReady.value}")
            log("settings", ru.poneslas.tasya.probe.assist.openAssistantSettings(ctx))
            val seen = ask("Выберите другого помощника, подождите 10 с. Появилось уведомление «Тася больше не помощник?»?", listOf("Да", "Нет"))
            log("human_saw_notification", seen)
            tell("Верните роль пробнику и снова запустите П23в → «Посчитать». Если процесс пробника не умер — можно «Готово» сразу после возврата роли.")
        }
        val m = p.getLong("p23v_marker", 0)
        val entries = runCatching { File(ctx.filesDir, "vis_state.jsonl").readLines() }.getOrDefault(emptyList())
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optLong("wall") >= m }
        entries.forEach { log("vis_state", it.toString()) }
        val lost = entries.firstOrNull { !it.optBoolean("ready") }
        val back = entries.lastOrNull { it.optBoolean("ready") }
        summary(
            when {
                lost == null -> "переход «сервис остановлен» не записан — процесс не узнал о потере роли (или умер раньше)"
                else -> "сервис остановлен через ${(lost.optLong("wall") - m) / 1000} с после начала; уведомление показано=${lost.optBoolean("notified")}" +
                    (back?.let { "; сервис снова жив через ${(it.optLong("wall") - lost.optLong("wall")) / 1000} с" } ?: "")
            },
        )
        return when {
            lost != null && lost.optBoolean("notified") -> Status.PASS
            lost != null -> Status.PARTIAL
            else -> Status.FAIL
        }
    }
}
