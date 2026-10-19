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

    fun selectMode(id: String) = edit { it.copy(mode = id, lastUse = System.currentTimeMillis()) }
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
            val who = Brains.byId(mode.brain).title
            val phrase = clean
            val tools = PhoneTools(
                context, settings = { _state.value.settings },
                confirm = { title, detail -> confirm(modeId, title, detail) },
                report = { a -> push(modeId, a.copy(id = nextId())) },
                journal = { tool, params, title, status -> journal(tool, params, title, status, who, phrase) },
                notes = { _state.value.saved.notes },
                saveNote = { n -> edit { it.copy(notes = (it.notes + n).takeLast(500)) } },
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
        val s = _state.value.settings
        val policyId = when (m) { is Rules.Match.Timer -> "timer.set"; is Rules.Match.Alarm -> "alarm.set"; is Rules.Match.Torch -> "torch.set" }
        // «Спрашивать» и «Не разрешено» — через мозг: там карточка «Да / Отмена» и честный отказ.
        if (ru.poneslas.tasya.actions.Registry.policy(s, policyId) != Policy.AUTO) return false
        val (item, note) = when (m) {
            is Rules.Match.Timer -> PhoneActions.setTimer(context, m.seconds).fold(
                { FeedItem.Act(nextId(), "⏱", "Таймер запущен · ${m.title}", "на телефоне", "Открыть таймеры", "timers",
                    at = System.currentTimeMillis() + m.seconds * 1000L, countdown = true) to "Таймер ${m.title} запущен на телефоне." },
                { FeedItem.Act(nextId(), "⏱", "Таймер не запущен · ${m.title}", "«Часы» не ответили", state = "fail") to null })
            is Rules.Match.Alarm -> {
                val t = "%d:%02d".format(m.hour, m.minute)
                PhoneActions.setAlarm(context, m.hour, m.minute).fold(
                    { FeedItem.Act(nextId(), "⏰", "Будильник поставлен · $t", "на телефоне", "Открыть будильники", "alarms",
                        at = ru.poneslas.tasya.actions.Phone.nextOccurrence(m.hour, m.minute)) to "Будильник на $t поставлен на телефоне." },
                    { FeedItem.Act(nextId(), "⏰", "Будильник не поставлен · $t", "«Часы» не ответили", state = "fail") to null })
            }
            is Rules.Match.Torch -> ru.poneslas.tasya.actions.Phone.torch(context, m.on).fold(
                { FeedItem.Act(nextId(), "🔦", if (m.on) "Фонарик включён" else "Фонарик выключен", "на телефоне",
                    undo = if (m.on) "Выключить" else null, undoAction = if (m.on) "torch:off" else null) to (if (m.on) "Фонарик включён." else "Фонарик выключен.") },
                { FeedItem.Act(nextId(), "🔦", "Фонарик недоступен", "камера занята или вспышки нет", state = "fail") to null })
        }
        push(mode.id, item)
        journal(policyId, text, (item as FeedItem.Act).title, if (note != null) "выполнено" else "не выполнено", "Правило на телефоне", text)
        // Мозг узнаёт о локальном действии из истории — «а перенеси его на 8» сработает.
        if (note != null) { appendHistory(mode.id, ChatMsg("user", text)); appendHistory(mode.id, ChatMsg("assistant", note)) }
        return true
    }

    private fun journal(tool: String, params: String, title: String, status: String, who: String, phrase: String) {
        val locked = context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        edit { it.copy(journal = (it.journal + JournalEntry(nextId(), System.currentTimeMillis(), title, who, phrase, tool, params, status, locked)).takeLast(300)) }
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

    /** Кнопки строки действия: «Открыть …» ([FeedItem.Act.action]) и «Отменить / Выключить / Вернуть» ([FeedItem.Act.undoAction]). */
    fun onActButton(item: FeedItem.Act, undo: Boolean = false) {
        val a = (if (undo) item.undoAction else item.action) ?: return
        val P = ru.poneslas.tasya.actions.Phone
        val parts = a.split(":")
        val ok: Boolean = when (parts[0]) {
            "timers" -> ru.poneslas.tasya.actions.Launch.activity(context, android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS)).isSuccess
            "alarms" -> ru.poneslas.tasya.actions.Launch.activity(context, android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)).isSuccess
            "perm" -> { ru.poneslas.tasya.actions.Launch.askPermissions(context, a.removePrefix("perm:").split(",")); true }
            "dndaccess" -> P.openDndAccess(context).isSuccess
            "map" -> P.openMap(context, parts[1].toDouble(), parts[2].toDouble(), parts.drop(3).joinToString(":")).isSuccess
            "cal" -> if (parts[1] == "open") P.openEvent(context, parts[2].toLong()).isSuccess else P.deleteEvent(context, parts[2].toLong()).isSuccess
            "torch" -> P.torch(context, false).isSuccess
            "vol" -> P.volumeRaw(context, parts[1], parts[2].toInt()).isSuccess
            "dnd" -> P.dndRestore(context, parts[1].toInt()).isSuccess
            else -> false
        }
        // Отменили — строка гаснет и говорит, что сделано; модель узнаёт из истории режима.
        if (undo && ok) {
            val modeId = _state.value.saved.feeds.entries.firstOrNull { e -> e.value.any { it.id == item.id } }?.key ?: return
            replace(modeId, item.id) { (it as FeedItem.Act).copy(state = "undone", subtitle = "отменено", undo = null, undoAction = null, countdown = false, at = null) }
            appendHistory(modeId, ChatMsg("user", "(Пользователь отменил кнопкой: ${item.title})"))
            appendHistory(modeId, ChatMsg("assistant", "Отменено: ${item.title}."))
        }
    }
}
