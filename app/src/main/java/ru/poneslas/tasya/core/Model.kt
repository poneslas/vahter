package ru.poneslas.tasya.core

/**
 * Модель данных — один к одному с прототипом (docs/design/prototype/model.js) и
 * Архитектурой (2.2 — мозги, 9.6.1 — режимы). Всё, что видно на экране режима, берётся из этих полей.
 */

enum class Primary { VOICE, PHOTO }

data class Accepts(
    val voice: Boolean = true,
    val photo: Boolean = false,
    val file: Boolean = false,
)

/** Режим — отдельный разговор с готовым промптом. id постоянный: встроенные — слова, свои — «m-xxxx». */
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
            hint = "Спросите что угодно или попросите телефон: таймер, будильник, календарь.",
            placeholder = "Спросить или попросить",
            accepts = Accepts(voice = true, photo = true, file = true),
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
        prompt = "Я пришлю фото меню. Выбери мне 1–2 блюда и напиток под мои вкусы: не острое, без свинины, люблю рыбу и овощи. Если меню не на русском — пиши названия в оригинале и по-русски, чтобы я мог показать официанту. Коротко. Не сохраняй это в память.",
    )
}

/** Описание мозга. Ключ — в Keystore, здесь не хранится. */
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
}

/** Элемент ленты разговора. */
sealed interface FeedItem {
    val id: Long

    /** Реплика пользователя. [via] — «голос», «клавиатура», «голос, правка». */
    data class Me(override val id: Long, val text: String, val via: String) : FeedItem

    /** Ответ мозга. [done] = false, пока идёт поток. */
    data class Ai(override val id: Long, val text: String, val done: Boolean) : FeedItem

    /** Строка итога действия на телефоне. */
    data class Act(
        override val id: Long,
        val icon: String,
        val title: String,
        val subtitle: String,
        val button: String? = null,
    ) : FeedItem

    /** Ошибка по-русски. */
    data class Err(override val id: Long, val text: String) : FeedItem
}
