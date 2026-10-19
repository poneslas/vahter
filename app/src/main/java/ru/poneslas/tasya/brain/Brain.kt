package ru.poneslas.tasya.brain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.poneslas.tasya.core.Mode

/**
 * Мозг: реплика → поток событий (Архитектура, 2.2). Набор событий полный, по Hermes;
 * прямой API использует подмножество. Реализации: [FakeBrain] (каркас), ApiBrain (DeepSeek), HermesBrain (этап 5).
 */
interface Brain {
    fun send(turn: Turn): Flow<BrainEvent>
}

data class Turn(
    val mode: Mode,
    val text: String,
    /** Вся история режима: прямой API шлёт её целиком (2.2, п. 1). */
    val history: List<HistoryMessage>,
)

data class HistoryMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

sealed interface BrainEvent {
    /** Кусок текста ответа. */
    data class Delta(val text: String) : BrainEvent
    /** Ответ закончен. */
    data object MessageEnd : BrainEvent
    /** Шаг работы (поиск, команда, действие телефона). */
    data class Step(val title: String, val phone: Boolean) : BrainEvent
    /** Мозг просит действие телефона. */
    data class ToolCall(val name: String, val argsJson: String) : BrainEvent
    /** Ошибка — уже по-русски. */
    data class Error(val text: String) : BrainEvent
}

/** Мозг для каркаса: отвечает заготовками, чтобы проверить лист и ленту без сети. */
class FakeBrain : Brain {
    override fun send(turn: Turn): Flow<BrainEvent> = flow {
        delay(500)
        val answer = when {
            turn.text.contains("завтра", true) -> "Завтра две встречи: в 10:00 банк, в 15:30 созвон с Олей. Утро свободно."
            turn.mode.id != "main" -> "Режим «${turn.mode.title}»: здесь будет ответ модели по промпту режима."
            else -> "Это ответ-заглушка. Настоящий мозг — DeepSeek — подключается в следующей сборке."
        }
        for (word in answer.split(" ")) {
            emit(BrainEvent.Delta("$word "))
            delay(40)
        }
        emit(BrainEvent.MessageEnd)
    }
}
