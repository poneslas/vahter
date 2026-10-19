package ru.poneslas.tasya.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.poneslas.tasya.actions.PhoneActions
import ru.poneslas.tasya.actions.Rules
import ru.poneslas.tasya.brain.Brain
import ru.poneslas.tasya.brain.BrainEvent
import ru.poneslas.tasya.brain.HistoryMessage
import ru.poneslas.tasya.brain.Turn

/**
 * Разговор: лента каждого режима, текущий режим, черновики поля. Один на лист и приложение
 * (Продукт 5.1: «лист и приложение — одно и то же»). Пока в памяти процесса; Room — в следующих вехах.
 */
class Conversation(
    private val context: Context,
    private val scope: CoroutineScope,
    private val brainFor: (Mode) -> Brain,
) {
    data class State(
        val modes: List<Mode> = Modes.builtin + Modes.sample,
        val mode: String = Modes.MAIN,
        val feeds: Map<String, List<FeedItem>> = emptyMap(),
        val drafts: Map<String, String> = emptyMap(),
        val busy: Boolean = false,
    ) {
        val current: Mode get() = modes.firstOrNull { it.id == mode } ?: modes.first()
        val feed: List<FeedItem> get() = feeds[mode].orEmpty()
        val draft: String get() = drafts[mode].orEmpty()
        val tabs: List<Mode> get() = modes.filter { it.enabled }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var seq = 0L
    private fun nextId() = ++seq
    private var job: Job? = null

    fun selectMode(id: String) = _state.update { it.copy(mode = id) }

    fun setDraft(text: String) = _state.update { it.copy(drafts = it.drafts + (it.mode to text)) }

    private fun push(modeId: String, item: FeedItem) = _state.update {
        it.copy(feeds = it.feeds + (modeId to (it.feeds[modeId].orEmpty() + item)))
    }

    private fun replace(modeId: String, id: Long, f: (FeedItem) -> FeedItem) = _state.update { s ->
        s.copy(feeds = s.feeds + (modeId to s.feeds[modeId].orEmpty().map { if (it.id == id) f(it) else it }))
    }

    /** Отправить реплику: сначала локальные правила, потом мозг режима. */
    fun send(text: String, via: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val s = _state.value
        val mode = s.current
        _state.update { it.copy(drafts = it.drafts + (mode.id to "")) }
        push(mode.id, FeedItem.Me(nextId(), clean, via))

        if (mode.tools) {
            when (val m = Rules.match(clean)) {
                is Rules.Match.Timer -> {
                    val r = PhoneActions.setTimer(context, m.seconds)
                    push(mode.id, if (r.isSuccess) FeedItem.Act(nextId(), "⏱", "Таймер · ${m.title}", "на телефоне", "Открыть таймеры")
                    else FeedItem.Err(nextId(), "Не получилось поставить таймер: «Часы» не ответили."))
                    return
                }
                is Rules.Match.Alarm -> {
                    val r = PhoneActions.setAlarm(context, m.hour, m.minute)
                    push(mode.id, if (r.isSuccess) FeedItem.Act(nextId(), "⏰", "Будильник · %d:%02d".format(m.hour, m.minute), "на телефоне", "Открыть Часы")
                    else FeedItem.Err(nextId(), "Не получилось поставить будильник: «Часы» не ответили."))
                    return
                }
                null -> Unit
            }
        }

        val history = s.feeds[mode.id].orEmpty().mapNotNull {
            when (it) {
                is FeedItem.Me -> HistoryMessage(HistoryMessage.Role.USER, it.text)
                is FeedItem.Ai -> HistoryMessage(HistoryMessage.Role.ASSISTANT, it.text)
                else -> null
            }
        }
        val answerId = nextId()
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(busy = true) }
            var started = false
            try {
                brainFor(mode).send(Turn(mode, clean, history)).collect { e ->
                    when (e) {
                        is BrainEvent.Delta -> {
                            if (!started) { push(mode.id, FeedItem.Ai(answerId, e.text, done = false)); started = true }
                            else replace(mode.id, answerId) { (it as FeedItem.Ai).copy(text = it.text + e.text) }
                        }
                        BrainEvent.MessageEnd -> if (started) replace(mode.id, answerId) { (it as FeedItem.Ai).copy(text = it.text.trimEnd(), done = true) }
                        is BrainEvent.Error -> push(mode.id, FeedItem.Err(nextId(), e.text))
                        is BrainEvent.Step, is BrainEvent.ToolCall -> Unit // вехи 3 и 5
                    }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Действие кнопки на строке итога. */
    fun onActButton(item: FeedItem.Act) {
        when {
            item.title.startsWith("Таймер") -> PhoneActions.showTimers(context)
            else -> PhoneActions.showAlarms(context)
        }
    }
}
