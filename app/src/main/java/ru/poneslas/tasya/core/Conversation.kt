package ru.poneslas.tasya.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.poneslas.tasya.actions.PhoneActions
import ru.poneslas.tasya.actions.PhoneTools
import ru.poneslas.tasya.actions.Rules
import ru.poneslas.tasya.brain.Brain
import ru.poneslas.tasya.brain.BrainEvent
import ru.poneslas.tasya.brain.ChatMsg
import ru.poneslas.tasya.brain.ErrorKind
import ru.poneslas.tasya.brain.Turn

/**
 * Разговор: лента и история каждого режима, текущий режим, черновики поля, настройки.
 * Один на лист и приложение (Продукт 5.1). Хранится на телефоне ([Store]).
 */
@OptIn(FlowPreview::class)
class Conversation(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: Store,
    private val brainFor: (Mode) -> Brain,
    /** Ответ пришёл — показать уведомление, если ни лист, ни приложение не на экране. */
    private val onAnswer: (Mode, String) -> Unit,
) {
    data class State(
        val saved: Saved = Saved(),
        val drafts: Map<String, String> = emptyMap(),
        val busy: Boolean = false,
    ) {
        val modes get() = saved.modes
        val mode get() = saved.mode
        val settings get() = saved.settings
        val current: Mode get() = modes.firstOrNull { it.id == mode } ?: modes.first()
        val feed: List<FeedItem> get() = saved.feeds[mode].orEmpty()
        val draft: String get() = drafts[mode].orEmpty()
        val tabs: List<Mode> get() = modes.filter { it.enabled || it.id == Modes.MAIN }
    }

    private val _state = MutableStateFlow(State(saved = store.load()))
    val state: StateFlow<State> = _state.asStateFlow()

    private var seq = System.currentTimeMillis()
    private fun nextId() = ++seq
    private var job: Job? = null
    private val pending = mutableMapOf<Long, CompletableDeferred<Boolean>>()

    init {
        _state.drop(1).debounce(400).onEach { s -> withContext(Dispatchers.IO) { runCatching { store.save(s.saved) } } }.launchIn(scope)
    }

    private fun edit(f: (Saved) -> Saved) = _state.update { it.copy(saved = f(it.saved)) }

    fun selectMode(id: String) = edit { it.copy(mode = id) }
    fun setDraft(text: String) = _state.update { it.copy(drafts = it.drafts + (it.mode to text)) }
    fun updateSettings(f: (Settings) -> Settings) = edit { it.copy(settings = f(it.settings)) }
    fun updateModes(f: (List<Mode>) -> List<Mode>) = edit { it.copy(modes = f(it.modes)) }
    fun touch() = edit { it.copy(lastUse = System.currentTimeMillis()) }

    private fun push(modeId: String, item: FeedItem) = edit { it.copy(feeds = it.feeds + (modeId to (it.feeds[modeId].orEmpty() + item))) }
    private fun replace(modeId: String, id: Long, f: (FeedItem) -> FeedItem) = edit { s ->
        s.copy(feeds = s.feeds + (modeId to s.feeds[modeId].orEmpty().map { if (it.id == id) f(it) else it }))
    }
    private fun remove(modeId: String, id: Long) = edit { s -> s.copy(feeds = s.feeds + (modeId to s.feeds[modeId].orEmpty().filterNot { it.id == id })) }
    private fun appendHistory(modeId: String, msg: ChatMsg) = edit { it.copy(histories = it.histories + (modeId to (it.histories[modeId].orEmpty() + msg))) }

    /** «+»: новый режим. id выдаётся один раз и не меняется при переименовании (для Hermes это ключ сессии). */
    fun addMode(): String {
        val id = "m-" + java.util.UUID.randomUUID().toString().take(4)
        updateModes { it + Mode(id = id, title = "Новый режим", icon = "star", placeholder = "Вопрос или задача") }
        return id
    }

    fun editMode(id: String, f: (Mode) -> Mode) = updateModes { list -> list.map { if (it.id == id) f(it) else it } }

    fun deleteMode(id: String) = edit { s ->
        s.copy(modes = s.modes.filterNot { it.id == id }, feeds = s.feeds - id, histories = s.histories - id,
            mode = if (s.mode == id) Modes.MAIN else s.mode)
    }

    /** «Начать заново»: стереть разговор режима с телефона. */
    fun restart(modeId: String = _state.value.mode) = edit { it.copy(feeds = it.feeds - modeId, histories = it.histories - modeId) }

    /** Вложение ждёт отправки: 2 с на «✕» и на вопрос; начат вопрос — ждёт его (Продукт 5.17). */
    private data class Pending(val modeId: String, val meId: Long, val paths: List<String>, val via: String)
    private var pendingAttach: Pending? = null

    /** Снимки или фото из галереи → пузырь в ленте; уходят мозгу одной репликой (альбом). */
    fun attach(paths: List<String>, via: String) {
        if (paths.isEmpty()) return
        pendingAttach?.let { commit(it, "") }
        val mode = _state.value.current
        val meId = nextId()
        val p = Pending(mode.id, meId, paths, via)
        pendingAttach = p
        touch()
        push(mode.id, FeedItem.Me(meId, "", via, paths, status = "Готовлю…"))
        scope.launch {
            kotlinx.coroutines.delay(2000)
            while (pendingAttach === p) {
                val draft = _state.value.drafts[p.modeId].orEmpty()
                if (draft.isBlank()) { commit(p, ""); break }
                replace(p.modeId, p.meId) { (it as FeedItem.Me).copy(status = "ждёт ваш вопрос") }
                kotlinx.coroutines.delay(300)
            }
        }
    }

    /** «✕» на пузыре: отменить, пока не ушло; начатый вопрос остаётся в поле. */
    fun cancelAttach(meId: Long) {
        val p = pendingAttach ?: return
        if (p.meId != meId) return
        pendingAttach = null
        remove(p.modeId, p.meId)
    }

    private fun commit(p: Pending, text: String) {
        if (pendingAttach !== p) return
        pendingAttach = null
        replace(p.modeId, p.meId) { (it as FeedItem.Me).copy(text = text, status = null) }
        val mode = _state.value.modes.firstOrNull { it.id == p.modeId } ?: return
        val images = p.paths
        val n = p.paths.size
        val note = if (n > 1) "[$n кадра, по порядку] " else ""
        toBrain(mode, (note + text).trim(), images)
    }

    /** Отправить реплику: сначала локальные правила, потом мозг режима. */
    fun send(text: String, via: String) {
        val clean = text.trim()
        val s = _state.value
        val mode = s.current
        // Вложение ещё не ушло — вопрос уходит вместе с ним одной репликой.
        pendingAttach?.takeIf { it.modeId == mode.id }?.let { p ->
            _state.update { it.copy(drafts = it.drafts + (mode.id to "")) }
            commit(p, clean); return
        }
        if (clean.isEmpty()) return
        _state.update { it.copy(drafts = it.drafts + (mode.id to "")) }
        touch()
        push(mode.id, FeedItem.Me(nextId(), clean, via))
        if (mode.tools && localRule(mode, clean)) return
        toBrain(mode, clean, emptyList())
    }

    private fun toBrain(mode: Mode, clean: String, images: List<String>) {
        val userMsg = ChatMsg("user", clean.ifEmpty { null }, images = images)
        appendHistory(mode.id, userMsg)
        val history = _state.value.saved.histories[mode.id].orEmpty()
        val modeId = mode.id
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(busy = true) }
            var answerId: Long? = null
            var stepsId: Long? = null
            val answer = StringBuilder()
            val tools = PhoneTools(
                context, settings = { _state.value.settings },
                confirm = { title, detail -> confirm(modeId, title, detail) },
                report = { icon, title, sub, button, action -> push(modeId, FeedItem.Act(nextId(), icon, title, sub, button, action)) },
            )
            try {
                brainFor(mode).send(Turn(mode, history), tools).collect { e ->
                    when (e) {
                        is BrainEvent.Delta -> {
                            answer.append(e.text)
                            val id = answerId
                            if (id == null) { answerId = nextId().also { push(modeId, FeedItem.Ai(it, e.text, done = false)) } }
                            else replace(modeId, id) { (it as FeedItem.Ai).copy(text = it.text + e.text) }
                        }
                        is BrainEvent.Step -> {
                            val id = stepsId
                            if (id == null) stepsId = nextId().also { push(modeId, FeedItem.Steps(it, listOf(e.title), running = true)) }
                            else replace(modeId, id) { (it as FeedItem.Steps).copy(titles = it.titles + e.title) }
                            answerId = null // следующий текст — новым сообщением после шагов
                        }
                        is BrainEvent.History -> appendHistory(modeId, e.msg)
                        BrainEvent.MessageEnd -> Unit
                        is BrainEvent.Error -> push(modeId, when (e.kind) {
                            ErrorKind.CONTEXT -> FeedItem.Card(nextId(), "${Brains.byId(mode.brain).title} · ошибка", "Разговор слишком длинный",
                                "Модель больше не помещает всю историю режима. Начните заново: промпт и настройки останутся.", listOf("Начать заново" to "restart"), phone = false)
                            else -> FeedItem.Err(nextId(), e.text)
                        })
                    }
                }
            } finally {
                stepsId?.let { id -> replace(modeId, id) { (it as FeedItem.Steps).copy(running = false) } }
                edit { st -> st.copy(feeds = st.feeds + (modeId to st.feeds[modeId].orEmpty().map { if (it is FeedItem.Ai && !it.done) it.copy(done = true, text = it.text.trimEnd()) else it })) }
                _state.update { it.copy(busy = false) }
                if (answer.isNotBlank()) onAnswer(mode, answer.toString())
            }
        }
    }

    /** Точные короткие команды — без мозга и без сети (Архитектура 5). */
    private fun localRule(mode: Mode, text: String): Boolean {
        val m = Rules.match(text) ?: return false
        val policyId = if (m is Rules.Match.Timer) "timer.set" else "alarm.set"
        if (_state.value.settings.policy[policyId] == Policy.OFF) return false
        val (item, note) = when (m) {
            is Rules.Match.Timer -> PhoneActions.setTimer(context, m.seconds).fold(
                { FeedItem.Act(nextId(), "⏱", "Таймер · ${m.title}", "на телефоне", "Открыть таймеры", "timers") to "Таймер ${m.title} поставлен на телефоне." },
                { FeedItem.Err(nextId(), "Не получилось поставить таймер: «Часы» не ответили.") to null })
            is Rules.Match.Alarm -> PhoneActions.setAlarm(context, m.hour, m.minute).fold(
                { FeedItem.Act(nextId(), "⏰", "Будильник · %d:%02d".format(m.hour, m.minute), "на телефоне", "Открыть Часы", "alarms") to "Будильник на %d:%02d поставлен на телефоне.".format(m.hour, m.minute) },
                { FeedItem.Err(nextId(), "Не получилось поставить будильник: «Часы» не ответили.") to null })
        }
        push(mode.id, item)
        // Мозг узнаёт о локальном действии из истории — «а перенеси его на 8» сработает.
        if (note != null) { appendHistory(mode.id, ChatMsg("user", text)); appendHistory(mode.id, ChatMsg("assistant", note)) }
        return true
    }

    /** Карточка «Поставить / Отмена» и ожидание ответа. */
    private suspend fun confirm(modeId: String, title: String, detail: String): Boolean {
        val id = nextId()
        val d = CompletableDeferred<Boolean>()
        pending[id] = d
        push(modeId, FeedItem.Card(id, "${Brains.byId(_state.value.current.brain).title} просит", title, detail, listOf("Да" to "yes", "Отмена" to "no")))
        val ok = d.await()
        pending.remove(id)
        remove(modeId, id)
        return ok
    }

    fun onCardButton(card: FeedItem.Card, action: String) {
        when (action) {
            "yes" -> pending[card.id]?.complete(true)
            "no" -> pending[card.id]?.complete(false) ?: remove(_state.value.mode, card.id)
            "restart" -> restart()
            else -> remove(_state.value.mode, card.id)
        }
    }

    fun onActButton(item: FeedItem.Act) {
        when (item.action) {
            "timers" -> PhoneActions.showTimers(context)
            else -> PhoneActions.showAlarms(context)
        }
    }
}
