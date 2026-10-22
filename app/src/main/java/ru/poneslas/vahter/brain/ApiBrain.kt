package ru.poneslas.vahter.brain

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.poneslas.vahter.core.BrainDesc
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.poneslas.vahter.core.Modes
import ru.poneslas.vahter.media.Attachments

/**
 * Прямой OpenAI-совместимый API (DeepSeek). Цикл хода — в приложении (Архитектура 2.2):
 * system + вся история режима → поток (SSE) → вызов функции → результат в историю → следующий запрос.
 * Не больше [maxToolRounds] вызовов за реплику. reasoning_content не храним и не отправляем.
 */
class ApiBrain(
    private val desc: BrainDesc,
    private val apiKey: () -> String?,
    private val maxToolRounds: Int = 3,
) : Brain {
    private val json = Json { ignoreUnknownKeys = true }
    /** Модель сейчас: основная или запасная, если основную сервер не узнал (сохраняется до перезапуска). */
    @Volatile private var model = desc.model
    private class ModelGone : Exception()
    private fun modelGone(code: Int, err: String) = (code == 400 || code == 404) && err.contains("model", true) && desc.fallbackModel.isNotEmpty() && model != desc.fallbackModel

    override fun send(turn: Turn, tools: ToolHost): Flow<BrainEvent> = flow {
        val key = apiKey() ?: run {
            emit(BrainEvent.Error(tr(R.string.brain_no_key), ErrorKind.NO_BRAIN)); return@flow
        }
        val history = turn.history.toMutableList()
        // Поиск в интернете — функция самого мозга (не телефона): выполняет приложение через API провайдера.
        val specs = tools.specs(turn.mode) + if (desc.search) listOf(SEARCH_SPEC) else emptyList()
        val question = turn.history.lastOrNull { it.role == "user" }?.content.orEmpty()
        var round = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val body = requestBody(turn, history, specs)
            val result = try {
                stream(key, body) { delta -> emit(BrainEvent.Delta(delta)) }
            } catch (e: ModelGone) {
                model = desc.fallbackModel; continue
            } catch (e: ApiError) {
                emit(BrainEvent.Error(e.message ?: tr(R.string.brain_error_head), e.kind)); return@flow
            } catch (e: UnknownHostException) {
                emit(BrainEvent.Error(tr(R.string.brain_up_no_net, desc.title), ErrorKind.NETWORK)); return@flow
            } catch (e: SocketTimeoutException) {
                emit(BrainEvent.Error(tr(R.string.brain_timeout, desc.title), ErrorKind.NETWORK)); return@flow
            } catch (e: IOException) {
                emit(BrainEvent.Error(tr(R.string.brain_stream_lost, e.message ?: tr(R.string.brain_net_error)), ErrorKind.NETWORK)); return@flow
            }
            val assistant = ChatMsg("assistant", result.text.ifEmpty { null }, toolCalls = result.toolCalls)
            history += assistant
            emit(BrainEvent.History(assistant))
            if (result.toolCalls.isEmpty() || round >= maxToolRounds) break
            round++
            for (call in result.toolCalls) {
                val web = call.name == SEARCH_FN
                val query = if (web) runCatching { json.parseToJsonElement(call.arguments).jsonObject["query"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty() else ""
                emit(BrainEvent.Step(if (web) tr(R.string.brain_step_search, query) else stepTitle(call.name), phone = !web))
                val out = if (web) search(key, query, question) else tools.call(call.name, call.arguments)
                val toolMsg = ChatMsg("tool", out, toolCallId = call.id)
                history += toolMsg
                emit(BrainEvent.History(toolMsg))
            }
        }
        emit(BrainEvent.MessageEnd)
    }.flowOn(Dispatchers.IO)

    private fun stepTitle(name: String) = when (name) {
        "alarm_set" -> tr(R.string.brain_step_alarm_set); "alarm_dismiss" -> tr(R.string.brain_step_alarm_off); "alarm_next" -> tr(R.string.brain_step_alarm_next)
        "timer_set" -> tr(R.string.brain_step_timer_set); "timer_stop" -> tr(R.string.brain_step_timer_off); "calendar_delete" -> tr(R.string.brain_step_cal_del); "note_delete", "place_delete" -> tr(R.string.brain_step_forget); "calendar_query" -> tr(R.string.brain_step_cal_query); "calendar_add" -> tr(R.string.brain_step_cal_add)
        "torch_set" -> tr(R.string.reg_torch); "volume_set" -> tr(R.string.reg_volume); "dnd_set" -> tr(R.string.phone_screen_dnd); "media_control" -> tr(R.string.brain_step_media)
        "location_get" -> tr(R.string.brain_step_location); "place_save" -> tr(R.string.brain_step_place_save); "place_list" -> tr(R.string.brain_step_places)
        "note_add" -> tr(R.string.brain_step_note_save); "note_find" -> tr(R.string.brain_step_note_find); "app_open" -> tr(R.string.brain_step_app_open); "settings_open" -> tr(R.string.brain_step_settings_open)
        "call_dial" -> tr(R.string.brain_step_call); "message_draft" -> tr(R.string.brain_step_message)
        else -> tr(R.string.brain_step_generic)
    }

    private fun systemPrompt(turn: Turn): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
        val base = tr(R.string.brain_system_1) +
            tr(R.string.brain_system_2) +
            tr(R.string.brain_system_3) +
            tr(R.string.brain_system_4) +
            tr(R.string.brain_system_5) +
            tr(R.string.brain_system_now, now)
        return if (turn.mode.prompt.isBlank()) base
        else if (turn.mode.id == Modes.MAIN) tr(R.string.brain_system_rules, base, turn.mode.prompt)
        else tr(R.string.brain_system_mode, base, turn.mode.title, turn.mode.prompt)
    }

    private fun requestBody(turn: Turn, history: List<ChatMsg>, specs: List<ToolSpec>): String {
        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", systemPrompt(turn)) })
            for (m in history) add(chatMsgJson(m))
        }
        return buildJsonObject {
            put("model", model)
            put("stream", true)
            put("messages", messages)
            if (specs.isNotEmpty()) put("tools", buildJsonArray {
                for (s in specs) add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", s.name)
                        put("description", s.description)
                        put("parameters", json.parseToJsonElement(s.parametersJson))
                    })
                })
            })
        }.toString()
    }

    private class Result(val text: String, val toolCalls: List<ToolCallMsg>)
    class ApiError(message: String, val kind: ErrorKind) : Exception(message)

    private suspend fun stream(key: String, body: String, onDelta: suspend (String) -> Unit): Result {
        val c = URL(desc.url.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 90_000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "text/event-stream")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code != 200) {
                val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                if (modelGone(code, err)) throw ModelGone()
                throw when {
                    code == 401 -> ApiError(tr(R.string.brain_key_rejected), ErrorKind.AUTH)
                    code == 402 -> ApiError(tr(R.string.brain_no_balance, desc.title), ErrorKind.BALANCE)
                    code == 429 -> ApiError(tr(R.string.brain_rate_limited, desc.title), ErrorKind.RATE)
                    code == 400 && err.contains("context", true) -> ApiError(tr(R.string.brain_context_full), ErrorKind.CONTEXT)
                    code >= 500 -> ApiError(tr(R.string.brain_server_error, desc.title, code), ErrorKind.OTHER)
                    else -> ApiError(tr(R.string.brain_http_error, desc.title, code, err.take(200)), ErrorKind.OTHER)
                }
            }
            val text = StringBuilder()
            val calls = sortedMapOf<Int, Triple<String, String, StringBuilder>>()
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: continue
                    val delta = choice["delta"]?.jsonObject ?: continue
                    delta["content"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) { text.append(it); onDelta(it) } }
                    (delta["tool_calls"] as? JsonArray)?.forEach { tc ->
                        val o = tc.jsonObject
                        val idx = o["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val f = o["function"]?.jsonObject
                        val cur = calls.getOrPut(idx) { Triple("", "", StringBuilder()) }
                        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: cur.first
                        val name = f?.get("name")?.jsonPrimitive?.contentOrNull ?: cur.second
                        f?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { cur.third.append(it) }
                        calls[idx] = Triple(id, name, cur.third)
                    }
                }
            }
            return Result(text.toString(), calls.values.map { ToolCallMsg(it.first, it.second, it.third.toString().ifBlank { "{}" }) })
        } finally {
            c.disconnect()
        }
    }

    /**
     * Поиск в интернете (DeepSeek Search API, /v1/tools/search): модели отдаём заголовок, ссылку и выжимку «для моделей» (summary),
     * не больше 5 результатов. Не вышло — честная ошибка, модель ответит без поиска.
     */
    private fun search(key: String, query: String, question: String): String = runCatching {
        val c = URL(desc.url.trimEnd('/') + "/v1/tools/search").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 30_000; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(buildJsonObject { put("query", query.take(128)); put("count", 5); if (question.isNotBlank()) put("ori_question", question.take(128)) }.toString().toByteArray()) }
            if (c.responseCode != 200) return@runCatching """{"ok":false,"message":"search HTTP ${c.responseCode}"}"""
            val res = json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject["results"] as? JsonArray ?: JsonArray(emptyList())
            buildJsonObject {
                put("ok", true)
                put("results", buildJsonArray {
                    for (r in res.take(5)) { val o = r.jsonObject; add(buildJsonObject {
                        put("title", o["title"]?.jsonPrimitive?.contentOrNull.orEmpty()); put("url", o["url"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("summary", (o["summary"] ?: o["snippet"])?.jsonPrimitive?.contentOrNull.orEmpty().take(900))
                    }) }
                })
            }.toString()
        } finally { c.disconnect() }
    }.getOrElse { """{"ok":false,"message":"search failed: ${it.message?.replace("\"", "'")}"}""" }

    /** Проверка ключа пробным запросом (первый запуск, шаг «Мозг»). Основную модель не узнали — пробуем запасную. */
    fun check(key: String): Result2 = check(key, model).let { r -> if (r.second && desc.fallbackModel.isNotEmpty() && model != desc.fallbackModel) { model = desc.fallbackModel; check(key, model).first } else r.first }

    private fun check(key: String, m: String): Pair<Result2, Boolean> = runCatching {
        val c = URL(desc.url.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 30_000; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("Content-Type", "application/json")
        val t0 = System.currentTimeMillis()
        c.outputStream.use {
            it.write(buildJsonObject {
                put("model", m); put("max_tokens", 1)
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", tr(R.string.brain_check_prompt)) }) })
            }.toString().toByteArray())
        }
        val code = c.responseCode
        val ms = System.currentTimeMillis() - t0
        val err = if (code != 200) c.errorStream?.bufferedReader()?.readText().orEmpty() else ""
        c.disconnect()
        when (code) {
            200 -> Result2(true, tr(R.string.brain_check_ok, "%.1f".format(ms / 1000.0).replace('.', ','))) to false
            401 -> Result2(false, tr(R.string.brain_check_key_rejected)) to false
            402 -> Result2(false, tr(R.string.brain_check_no_balance)) to false
            else -> Result2(false, tr(R.string.brain_check_http_error, code)) to modelGone(code, err)
        }
    }.getOrElse { Result2(false, tr(R.string.brain_no_net, desc.url.removePrefix("https://"))) to false }

    data class Result2(val ok: Boolean, val text: String)
}

/** Сообщение истории в формате OpenAI chat: картинки — `image_url` с data-URL (общее для [ApiBrain] и [HermesBrain]). */
internal fun chatMsgJson(m: ChatMsg): JsonObject = buildJsonObject {
    put("role", m.role)
    if (m.role == "user" && m.images.isNotEmpty()) {
        put("content", buildJsonArray {
            if (!m.content.isNullOrBlank()) add(buildJsonObject { put("type", "text"); put("text", m.content) })
            for (img in m.images) add(buildJsonObject {
                val url = if (img.startsWith("data:")) img else Attachments.dataUrl(java.io.File(img))
                put("type", "image_url"); put("image_url", buildJsonObject { put("url", url) })
            })
        })
    } else if (m.content != null) put("content", m.content) else put("content", "")
    if (m.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
        for (c in m.toolCalls) add(buildJsonObject {
            put("id", c.id); put("type", "function")
            put("function", buildJsonObject { put("name", c.name); put("arguments", c.arguments) })
        })
    })
    if (m.toolCallId != null) put("tool_call_id", m.toolCallId)
}

private const val SEARCH_FN = "web_search"
/** Поиск в интернете — для свежих фактов (новости, цены, расписания, погода); ответ — со ссылками на источники. */
private val SEARCH_SPEC = ToolSpec(SEARCH_FN,
    "Search the web for fresh facts (news, prices, schedules, weather, anything after your training). Returns titles, urls and summaries. Cite sources as Markdown links.",
    """{"type":"object","properties":{"query":{"type":"string","description":"Search query, up to 128 chars; operators site:, after:, lang: allowed"}},"required":["query"]}""")
