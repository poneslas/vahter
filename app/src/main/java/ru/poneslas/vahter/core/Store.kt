package ru.poneslas.vahter.core

import android.content.Context
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
    val notes: List<Note> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    /** Сессии режимов в Hermes (ключ — id режима); история этих режимов живёт на сервере. */
    val hermesSessions: Map<String, HermesSession> = emptyMap(),
)

class Store(context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Saved = runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrElse { Saved() }.let { s ->
        // Встроенные режимы не редактируются (Бэклог №14), их тексты — из ресурсов на языке телефона (№15), а не из сохранённого.
        s.copy(modes = s.modes.map { m -> Modes.builtin.firstOrNull { it.id == m.id }?.let { b -> m.copy(title = b.title, hint = b.hint, placeholder = b.placeholder, prompt = if (m.promptEdited) m.prompt else b.prompt, icon = b.icon) } ?: m })
    }

    fun save(s: Saved) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Saved.serializer(), s))
        tmp.renameTo(file)
    }
}
