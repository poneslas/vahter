package ru.poneslas.tasya.probe.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.Registry
import ru.poneslas.tasya.probe.TAG
import java.io.File

enum class Status(val label: String, val color: Long) {
    NOT_RUN("не запускали", 0xFF9E9E9E),
    RUNNING("идёт", 0xFF2196F3),
    PASS("прошло", 0xFF4CAF50),
    FAIL("не прошло", 0xFFF44336),
    PARTIAL("частично", 0xFFFF9800),
    NOT_IMPL("не сделано", 0xFF607D8B),
}

data class Event(val t: Long, val tag: String, val text: String)
data class Err(val type: String, val msg: String, val stack: String)

data class ProbeResult(
    val id: String,
    val status: Status = Status.NOT_RUN,
    val summary: String = "",
    val metrics: Map<String, List<Double>> = emptyMap(),
    val events: List<Event> = emptyList(),
    val errors: List<Err> = emptyList(),
    val note: String = "",
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
)

/** Результаты всех проверок; после каждого изменения переписываются report.json и report.txt. */
object Results {
    private val _all = MutableStateFlow<Map<String, ProbeResult>>(emptyMap())
    val all: StateFlow<Map<String, ProbeResult>> = _all
    private var saveJob: Job? = null
    private lateinit var dir: File

    fun get(id: String) = _all.value[id] ?: ProbeResult(id)

    fun update(id: String, f: (ProbeResult) -> ProbeResult) {
        _all.update { it + (id to f(it[id] ?: ProbeResult(id))) }
        scheduleSave()
    }

    fun clear() {
        _all.value = emptyMap()
        scheduleSave()
    }

    fun load(ctx: Context) {
        dir = ctx.filesDir
        val f = File(dir, "report.json")
        if (!f.exists()) return
        runCatching {
            val probes = JSONObject(f.readText()).getJSONArray("probes")
            val map = mutableMapOf<String, ProbeResult>()
            for (i in 0 until probes.length()) {
                val r = fromJson(probes.getJSONObject(i))
                // Прогон, оборванный смертью процесса, не должен висеть «идёт».
                map[r.id] = if (r.status == Status.RUNNING) r.copy(status = Status.PARTIAL, summary = "оборвано: процесс перезапущен. " + r.summary) else r
            }
            _all.value = map
        }.onFailure { Log.w(TAG, "report.json не прочитан", it) }
    }

    private fun scheduleSave() {
        if (!::dir.isInitialized) return
        saveJob?.cancel()
        saveJob = App.scope.launch(Dispatchers.IO) {
            delay(300)
            saveNow()
        }
    }

    fun saveNow() {
        val json = toJson().toString(1)
        File(dir, "report.json").writeText(json)
        File(dir, "report.txt").writeText(toText())
    }

    fun reportFile(name: String) = File(dir, name)

    fun toJson(): JSONObject = JSONObject().apply {
        put("header", JSONObject(Header.collect(App.instance) as Map<*, *>))
        put("probes", JSONArray().apply {
            Registry.probes.forEach { p -> _all.value[p.id]?.let { put(toJson(it)) } }
        })
    }

    private fun toJson(r: ProbeResult) = JSONObject().apply {
        put("probe", r.id)
        put("status", r.status.name.lowercase())
        put("summary", r.summary)
        put("metrics", JSONObject().apply {
            r.metrics.forEach { (k, v) ->
                put(k, JSONArray(v))
                if (v.size > 1) put("${k}_median", median(v))
            }
        })
        put("events", JSONArray().apply {
            r.events.forEach { put(JSONObject().put("t", it.t).put("tag", it.tag).put("text", it.text)) }
        })
        put("errors", JSONArray().apply {
            r.errors.forEach { put(JSONObject().put("type", it.type).put("msg", it.msg).put("stack", it.stack)) }
        })
        put("note", r.note)
        put("startedAt", r.startedAt)
        put("finishedAt", r.finishedAt)
    }

    private fun fromJson(o: JSONObject): ProbeResult {
        val m = o.optJSONObject("metrics") ?: JSONObject()
        val metrics = mutableMapOf<String, List<Double>>()
        m.keys().forEach { k ->
            val a = m.optJSONArray(k) ?: return@forEach
            metrics[k] = (0 until a.length()).map { a.getDouble(it) }
        }
        val ev = o.optJSONArray("events") ?: JSONArray()
        val er = o.optJSONArray("errors") ?: JSONArray()
        return ProbeResult(
            id = o.getString("probe"),
            status = runCatching { Status.valueOf(o.getString("status").uppercase()) }.getOrDefault(Status.NOT_RUN),
            summary = o.optString("summary"),
            metrics = metrics,
            events = (0 until ev.length()).map { ev.getJSONObject(it).let { e -> Event(e.getLong("t"), e.getString("tag"), e.getString("text")) } },
            errors = (0 until er.length()).map { er.getJSONObject(it).let { e -> Err(e.getString("type"), e.getString("msg"), e.getString("stack")) } },
            note = o.optString("note"),
            startedAt = o.optLong("startedAt"),
            finishedAt = o.optLong("finishedAt"),
        )
    }

    fun toText(): String = buildString {
        appendLine("Пробник Таси — отчёт")
        Header.collect(App.instance).forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        ru.poneslas.tasya.probe.guide.Guide.parts.forEach { part ->
            appendLine("Часть ${part.n}. ${part.title}")
            part.steps.forEach { st ->
                val r = _all.value[st.id]
                appendLine("  ${st.title} — ${ru.poneslas.tasya.probe.ui.statusWord(r?.status ?: Status.NOT_RUN)}" + (ru.poneslas.tasya.probe.guide.Guide.plain(st.id).takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            }
        }
        appendLine()
        Registry.probes.forEach { p ->
            val r = _all.value[p.id] ?: return@forEach
            appendLine("${p.id} ${p.title} — ${r.status.label}")
            if (r.summary.isNotBlank()) appendLine("  ${r.summary}")
            r.metrics.forEach { (k, v) ->
                appendLine("  $k: " + if (v.size > 1) "медиана ${fmt(median(v))}, p90 ${fmt(percentile(v, 0.9))}, n=${v.size}" else fmt(v.first()))
            }
            r.errors.forEach { appendLine("  ! ${it.type}: ${it.msg}") }
            if (r.note.isNotBlank()) appendLine("  заметка: ${r.note}")
        }
    }
}

fun median(v: List<Double>) = percentile(v, 0.5)
fun percentile(v: List<Double>, p: Double): Double {
    if (v.isEmpty()) return Double.NaN
    val s = v.sorted()
    return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
}
fun fmt(d: Double) = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else "%.2f".format(d)
