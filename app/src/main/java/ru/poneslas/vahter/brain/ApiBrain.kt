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
import kotlinx.serialization.json.jsonArray
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
            emit(BrainEvent.Error(tr(R.string.brain_mozg_ne_podklyuchen_net_klyucha), ErrorKind.NO_BRAIN)); return@flow
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
                emit(BrainEvent.Error(e.message ?: tr(R.string.brain_oshibka_mozga), e.kind)); return@flow
            } catch (e: UnknownHostException) {
                emit(BrainEvent.Error(tr(R.string.brain_up_no_net, desc.title), ErrorKind.NETWORK)); return@flow
            } catch (e: SocketTimeoutException) {
                emit(BrainEvent.Error(tr(R.string.brain_1_s_dolgo_ne_otvechaet, desc.title), ErrorKind.NETWORK)); return@flow
            } catch (e: IOException) {
                emit(BrainEvent.Error(tr(R.string.brain_svyaz_oborvalas_1_s, e.message ?: tr(R.string.brain_oshibka_seti)), ErrorKind.NETWORK)); return@flow
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
        "alarm_set" -> tr(R.string.brain_stavlyu_budilnik); "alarm_dismiss" -> tr(R.string.brain_vyklyuchayu_budilnik); "alarm_next" -> tr(R.string.brain_smotryu_budilniki)
        "timer_set" -> tr(R.string.brain_zapuskayu_taymer); "timer_stop" -> tr(R.string.brain_ostanavlivayu_taymer); "calendar_delete" -> tr(R.string.brain_udalyayu_sobytie); "note_delete", "place_delete" -> tr(R.string.brain_zabyvayu); "calendar_query" -> tr(R.string.brain_smotryu_kalendar); "calendar_add" -> tr(R.string.brain_sozdayu_sobytie)
        "torch_set" -> tr(R.string.reg_fonarik); "volume_set" -> tr(R.string.reg_gromkost); "dnd_set" -> tr(R.string.ph_ne_bespokoit); "media_control" -> tr(R.string.brain_muzyka)
        "location_get" -> tr(R.string.brain_uznayu_mestopolozhenie); "place_save" -> tr(R.string.brain_zapominayu_mesto); "place_list" -> tr(R.string.brain_smotryu_sokhranennye_mesta)
        "note_add" -> tr(R.string.brain_zapominayu); "note_find" -> tr(R.string.brain_ishchu_v_zametkakh); "app_open" -> tr(R.string.brain_otkryvayu_prilozhenie); "settings_open" -> tr(R.string.brain_otkryvayu_nastroyki)
        "call_dial" -> tr(R.string.brain_nabirayu_nomer); "message_draft" -> tr(R.string.brain_gotovlyu_soobshchenie)
        else -> tr(R.string.brain_deystvie_na_telefone)
    }

    private fun systemPrompt(turn: Turn): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
        val base = tr(R.string.brain_ty_golosovoy_assistent_na_telefone) +
            tr(R.string.brain_rech_polzovatelya_raspoznana_avtomatiche) +
            tr(R.string.brain_esli_dlya_prosby_est_funktsiya) +
            tr(R.string.brain_u_tebya_net_dostupa_k) +
            tr(R.string.brain_i_predlozhi_podklyuchit_lichnogo_agenta) +
            tr(R.string.brain_seychas_1_s, now)
        return if (turn.mode.prompt.isBlank()) base
        else if (turn.mode.id == ru.poneslas.vahter.core.Modes.MAIN) tr(R.string.brain_svoi_pravila, base, turn.mode.prompt)
        else tr(R.string.brain_1_s_rezhim_2_s, base, turn.mode.title, turn.mode.prompt)
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
                    val url = if (img.startsWith("data:")) img else ru.poneslas.vahter.media.Attachments.dataUrl(java.io.File(img))
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
                    code == 401 -> ApiError(tr(R.string.brain_klyuch_ne_prinyat_proverte_ego), ErrorKind.AUTH)
                    code == 402 -> ApiError(tr(R.string.brain_na_schete_1_s_zakonchilis, desc.title), ErrorKind.BALANCE)
                    code == 429 -> ApiError(tr(R.string.brain_1_s_ogranichivaet_zaprosy_poprobuyte, desc.title), ErrorKind.RATE)
                    code == 400 && err.contains("context", true) -> ApiError(tr(R.string.brain_razgovor_slishkom_dlinnyy_dlya_modeli), ErrorKind.CONTEXT)
                    code >= 500 -> ApiError(tr(R.string.brain_u_1_s_sboy_2, desc.title, code), ErrorKind.OTHER)
                    else -> ApiError(tr(R.string.brain_oshibka_1_s_2_s, desc.title, code, err.take(200)), ErrorKind.OTHER)
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
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", tr(R.string.brain_otvet_odnoy_bukvoy_da)) }) })
            }.toString().toByteArray())
        }
        val code = c.responseCode
        val ms = System.currentTimeMillis() - t0
        c.disconnect()
        when (code) {
            200 -> Result2(true, tr(R.string.brain_otvechaet_1_s_s, "%.1f".format(ms / 1000.0).replace('.', ',')))
            401 -> Result2(false, tr(R.string.brain_klyuch_ne_prinyat_proverte_chto))
            402 -> Result2(false, tr(R.string.brain_klyuch_vernyy_no_na_schete))
            else -> Result2(false, tr(R.string.brain_otvet_1_s_poprobuyte_pozzhe, code))
        }
    }.getOrElse { Result2(false, tr(R.string.brain_net_svyazi_s_1_s, desc.url.removePrefix("https://"))) }

    data class Result2(val ok: Boolean, val text: String)
}

@Suppress("unused")
private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
@Suppress("unused")
private fun JsonObject.arr(k: String) = this[k]?.jsonArray
