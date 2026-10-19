package ru.poneslas.tasya.brain

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.poneslas.tasya.core.BrainDesc
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    override fun send(turn: Turn, tools: ToolHost): Flow<BrainEvent> = flow {
        val key = apiKey() ?: run {
            emit(BrainEvent.Error("Мозг не подключён — нет ключа DeepSeek.", ErrorKind.AUTH)); return@flow
        }
        val history = turn.history.toMutableList()
        val specs = tools.specs(turn.mode)
        var round = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val body = requestBody(turn, history, specs)
            val result = try {
                stream(key, body) { delta -> emit(BrainEvent.Delta(delta)) }
            } catch (e: ApiError) {
                emit(BrainEvent.Error(e.message ?: "Ошибка мозга", e.kind)); return@flow
            } catch (e: UnknownHostException) {
                emit(BrainEvent.Error("Нет связи с ${desc.title}.", ErrorKind.NETWORK)); return@flow
            } catch (e: SocketTimeoutException) {
                emit(BrainEvent.Error("${desc.title} долго не отвечает.", ErrorKind.NETWORK)); return@flow
            } catch (e: IOException) {
                emit(BrainEvent.Error("Связь оборвалась: ${e.message ?: "ошибка сети"}.", ErrorKind.NETWORK)); return@flow
            }
            val assistant = ChatMsg("assistant", result.text.ifEmpty { null }, toolCalls = result.toolCalls)
            history += assistant
            emit(BrainEvent.History(assistant))
            if (result.toolCalls.isEmpty() || round >= maxToolRounds) break
            round++
            for (call in result.toolCalls) {
                emit(BrainEvent.Step(stepTitle(call.name), phone = true))
                val out = tools.call(call.name, call.arguments)
                val toolMsg = ChatMsg("tool", out, toolCallId = call.id)
                history += toolMsg
                emit(BrainEvent.History(toolMsg))
            }
        }
        emit(BrainEvent.MessageEnd)
    }.flowOn(Dispatchers.IO)

    private fun stepTitle(name: String) = when (name) {
        "alarm_set" -> "Ставлю будильник"
        "timer_set" -> "Ставлю таймер"
        else -> "Действие на телефоне"
    }

    private fun systemPrompt(turn: Turn): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
        val base = "Ты — голосовой ассистент на телефоне Android. Отвечай по-русски, коротко и по делу; " +
            "речь пользователя распознана автоматически и может содержать ошибки — понимай смысл. " +
            "Если для просьбы есть функция телефона — вызывай её, а не объясняй, как сделать вручную. " +
            "Сейчас: $now."
        return if (turn.mode.prompt.isBlank()) base else "$base\n\nРежим «${turn.mode.title}»: ${turn.mode.prompt}"
    }

    private fun requestBody(turn: Turn, history: List<ChatMsg>, specs: List<ToolSpec>): String {
        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", systemPrompt(turn)) })
            for (m in history) add(toJson(m))
        }
        return buildJsonObject {
            put("model", desc.model)
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

    private fun toJson(m: ChatMsg): JsonObject = buildJsonObject {
        put("role", m.role)
        if (m.role == "user" && m.images.isNotEmpty()) {
            put("content", buildJsonArray {
                if (!m.content.isNullOrBlank()) add(buildJsonObject { put("type", "text"); put("text", m.content) })
                for (img in m.images) add(buildJsonObject {
                    put("type", "image_url"); put("image_url", buildJsonObject { put("url", img) })
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
                throw when {
                    code == 401 -> ApiError("Ключ не принят — проверьте его в настройках мозга.", ErrorKind.AUTH)
                    code == 402 -> ApiError("На счёте ${desc.title} закончились деньги.", ErrorKind.BALANCE)
                    code == 429 -> ApiError("${desc.title} ограничивает запросы. Попробуйте через минуту.", ErrorKind.RATE)
                    code == 400 && err.contains("context", true) -> ApiError("Разговор слишком длинный для модели.", ErrorKind.CONTEXT)
                    code >= 500 -> ApiError("У ${desc.title} сбой ($code). Попробуйте позже.", ErrorKind.OTHER)
                    else -> ApiError("Ошибка ${desc.title} ($code): ${err.take(200)}", ErrorKind.OTHER)
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

    /** Проверка ключа пробным запросом (первый запуск, шаг «Мозг»). */
    fun check(key: String): Result2 = runCatching {
        val c = URL(desc.url.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 30_000; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("Content-Type", "application/json")
        val t0 = System.currentTimeMillis()
        c.outputStream.use {
            it.write(buildJsonObject {
                put("model", desc.model); put("max_tokens", 1)
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "Ответь одной буквой: да") }) })
            }.toString().toByteArray())
        }
        val code = c.responseCode
        val ms = System.currentTimeMillis() - t0
        c.disconnect()
        when (code) {
            200 -> Result2(true, "отвечает · ${"%.1f".format(ms / 1000.0)} с")
            401 -> Result2(false, "Ключ не принят. Проверьте, что он скопирован целиком и не отозван.")
            402 -> Result2(false, "Ключ верный, но на счёте нет денег.")
            else -> Result2(false, "Ответ $code — попробуйте позже.")
        }
    }.getOrElse { Result2(false, "Нет связи с ${desc.url.removePrefix("https://")}.") }

    data class Result2(val ok: Boolean, val text: String)
}

@Suppress("unused")
private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
@Suppress("unused")
private fun JsonObject.arr(k: String) = this[k]?.jsonArray
