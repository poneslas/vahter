package ru.poneslas.tasya.brain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import ru.poneslas.tasya.core.Mode

/**
 * Мозг: реплика → поток событий (Архитектура, 2.2). Набор событий полный, по Hermes;
 * прямой API использует подмножество. Реализации: [FakeBrain], [ApiBrain] (DeepSeek и любой OpenAI-совместимый),
 * HermesBrain — этап 5.
 */
interface Brain {
    fun send(turn: Turn, tools: ToolHost): Flow<BrainEvent>
}

/** Сообщение истории режима — в формате OpenAI chat: его и шлём целиком (2.2, п. 1, 4). */
@Serializable
data class ChatMsg(
    val role: String,                       // system | user | assistant | tool
    val content: String? = null,
    val images: List<String> = emptyList(), // data:image/jpeg;base64,… — только у user
    val toolCalls: List<ToolCallMsg> = emptyList(),
    val toolCallId: String? = null,
)

@Serializable
data class ToolCallMsg(val id: String, val name: String, val arguments: String)

data class Turn(
    val mode: Mode,
    /** Вся история режима, уже с новой репликой пользователя в конце. */
    val history: List<ChatMsg>,
)

/** Исполнитель действий телефона для мозга: проверяет разрешения, при «Спрашивать» ждёт «Да». */
interface ToolHost {
    /** Описания функций для API (JSON Schema). Пусто — у режима нет действий. */
    fun specs(mode: Mode): List<ToolSpec>
    suspend fun call(name: String, argumentsJson: String): String
}

data class ToolSpec(val name: String, val description: String, val parametersJson: String)

sealed interface BrainEvent {
    data class Delta(val text: String) : BrainEvent
    data object MessageEnd : BrainEvent
    data class Step(val title: String, val phone: Boolean) : BrainEvent
    /** Новое сообщение для истории режима (ответ ассистента, вызов функции, результат функции). */
    data class History(val msg: ChatMsg) : BrainEvent
    data class Error(val text: String, val kind: ErrorKind = ErrorKind.OTHER) : BrainEvent
}

enum class ErrorKind { AUTH, BALANCE, RATE, CONTEXT, NETWORK, OTHER }

/** Мозг без сети — для проверки листа и демонстрации без ключа. */
class FakeBrain : Brain {
    override fun send(turn: Turn, tools: ToolHost): Flow<BrainEvent> = flow {
        delay(400)
        val answer = "Мозг не подключён. Добавьте ключ DeepSeek: Настройки → Мозги → DeepSeek."
        emit(BrainEvent.Delta(answer))
        emit(BrainEvent.History(ChatMsg("assistant", answer)))
        emit(BrainEvent.MessageEnd)
    }
}
