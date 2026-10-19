package ru.poneslas.tasya.core

import kotlinx.serialization.Serializable

/**
 * Модель данных — один к одному с прототипом (docs/design/prototype/model.js) и
 * Архитектурой (2.2 — мозги, 9.6.1 — режимы). Всё, что видно на экране режима, берётся из этих полей.
 */

@Serializable enum class Primary { VOICE, PHOTO }

@Serializable
data class Accepts(
    val voice: Boolean = true,
    val photo: Boolean = false,
    val file: Boolean = false,
)

/** Режим — отдельный разговор с готовым промптом. id постоянный: встроенные — слова, свои — «m-xxxx». */
@Serializable
data class Mode(
    val id: String,
    val title: String,
    val icon: String,
    val hint: String = "",
    val placeholder: String = "Вопрос или задача",
    val prompt: String = "",
    val accepts: Accepts = Accepts(),
    val primary: Primary = Primary.VOICE,
    val brain: String = "deepseek",
    val tools: Boolean = false,
    val builtin: Boolean = false,
    val enabled: Boolean = true,
)

object Modes {
    const val MAIN = "main"

    val builtin = listOf(
        Mode(
            id = MAIN, title = "Ассистент", icon = "spark", builtin = true, tools = true,
            hint = "Спросите что угодно или попросите телефон: таймер, будильник.",
            placeholder = "Спросить или попросить",
            accepts = Accepts(voice = true, photo = true, file = false),
        ),
        Mode(
            id = "translate", title = "Переводчик", icon = "transl", builtin = true,
            hint = "Скажите или напишите — переведу. Можно сфотографировать надпись или меню.",
            placeholder = "Что перевести?",
            accepts = Accepts(voice = true, photo = true),
            prompt = "Всё не на русском — переведи на русский. Всё на русском — переведи на китайский, добавь пиньинь. Коротко, без вступлений. Не сохраняй это в память.",
        ),
        Mode(
            id = "whatsthis", title = "Что это?", icon = "lens", builtin = true, primary = Primary.PHOTO,
            hint = "Сфотографируйте товар, вывеску или меню — коротко объясню, что это.",
            placeholder = "Вопрос к фото",
            accepts = Accepts(voice = true, photo = true),
            prompt = "Пользователь в поездке. По фото коротко скажи: что это, для чего, нормальная ли цена, на что обратить внимание. Переведи надписи. Не сохраняй это в память.",
        ),
    )

    /** Пример своего режима — как если бы его создали через «+». */
    val sample = Mode(
        id = "m-4c1e", title = "Гастрогид", icon = "food", primary = Primary.PHOTO,
        hint = "Сфотографируйте меню — выберу под ваши вкусы.",
        placeholder = "Вопрос к меню",
        accepts = Accepts(voice = true, photo = true),
        prompt = "Я пришлю фото меню (может быть несколько кадров). Выбери мне 1–2 блюда и напиток под мои вкусы: не острое, без свинины, люблю рыбу и овощи. Если меню не на русском — пиши названия в оригинале и по-русски, чтобы я мог показать официанту. Коротко. Не сохраняй это в память.",
    )
}

/** Описание мозга. Ключ — в Keystore ([Secrets]), здесь не хранится. */
data class BrainDesc(
    val id: String,
    val kind: Kind,
    val title: String,
    val url: String = "",
    val model: String = "",
    val vision: Boolean = false,
    val tools: Boolean = false,
    val memory: Boolean = false,
    val server: Boolean = false,
    /** Наш лимит картинок в реплике; у DeepSeek API принимает не меньше (замер 18.10). */
    val maxImages: Int = 10,
) {
    enum class Kind { API, HERMES }
}

object Brains {
    val deepseek = BrainDesc(
        id = "deepseek", kind = BrainDesc.Kind.API, title = "DeepSeek",
        url = "https://api.deepseek.com", model = "deepseek-chat",
        vision = true, tools = true,
    )
    val hermes = BrainDesc(
        id = "hermes", kind = BrainDesc.Kind.HERMES, title = "Hermes",
        vision = true, tools = true, memory = true, server = true,
    )
    val all = listOf(deepseek, hermes)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: deepseek
}

/** Действие на телефоне: «Разрешено» / «Спрашивать» / «Не разрешено» (Архитектура Б.2). */
@Serializable enum class Policy { AUTO, ASK, OFF }

/** Заблокированный экран: по умолчанию сначала разблокировка (Продукт 5.7). */
@Serializable enum class LockScope { OFF, SAFE, ON }

@Serializable enum class ReturnToAssistant { MIN30, NEVER, ALWAYS }

@Serializable
data class Settings(
    val lockScope: LockScope = LockScope.OFF,
    val returnToAssistant: ReturnToAssistant = ReturnToAssistant.MIN30,
    /** Только то, что пользователь менял; остальное — по умолчанию из реестра ([ru.poneslas.tasya.actions.Registry]). */
    val policy: Map<String, Policy> = emptyMap(),
    val onboarded: Boolean = false,
    /** Голосовой режим при вызове помощника сразу пишет (Бэклог №13). Только вызов — не уведомление и не возврат. */
    val listenOnCall: Boolean = true,
)

/** Элемент ленты разговора. */
@Serializable
sealed interface FeedItem {
    val id: Long

    /** Реплика пользователя. [via] — «голос», «клавиатура», «фото»… [images] — миниатюры (пути к файлам). */
    @Serializable
    data class Me(override val id: Long, val text: String, val via: String, val images: List<String> = emptyList(), val status: String? = null) : FeedItem

    /** Ответ мозга. [done] = false, пока идёт поток. */
    @Serializable
    data class Ai(override val id: Long, val text: String, val done: Boolean) : FeedItem

    /** Свёрнутая строка шагов работы мозга. */
    @Serializable
    data class Steps(override val id: Long, val titles: List<String>, val running: Boolean) : FeedItem

    /**
     * Строка итога действия на телефоне: заголовок с глаголом («Будильник поставлен · 7:00»), пояснение,
     * состояние значка ([state]: done — галочка, fail — крестик, info — без отметки), срок [at] —
     * «прозвенит через …» или живой отсчёт таймера ([countdown]); две кнопки: открыть и отменить.
     */
    @Serializable
    data class Act(
        override val id: Long,
        val icon: String,
        val title: String,
        val subtitle: String,
        val button: String? = null,
        val action: String? = null,
        val state: String = "done",
        val at: Long? = null,
        val countdown: Boolean = false,
        val undo: String? = null,
        val undoAction: String? = null,
    ) : FeedItem

    /** Карточка с кнопками: подтверждение действия, ошибка с «Начать заново»… */
    @Serializable
    data class Card(
        override val id: Long,
        val head: String,
        val title: String,
        val text: String = "",
        val buttons: List<Pair<String, String>> = emptyList(),
        val phone: Boolean = true,
    ) : FeedItem

    /** Ошибка по-русски. */
    @Serializable
    data class Err(override val id: Long, val text: String) : FeedItem
}

/** Заметка или сохранённое место — хранится на телефоне, мозг находит через note_find / place_list. */
@Serializable
data class Note(val id: Long, val text: String, val at: Long, val place: Boolean = false, val lat: Double? = null, val lon: Double? = null, val address: String? = null)

/** Запись журнала действий (Архитектура Б.3): что, кто попросил, с какой фразы, чем кончилось. Только на телефоне. */
@Serializable
data class JournalEntry(
    val id: Long, val at: Long, val title: String, val who: String, val phrase: String,
    val tool: String, val params: String, val status: String, val locked: Boolean,
)
