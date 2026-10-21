package ru.poneslas.vahter.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.poneslas.vahter.R
import ru.poneslas.vahter.core.BrainDesc
import ru.poneslas.vahter.core.HermesRun
import ru.poneslas.vahter.core.Modes
import ru.poneslas.vahter.tr
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLException

/** Подключение к Hermes: адрес (`https://…`) и ключ телефона. Ключ — не ключ API Hermes: сервер (Caddy) меняет его на свой (Архитектура 9.8). */
data class HermesLink(val url: String, val token: String)

/**
 * Hermes через его встроенный API-сервер (Архитектура 9.0). Ход — задача на сервере (`POST /v1/runs`): она живёт независимо
 * от соединения, события читаются потоком `GET /v1/runs/{id}/events` с любого места (`last_seq`), статус и итог — `GET /v1/runs/{id}`.
 * Поэтому «задал и ушёл» работает без пуша: оборвалась связь — переподключаемся; приложение убили — при запуске дочитываем ([resume]).
 * Отличия от [ApiBrain]:
 * - история режима живёт на сервере: шлём промпт режима и новую реплику, сессию держит `session_id`;
 * - инструменты выполняются на сервере, действий телефона у Hermes нет; его шаги — события `tool.started`;
 * - опасную команду сервер просит подтвердить событием `approval.request` — карточка в ленте, ответ `POST /v1/runs/{id}/approval`;
 * - «дописал вдогонку» — `POST /v1/runs/{id}/steer`: реплика вливается в идущий ход, как в Telegram (Архитектура 9.4).
 */
class HermesBrain(
    private val desc: BrainDesc,
    private val link: () -> HermesLink?,
    /** Сессия режима в Hermes: сохранённый id или null — новая. После 30 минут тишины — новая (Архитектура 9.7). */
    private val session: (modeId: String) -> String?,
    private val saveSession: (modeId: String, id: String) -> Unit,
) : Brain {
    private val json = Json { ignoreUnknownKeys = true }

    override fun send(turn: Turn, tools: ToolHost): Flow<BrainEvent> = flow {
        val l = link() ?: run { emit(BrainEvent.Error(tr(R.string.hermes_no_link), ErrorKind.NO_BRAIN)); return@flow }
        val user = turn.history.lastOrNull { it.role == "user" } ?: return@flow
        val body = buildJsonObject {
            put("model", desc.model)
            put("instructions", systemPrompt(turn))
            put("input", buildJsonArray { add(chatMsgJson(user)) })
            session(turn.mode.id)?.let { put("session_id", it) }
        }.toString()
        val runId = try {
            submit(l, turn.mode.id, body)
        } catch (e: Exception) { emit(error(l, e)); return@flow }
        emit(BrainEvent.Run(runId, -1))
        follow(l, turn.mode.id, HermesRun(runId, -1, System.currentTimeMillis()), this)
    }.flowOn(Dispatchers.IO)

    /** Дочитать ход, начатый раньше (приложение закрывали): события с места, где остановились; задачи на сервере уже нет — честная строка. */
    fun resume(modeId: String, run: HermesRun): Flow<BrainEvent> = flow {
        val l = link() ?: return@flow
        follow(l, modeId, run, this)
    }.flowOn(Dispatchers.IO)

    /** «Дописал вдогонку»: реплика в идущий ход. false — ход уже закончен, нужна новая задача. */
    suspend fun steer(runId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val l = link() ?: return@withContext false
        runCatching {
            val c = open(l, "/v1/runs/$runId/steer", "POST", 20_000)
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(buildJsonObject { put("input", text) }.toString().toByteArray()) }
            val ok = c.responseCode == 200; c.disconnect(); ok
        }.getOrDefault(false)
    }

    /** «Остановить» идущий ход (и перед новой задачей, если старая ещё шла). */
    suspend fun stop(runId: String) = withContext(Dispatchers.IO) {
        val l = link() ?: return@withContext
        runCatching { val c = open(l, "/v1/runs/$runId/stop", "POST", 10_000); c.doOutput = true; c.outputStream.use { it.write("{}".toByteArray()) }; c.responseCode; c.disconnect() }
    }

    /** Промпт режима — `instructions`: Hermes кладёт его временным слоем поверх своего, в историю не пишет. */
    private fun systemPrompt(turn: Turn): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
        val base = tr(R.string.hermes_system_1) + tr(R.string.brain_system_now, now)
        return when {
            turn.mode.prompt.isBlank() -> base
            turn.mode.id == Modes.MAIN -> tr(R.string.brain_system_rules, base, turn.mode.prompt)
            else -> tr(R.string.brain_system_mode, base, turn.mode.title, turn.mode.prompt)
        }
    }

    class HermesError(message: String, val kind: ErrorKind, val code: Int = 0) : Exception(message)

    private fun open(l: HermesLink, path: String, method: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(l.url.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer ${l.token}")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        c.setRequestProperty("User-Agent", "Vahter")
        return c
    }

    private fun httpError(l: HermesLink, code: Int, err: String): HermesError = when (code) {
        401, 403 -> HermesError(tr(R.string.hermes_err_auth), ErrorKind.AUTH, code)
        404 -> HermesError(tr(R.string.hermes_err_404, host(l.url)), ErrorKind.OTHER, code)
        429 -> HermesError(tr(R.string.hermes_err_busy), ErrorKind.RATE, code)
        502, 503, 504 -> HermesError(tr(R.string.hermes_err_down, code), ErrorKind.NETWORK, code)
        else -> HermesError(tr(R.string.hermes_err_http, code, err.take(200)), ErrorKind.OTHER, code)
    }

    private fun error(l: HermesLink, e: Exception): BrainEvent.Error = when (e) {
        is HermesError -> BrainEvent.Error(e.message ?: tr(R.string.brain_error_head), e.kind)
        is SSLException -> BrainEvent.Error(tr(R.string.hermes_err_tls), ErrorKind.NETWORK)
        is UnknownHostException -> BrainEvent.Error(tr(R.string.brain_up_no_net, host(l.url)), ErrorKind.NETWORK)
        is SocketTimeoutException -> BrainEvent.Error(tr(R.string.brain_timeout, desc.title), ErrorKind.NETWORK)
        is IOException -> BrainEvent.Error(tr(R.string.brain_stream_lost, e.message ?: tr(R.string.brain_net_error)), ErrorKind.NETWORK)
        else -> BrainEvent.Error(e.message ?: tr(R.string.brain_error_head), ErrorKind.OTHER)
    }

    /** `POST /v1/runs` → id задачи. Idempotency-Key: повтор после обрыва не заведёт вторую задачу. */
    private fun submit(l: HermesLink, modeId: String, body: String): String {
        val c = open(l, "/v1/runs", "POST", 30_000)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("X-Hermes-Session-Key", "vahter:$modeId")
        c.setRequestProperty("Idempotency-Key", UUID.randomUUID().toString())
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code != 200 && code != 202) throw httpError(l, code, c.errorStream?.bufferedReader()?.readText().orEmpty())
            val obj = json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
            return obj.str("run_id") ?: throw HermesError(tr(R.string.hermes_err_http, code, "no run_id"), ErrorKind.OTHER)
        } finally { c.disconnect() }
    }

    /** `GET /v1/runs/{id}` → статус и итог; null — задачи на сервере нет (перезапуск, срок хранения). */
    private fun status(l: HermesLink, runId: String): JsonObject? {
        val c = open(l, "/v1/runs/$runId", "GET", 15_000)
        try {
            val code = c.responseCode
            if (code == 404) return null
            if (code != 200) throw httpError(l, code, c.errorStream?.bufferedReader()?.readText().orEmpty())
            return json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
        } finally { c.disconnect() }
    }

    private class Follow(var seq: Int, val text: StringBuilder = StringBuilder(), val media: MutableList<String> = mutableListOf(), var done: Boolean = false)

    /**
     * Читать события задачи с [run].seq, переподключаясь после обрывов, пока ход не завершится. Текст через [MediaFilter]
     * (теги `MEDIA:` → файлы после текста). Конец: `run.completed` — History + MessageEnd, `run.failed` — Error, `run.cancelled` — Error.
     */
    private suspend fun follow(l: HermesLink, modeId: String, run: HermesRun, out: FlowCollector<BrainEvent>) {
        val f = Follow(run.seq)
        val filter = MediaFilter({ t -> f.text.append(t); out.emit(BrainEvent.Delta(t)) }, { f.media += it })
        var attempt = 0
        while (!f.done) {
            currentCoroutineContext().ensureActive()
            try {
                stream(l, modeId, run.id, f, filter, out)
                if (!f.done) {
                    // Поток закрылся без итога: задача могла кончиться, пока нас не было, — спросим статус.
                    val st = status(l, run.id)
                    if (st == null) { out.emit(BrainEvent.Error(tr(R.string.hermes_run_lost), ErrorKind.OTHER)); return }
                    val s = st.str("status")
                    if (s == "completed" || s == "failed" || s == "cancelled") { finish(l, modeId, st, f, filter, out); return }
                }
            } catch (e: HermesError) {
                if (e.code == 404) { out.emit(BrainEvent.Error(tr(R.string.hermes_run_lost), ErrorKind.OTHER)); return }
                out.emit(error(l, e)); return
            } catch (e: SSLException) { out.emit(error(l, e)); return
            } catch (e: IOException) {
                // Сеть моргнула — задача на сервере идёт дальше; подождём и продолжим с того же места.
                if (++attempt > 6) { out.emit(error(l, e)); return }
                delay(1000L shl (attempt - 1).coerceAtMost(4))
            }
        }
    }

    private suspend fun stream(l: HermesLink, modeId: String, runId: String, f: Follow, filter: MediaFilter, out: FlowCollector<BrainEvent>) {
        val c = open(l, "/v1/runs/$runId/events?last_seq=${f.seq}", "GET", 120_000)
        try {
            val code = c.responseCode
            if (code != 200) throw httpError(l, code, c.errorStream?.bufferedReader()?.readText().orEmpty())
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (!line.startsWith("data:")) continue
                    val obj = runCatching { json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull() ?: continue
                    obj["seq"]?.jsonPrimitive?.intOrNull?.let { f.seq = it }
                    when (obj.str("event")) {
                        "tool.started" -> {
                            val tool = obj.str("tool").orEmpty()
                            if (tool != "todo_list" && tool != "todo") out.emit(BrainEvent.Step(stepTitle(tool, obj.str("preview")), phone = false))
                        }
                        "message.delta" -> obj.str("delta")?.let { if (it.isNotEmpty()) filter.feed(it) }
                        "message.interim" -> if (obj["already_streamed"]?.jsonPrimitive?.contentOrNull != "true") obj.str("text")?.let { filter.feed(it.trimEnd() + "\n\n") }
                        "approval.request" -> out.emit(ask(l, obj))
                        "run.completed", "run.failed", "run.cancelled" -> { finish(l, modeId, obj, f, filter, out); return }
                        else -> Unit
                    }
                    out.emit(BrainEvent.Run(runId, f.seq))
                }
            }
        } finally { c.disconnect() }
    }

    /** Итог задачи: сессию запомнить, недопоказанный текст (если поток прервался) — дописать, файлы — скачать. */
    private suspend fun finish(l: HermesLink, modeId: String, st: JsonObject, f: Follow, filter: MediaFilter, out: FlowCollector<BrainEvent>) {
        f.done = true
        st.str("session_id")?.takeIf { it.isNotBlank() }?.let { saveSession(modeId, it) }
        val status = st.str("status") ?: st.str("event")?.removePrefix("run.")
        val output = st.str("output").orEmpty()
        if (f.text.isEmpty() && output.isNotBlank()) filter.feed(output)
        filter.finish()
        for (m in f.media) out.emit(fetch(l, m))
        // Ошибки провайдера Hermes присылает обычным ответом — узнаём по словарю и показываем красной плашкой (Продукт 5.16).
        val provider = providerError(f.text.toString())
        when {
            provider != null -> out.emit(provider)
            status == "failed" -> out.emit(BrainEvent.Error(tr(R.string.hermes_err_turn, st.str("error") ?: tr(R.string.brain_error_head)), ErrorKind.OTHER))
        }
        if (f.text.isNotBlank() && provider == null) out.emit(BrainEvent.History(ChatMsg("assistant", f.text.toString())))
        out.emit(BrainEvent.MessageEnd)
    }

    /**
     * Ответ, который на деле — ошибка сервиса модели (gateway/run.py: _PROVIDER_ERROR_REPLIES, _CONTEXT_OVERFLOW_REPLY и «сырые»
     * ошибки API). Известное — по-русски, незнакомое с признаками ошибки — исходным текстом, но красным. Обычный ответ — null.
     */
    private fun providerError(text: String): BrainEvent.Error? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 700) return null
        val low = t.lowercase()
        fun e(res: Int, kind: ErrorKind = ErrorKind.OTHER) = BrainEvent.Error(tr(res), kind, dropAnswer = true)
        return when {
            "grown too long for me to read" in low -> e(R.string.conv_too_long_p, ErrorKind.CONTEXT)
            "ai model service is rate-limiting" in low || "usage limit is reached" in low || "temporarily overloaded" in low ->
                e(R.string.hermes_prov_rate, ErrorKind.RATE)
            "sign-in to the ai model service failed" in low -> e(R.string.hermes_prov_auth)
            "ai model service rejected" in low -> e(R.string.hermes_prov_rejected)
            "connection to the ai model service was interrupted" in low || "isn't reachable right now" in low ||
                "could not reach the ai model service" in low || "ai model service kept failing" in low -> e(R.string.hermes_prov_down, ErrorKind.NETWORK)
            PROVIDER_RAW.containsMatchIn(t) -> BrainEvent.Error(t, ErrorKind.OTHER, dropAnswer = true)
            else -> null
        }
    }

    /** `approval.request`: команда и причина — как прислал сервер (Продукт 5: пересказ не выдумываем), варианты — его список. */
    private fun ask(l: HermesLink, obj: JsonObject): BrainEvent.Ask {
        val runId = obj.str("run_id").orEmpty()
        val requestId = obj.str("request_id")
        val command = obj.str("command").orEmpty()
        val why = obj.str("description").orEmpty()
        val choices = (obj["choices"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().ifEmpty { listOf("once", "deny") }
        val labels = choices.map { it to when (it) {
            "once" -> tr(R.string.hermes_ask_once); "session" -> tr(R.string.hermes_ask_session); "always" -> tr(R.string.hermes_ask_always); else -> tr(R.string.hermes_ask_deny)
        } }
        return BrainEvent.Ask(title = command.ifBlank { tr(R.string.hermes_asks) }, text = why, choices = labels.map { (k, v) -> v to k }) { choice ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val c = open(l, "/v1/runs/$runId/approval", "POST", 30_000)
                    c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(buildJsonObject { put("choice", choice); if (requestId != null) put("request_id", requestId) }.toString().toByteArray()) }
                    c.responseCode; c.disconnect()
                }
            }
        }
    }

    /**
     * Файл от мозга: доставляется только из папки `vahter-out` дома Hermes — её отдаёт Caddy по `/vahter/files/<имя>` тем же ключом
     * телефона (server/Caddyfile). Остальные пути остаются на сервере, честно говорим об этом.
     */
    private fun fetch(l: HermesLink, serverPath: String): BrainEvent.Attachment {
        val path = serverPath.trim().trim('"', '\'', '`', '<', '>').trimEnd('.', ',', ')')
        val name = path.substringAfterLast('/')
        val image = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp")
        if (!path.contains("/vahter-out/")) return BrainEvent.Attachment(name, null, image, tr(R.string.hermes_file_server_only, path))
        val dir = java.io.File(ru.poneslas.vahter.App.instance.cacheDir, "hermes").apply { mkdirs() }
        val out = java.io.File(dir, "${System.currentTimeMillis()}_$name")
        return runCatching {
            val c = open(l, "/vahter/files/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"), "GET", 120_000)
            try {
                if (c.responseCode != 200) throw HermesError("${c.responseCode}", ErrorKind.OTHER)
                c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            } finally { c.disconnect() }
            BrainEvent.Attachment(name, out.absolutePath, image)
        }.getOrElse { e -> out.delete(); BrainEvent.Attachment(name, null, image, tr(R.string.hermes_file_failed) + " (" + (e.message ?: "") + ")") }
    }

    /** Подписи шагов по-русски для частых инструментов Hermes; остальное — как прислал сервер (английская строка-превью). */
    private fun stepTitle(tool: String, label: String?): String = when {
        tool.startsWith("web_search") || tool == "x_search" -> tr(R.string.hermes_step_search)
        tool.startsWith("web_extract") || tool == "web_fetch" || tool.startsWith("browser") -> tr(R.string.hermes_step_read_page)
        tool == "terminal" || tool == "process" -> tr(R.string.hermes_step_terminal)
        tool == "execute_code" -> tr(R.string.hermes_step_code)
        tool == "read_file" || tool == "search_files" || tool == "list_dir" -> tr(R.string.hermes_step_read_file)
        tool == "write_file" || tool == "patch" || tool == "edit_file" -> tr(R.string.hermes_step_write_file)
        tool == "memory" || tool == "save_memory" || tool == "session_search" -> tr(R.string.hermes_step_memory)
        tool == "vision_analyze" -> tr(R.string.hermes_step_vision)
        tool == "send_message" -> tr(R.string.hermes_step_message)
        tool == "delegate_task" -> tr(R.string.hermes_step_delegate)
        tool.startsWith("cron") || tool == "schedule" -> tr(R.string.hermes_step_cron)
        tool.startsWith("skill") -> tr(R.string.hermes_step_skill)
        else -> label?.takeIf { it.isNotBlank() } ?: tr(R.string.hermes_step_generic, tool)
    }

    /** Проверка подключения: `GET /v1/models` ключом телефона — отвечает Hermes и за сколько (страница «Hermes», ссылка-приглашение). */
    fun check(l: HermesLink): ApiBrain.Result2 {
        if (!l.url.startsWith("https://") && !(debuggable && l.url.startsWith("http://"))) return ApiBrain.Result2(false, tr(R.string.hermes_check_https))
        return runCatching {
            val c = open(l, "/v1/models", "GET", 15_000)
            val t0 = System.currentTimeMillis()
            val code = c.responseCode
            val ms = System.currentTimeMillis() - t0
            val ok = code == 200 && c.inputStream.bufferedReader().readText().contains("hermes-agent")
            c.disconnect()
            when {
                ok -> ApiBrain.Result2(true, tr(R.string.brain_check_ok, "%.1f".format(ms / 1000.0).replace('.', ',')))
                code == 401 || code == 403 -> ApiBrain.Result2(false, tr(R.string.hermes_check_auth))
                code == 404 || code == 200 -> ApiBrain.Result2(false, tr(R.string.hermes_check_404))
                else -> ApiBrain.Result2(false, tr(R.string.hermes_check_http, code))
            }
        }.getOrElse { e ->
            ApiBrain.Result2(false, if (e is SSLException) tr(R.string.hermes_check_tls) else tr(R.string.hermes_check_net))
        }
    }

    companion object {
        /** Отладочная сборка может ходить по http на эмуляторный хост (10.0.2.2 → SSH-туннель на Mac); выпускная — только https. */
        var debuggable = false

        fun host(url: String) = url.removePrefix("https://").removePrefix("http://").trimEnd('/')

        /**
         * Ссылка-приглашение: `vahter://connect?url=https://…&token=…` (страница `/vahter/connect` на сервере открывает её)
         * или та же страница `https://…/vahter/connect#<token>`. Возвращает адрес и ключ или null.
         */
        fun parseInvite(text: String): HermesLink? {
            val t = text.trim()
            runCatching {
                val u = android.net.Uri.parse(t)
                if (u.scheme == "vahter" && u.host == "connect") {
                    val url = u.getQueryParameter("url")?.trim()?.trimEnd('/'); val tok = u.getQueryParameter("token")?.trim()
                    if (!url.isNullOrBlank() && !tok.isNullOrBlank()) return HermesLink(url, tok)
                }
                if ((u.scheme == "https" || u.scheme == "http") && u.path?.endsWith("/vahter/connect") == true && !u.fragment.isNullOrBlank())
                    return HermesLink("${u.scheme}://${u.authority}", u.fragment!!.trim())
            }
            return null
        }
    }
}

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

/** Начало «сырой» ошибки API, как её узнаёт сам Hermes (gateway/run.py, _PROVIDER_ERROR_MARKERS) — с начала строки. */
private val PROVIDER_RAW = Regex("""(?im)^\W*(api\s+(call\s+)?failed|provider\s+authentication\s+failed|non-retryable\s+error|rate\s+limited\s+after\s+\d+\s+retries|error\s+code\s*:|incorrect\s+api\s+key|invalid\s+api\s+key)""")

/**
 * Построчный фильтр потока: строки `MEDIA:/путь` уходят в [onMedia], остальное — в [onText] как есть. Хвост без перевода
 * строки отдаётся сразу, если уже не может оказаться началом тега.
 */
internal class MediaFilter(private val onText: suspend (String) -> Unit, private val onMedia: (String) -> Unit) {
    private val buf = StringBuilder()

    suspend fun feed(s: String) { buf.append(s); drain(end = false) }
    suspend fun finish() = drain(end = true)

    private suspend fun drain(end: Boolean) {
        while (true) {
            val nl = buf.indexOf("\n")
            if (nl < 0) break
            val line = buf.substring(0, nl + 1); buf.delete(0, nl + 1)
            line(line)
        }
        if (end) { if (buf.isNotEmpty()) line(buf.toString()); buf.clear(); return }
        val tail = buf.toString(); val t = tail.trimStart()
        if (t.isEmpty() || "MEDIA:".startsWith(t) || t.startsWith("MEDIA:")) return
        buf.clear(); onText(tail)
    }

    private suspend fun line(line: String) {
        val t = line.trim()
        if (t.startsWith("MEDIA:")) onMedia(t.removePrefix("MEDIA:")) else onText(line)
    }
}
