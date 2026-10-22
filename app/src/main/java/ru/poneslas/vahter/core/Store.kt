package ru.poneslas.vahter.core

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.poneslas.vahter.brain.ChatMsg
import java.io.File

/**
 * Хранение на телефоне: режимы, настройки, ленты и истории режимов — один JSON-файл.
 * История режима целиком уходит мозгу с каждой репликой (2.2); на телефоне она одна.
 */
@Serializable
data class Saved(
    val modes: List<Mode> = Modes.builtin + Modes.sample,
    val mode: String = Modes.MAIN,
    val feeds: Map<String, List<FeedItem>> = emptyMap(),
    val histories: Map<String, List<ChatMsg>> = emptyMap(),
    val settings: Settings = Settings(),
    val lastUse: Long = 0,
    /** Когда режимом пользовались последний раз (ключ — id режима): по нему «30 минут без дела» — у каждого режима своё. */
    val modeUse: Map<String, Long> = emptyMap(),
    /** С какого элемента ленты начинается текущий разговор режима; раньше — «Прошлый разговор» (переживает перезапуск). */
    val fresh: Map<String, Int> = emptyMap(),
    val notes: List<Note> = emptyList(),
    /** Журнала действий больше нет (22.10) — поле оставлено, чтобы читались старые файлы. */
    val journal: List<JournalEntry> = emptyList(),
    /** Сессии режимов в Hermes (ключ — id режима); история этих режимов живёт на сервере. */
    val hermesSessions: Map<String, HermesSession> = emptyMap(),
    /** Незавершённые ходы в Hermes (ключ — id режима): дочитываются при запуске, если приложение закрывали посреди ответа. */
    val hermesRuns: Map<String, HermesRun> = emptyMap(),
)

/** Файлы, на которые ссылается сохранённое: снимки и фото реплик, файлы от мозга, картинки в истории для мозга (абсолютные пути). */
fun Saved.files(): Set<String> = buildSet {
    feeds.values.forEach { feed -> feed.forEach { i ->
        when (i) {
            is FeedItem.Me -> addAll(i.images)
            is FeedItem.Files -> { addAll(i.images); i.files.mapNotNullTo(this) { it.path } }
            else -> {}
        }
    } }
    histories.values.forEach { h -> h.forEach { addAll(it.images) } }
}.mapTo(HashSet()) { File(it).absolutePath }

class Store(context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val atomic = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Saved = read().let { s ->
        // Встроенные режимы не редактируются (Бэклог №14), их тексты — из ресурсов на языке телефона (№15), а не из сохранённого.
        s.copy(modes = s.modes.map { m -> Modes.builtin.firstOrNull { it.id == m.id }?.let { b -> m.copy(title = b.title, hint = b.hint, placeholder = b.placeholder, prompt = if (m.promptEdited) m.prompt else b.prompt, icon = b.icon) } ?: m })
    }

    /** Файла нет — чистый лист. Не прочитался — копия в state.json.bad-<время> (последние три), иначе следующее сохранение затрёт всё молча. */
    private fun read(): Saved {
        val text = runCatching { atomic.readFully().decodeToString() }.getOrElse { return Saved() }
        return runCatching { json.decodeFromString(Saved.serializer(), text) }.getOrElse { e ->
            Log.e(TAG, "state.json не прочитался, копия рядом", e)
            runCatching {
                File(file.path + ".bad-" + System.currentTimeMillis()).writeText(text)
                file.parentFile?.listFiles { f -> f.name.startsWith(file.name + ".bad-") }?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
            }
            Saved()
        }
    }

    fun save(s: Saved) {
        val out = atomic.startWrite()
        try { out.write(json.encodeToString(Saved.serializer(), s).toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }

    private companion object { const val TAG = "Store" }
}
