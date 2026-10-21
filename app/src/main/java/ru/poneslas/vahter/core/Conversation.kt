package ru.poneslas.vahter.core

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
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
import ru.poneslas.vahter.actions.PhoneActions
import ru.poneslas.vahter.actions.PhoneTools
import ru.poneslas.vahter.actions.Rules
import ru.poneslas.vahter.brain.Brain
import ru.poneslas.vahter.brain.HermesBrain
import ru.poneslas.vahter.brain.BrainEvent
import ru.poneslas.vahter.brain.ChatMsg
import ru.poneslas.vahter.brain.ErrorKind
import ru.poneslas.vahter.brain.Turn
import ru.poneslas.vahter.actions.Registry
import ru.poneslas.vahter.actions.Phone
import ru.poneslas.vahter.assistant.Sheet
import ru.poneslas.vahter.actions.Launch

/** После стольких минут тишины следующая реплика режима на Hermes начинает новую сессию (Архитектура 9.7). */
private const val HERMES_IDLE_MS = 30 * 60 * 1000L

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
    /** Hermes, если подключён: дочитать незавершённый ход, дописать вдогонку, остановить (Архитектура 9.0). */
    private val hermes: () -> HermesBrain? = { null },
) {
    data class State(
        val saved: Saved = Saved(),
        val drafts: Map<String, String> = emptyMap(),
        /** Режимы, в которых сейчас идёт ход мозга (у Hermes ходы разных режимов идут параллельно). */
        val busyModes: Set<String> = emptySet(),
    ) {
        val busy get() = mode in busyModes
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
    /** Идущий ход каждого режима. */
    private val jobs = mutableMapOf<String, Job>()
    private val pending = mutableMapOf<Long, CompletableDeferred<Boolean>>()
    /** Открытые просьбы Hermes подтвердить команду (id карточки → ответ серверу). */
    private val asks = mutableMapOf<Long, suspend (String) -> Unit>()

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
        updateModes { it + Mode(id = id, title = tr(R.string.feed_new_mode), icon = "star", placeholder = tr(R.string.mode_assistant_hint)) }
        return id
    }

    fun editMode(id: String, f: (Mode) -> Mode) = updateModes { list -> list.map { if (it.id == id) f(it) else it } }

    fun deleteMode(id: String) = edit { s ->
        s.copy(modes = s.modes.filterNot { it.id == id }, feeds = s.feeds - id, histories = s.histories - id, hermesSessions = s.hermesSessions - id,
            mode = if (s.mode == id) Modes.MAIN else s.mode)
    }

    /** Карточка «Стереть разговор «…»? — Стереть / Отмена» в ленте режима (меню, долгое нажатие, голос). */
    fun askRestart(modeId: String = _state.value.mode) {
        val m = _state.value.modes.firstOrNull { it.id == modeId } ?: return
        push(modeId, FeedItem.Card(nextId(), tr(R.string.ui_restart), tr(R.string.restart_title, m.title), tr(R.string.restart_text),
            listOf(tr(R.string.restart_yes) to "restart", tr(R.string.ui_cancel) to "dismiss"), phone = true))
    }

    /** «Начать заново»: стереть разговор режима с телефона; у Hermes — и забыть сессию (следующая реплика начнёт новую). */
    fun restart(modeId: String = _state.value.mode) {
        jobs[modeId]?.cancel()
        _state.value.saved.hermesRuns[modeId]?.let { r -> hermes()?.let { h -> scope.launch { h.stop(r.id) } } }
        edit { it.copy(feeds = it.feeds - modeId, histories = it.histories - modeId, hermesSessions = it.hermesSessions - modeId, hermesRuns = it.hermesRuns - modeId) }
    }

    /** Сменить мозг режима: разговор начинается заново — историю между мозгами не переносим (Архитектура 2.2). */
    fun setBrain(modeId: String, brainId: String) {
        if (_state.value.modes.firstOrNull { it.id == modeId }?.brain == brainId) return
        editMode(modeId) { it.copy(brain = brainId) }
        restart(modeId)
    }

    /** Сессия режима в Hermes — id из заголовка; после 30 минут тишины — новая (Архитектура 9.7). */
    fun hermesSession(modeId: String): String? {
        val s = _state.value.saved.hermesSessions[modeId] ?: return null
        return if (System.currentTimeMillis() - s.at > HERMES_IDLE_MS) null else s.id
    }
    fun setHermesSession(modeId: String, id: String) = edit { it.copy(hermesSessions = it.hermesSessions + (modeId to HermesSession(id, System.currentTimeMillis()))) }
    fun clearHermesSessions() = edit { it.copy(hermesSessions = emptyMap()) }

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
        push(mode.id, FeedItem.Me(meId, "", via, paths, status = tr(R.string.conv_preparing)))
        scope.launch {
            kotlinx.coroutines.delay(2000)
            while (pendingAttach === p) {
                val draft = _state.value.drafts[p.modeId].orEmpty()
                if (draft.isBlank()) { commit(p, ""); break }
                replace(p.modeId, p.meId) { (it as FeedItem.Me).copy(status = tr(R.string.conv_awaits_question)) }
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
        val note = if (n > 1) tr(R.string.conv_frames_prefix, n) else ""
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
        // «Начни заново» голосом или текстом — подтверждение, мозгу не уходит.
        if (Rules.isRestart(clean)) { askRestart(mode.id); return }
        if (mode.tools) {
            // Составная фраза («будильник на 7 и таймер на минуту») — мозгу: он разложит на шаги.
            // Без сети — по частям локально; что не понято, честно сказать (Бэклог №19).
            if (Rules.isCompound(clean)) { if (!online() && localParts(mode, clean)) return }
            else if (localRule(mode, clean)) return
        }
        toBrain(mode, clean, emptyList())
    }

    private fun toBrain(mode: Mode, clean: String, images: List<String>) {
        val userMsg = ChatMsg("user", clean.ifEmpty { null }, images = images)
        appendHistory(mode.id, userMsg)
        val history = _state.value.saved.histories[mode.id].orEmpty()
        // Hermes ещё отвечает — реплика вливается в идущий ход (как в Telegram, Архитектура 9.4); не вышло — новая задача.
        val active = _state.value.saved.hermesRuns[mode.id]
        val h = hermes()
        if (h != null && mode.brain == Brains.hermes.id && active != null && jobs[mode.id]?.isActive == true && images.isEmpty()) {
            scope.launch { if (!h.steer(active.id, clean)) startTurn(mode, clean, history) }
            return
        }
        startTurn(mode, clean, history)
    }

    private fun startTurn(mode: Mode, phrase: String, history: List<ChatMsg>) {
        val old = _state.value.saved.hermesRuns[mode.id]
        jobs[mode.id]?.cancel()
        if (old != null) { hermes()?.let { h -> scope.launch { h.stop(old.id) } }; edit { it.copy(hermesRuns = it.hermesRuns - mode.id) } }
        run(mode, phrase, brainFor(mode).send(Turn(mode, history), toolHost(mode.id, phrase)))
    }

    /** Приложение запустили, а ходы Hermes не дочитаны (закрывали посреди ответа): дочитать с места, где остановились. */
    fun resumePending() {
        val h = hermes() ?: return
        for ((modeId, run) in _state.value.saved.hermesRuns) {
            if (jobs[modeId]?.isActive == true) continue
            val mode = _state.value.modes.firstOrNull { it.id == modeId } ?: continue
            run(mode, "", h.resume(modeId, run))
        }
    }

    private fun toolHost(modeId: String, phrase: String): PhoneTools {
        val who = Brains.byId(_state.value.modes.firstOrNull { it.id == modeId }?.brain ?: "deepseek").title
        return PhoneTools(
            context, settings = { _state.value.settings },
            confirm = { title, detail -> confirm(modeId, title, detail) },
            report = { a -> push(modeId, a.copy(id = nextId())) },
            journal = { tool, params, title, status -> journal(tool, params, title, status, who, phrase) },
            notes = { _state.value.saved.notes },
            saveNote = { n -> edit { it.copy(notes = (it.notes + n).takeLast(500)) } },
            deleteNote = ::deleteNote,
            timers = { _state.value.saved.feeds.values.flatten().filterIsInstance<FeedItem.Act>().filter { it.countdown && it.state == "done" }.mapNotNull { it.at } },
        )
    }

    /** Ход мозга: события потока → лента режима. Один на режим; у Hermes ход переживает закрытие приложения ([resumePending]). */
    private fun run(mode: Mode, phrase: String, events: kotlinx.coroutines.flow.Flow<BrainEvent>) {
        val modeId = mode.id
        jobs[modeId] = scope.launch {
            _state.update { it.copy(busyModes = it.busyModes + modeId) }
            var answerId: Long? = null
            var lastAi: Long? = null
            var stepsId: Long? = null
            var askId: Long? = null
            var filesId: Long? = null
            var ended = false
            val answer = StringBuilder()
            val who = Brains.byId(mode.brain).title
            try {
                events.collect { e ->
                    when (e) {
                        is BrainEvent.Delta -> {
                            answer.append(e.text)
                            val id = answerId
                            if (id == null) { answerId = nextId().also { lastAi = it; push(modeId, FeedItem.Ai(it, e.text, done = false)) } }
                            else replace(modeId, id) { (it as FeedItem.Ai).copy(text = it.text + e.text) }
                        }
                        is BrainEvent.Step -> {
                            val id = stepsId
                            if (id == null) stepsId = nextId().also { push(modeId, FeedItem.Steps(it, listOf(e.title), running = true)) }
                            else replace(modeId, id) { (it as FeedItem.Steps).copy(titles = it.titles + e.title) }
                            answerId = null // следующий текст — новым сообщением после шагов
                        }
                        is BrainEvent.History -> appendHistory(modeId, e.msg)
                        // Hermes просит подтвердить команду на сервере: карточка с его вариантами; ответ уходит на сервер, ход ждёт.
                        is BrainEvent.Ask -> {
                            askId?.let { remove(modeId, it) }
                            val id = nextId()
                            askId = id
                            asks[id] = e.respond
                            push(modeId, FeedItem.Card(id, tr(R.string.conv_asks, who), e.title, e.text, e.choices.map { (label, key) -> label to "ask:$key" }, phone = false))
                            answerId = null
                        }
                        // Файлы одного хода — одним элементом: картинки сеткой, документы карточками (после текста).
                        is BrainEvent.Attachment -> {
                            val id = filesId ?: nextId().also { filesId = it; push(modeId, FeedItem.Files(it)) }
                            replace(modeId, id) { (it as FeedItem.Files).let { f ->
                                if (e.image && e.path != null) f.copy(images = f.images + e.path) else f.copy(files = f.files + FileRef(e.name, e.path, e.note))
                            } }
                            answerId = null
                        }
                        // Задача на сервере: запоминаем id и место, чтобы дочитать после обрыва или перезапуска.
                        is BrainEvent.Run -> edit { st -> st.copy(hermesRuns = st.hermesRuns + (modeId to HermesRun(e.id, e.seq, st.hermesRuns[modeId]?.takeIf { it.id == e.id }?.at ?: System.currentTimeMillis()))) }
                        BrainEvent.MessageEnd -> ended = true
                        is BrainEvent.Error -> {
                            ended = true
                            if (e.dropAnswer) lastAi?.let { remove(modeId, it); answerId = null }
                            push(modeId, when (e.kind) {
                                ErrorKind.NO_BRAIN -> FeedItem.Card(nextId(), tr(R.string.conv_need_brain), tr(R.string.conv_up_no_brain), tr(R.string.conv_up_no_brain_p),
                                    listOf(tr(R.string.ui_connect) to "brain"), phone = false)
                                ErrorKind.CONTEXT -> FeedItem.Card(nextId(), tr(R.string.conv_error_head, Brains.byId(mode.brain).title), tr(R.string.conv_too_long_h),
                                    tr(R.string.conv_too_long_p), listOf(tr(R.string.ui_restart) to "restart"), phone = false)
                                // Не узнали телефон или ключ — это «нужна настройка», а не сбой: карточка с кнопкой, как без мозга.
                                ErrorKind.AUTH -> FeedItem.Card(nextId(), tr(R.string.conv_error_head, who), tr(R.string.conv_need_brain), e.text,
                                    listOf(tr(R.string.ui_connect) to "brain"), phone = false)
                                else -> FeedItem.Err(nextId(), e.text)
                            })
                        }
                    }
                }
                ended = true // поток кончился сам — ход завершён
            } finally {
                askId?.let { id -> asks.remove(id); remove(modeId, id) }
                stepsId?.let { id -> replace(modeId, id) { (it as FeedItem.Steps).copy(running = false) } }
                edit { st -> st.copy(feeds = st.feeds + (modeId to st.feeds[modeId].orEmpty().map { if (it is FeedItem.Ai && !it.done) it.copy(done = true, text = it.text.trimEnd()) else it }),
                    hermesRuns = if (ended) st.hermesRuns - modeId else st.hermesRuns) }
                _state.update { it.copy(busyModes = it.busyModes - modeId) }
                if (answer.isNotBlank()) onAnswer(mode, answer.toString())
            }
        }
    }

    /** Точные короткие команды — без мозга и без сети (Архитектура 5). */
    private fun online(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Без сети: каждую часть составной фразы — локальным правилом; непонятые — одной честной строкой. */
    private fun localParts(mode: Mode, text: String): Boolean {
        val parts = Rules.parts(text)
        val missed = parts.filterNot { localRule(mode, it, fromCompound = true) }
        if (missed.size == parts.size) return false
        if (missed.isNotEmpty()) push(mode.id, FeedItem.Err(nextId(), tr(R.string.conv_offline_missed, missed.joinToString("», «"))))
        return true
    }

    private fun localRule(mode: Mode, text: String, fromCompound: Boolean = false): Boolean {
        val m = Rules.match(text) ?: return false
        val s = _state.value.settings
        val policyId = when (m) { is Rules.Match.Timer -> "timer.set"; is Rules.Match.Alarm -> "alarm.set"; is Rules.Match.Torch -> "torch.set" }
        // «Спрашивать» и «Не разрешено» — через мозг: там карточка «Да / Отмена» и честный отказ.
        if (Registry.policy(s, policyId) != Policy.AUTO) return false
        val (item, note) = when (m) {
            is Rules.Match.Timer -> PhoneActions.setTimer(context, m.seconds).fold(
                { FeedItem.Act(nextId(), "⏱", tr(R.string.conv_timer_set, m.title), tr(R.string.conv_on_phone), tr(R.string.conv_open_timers), "timers",
                    at = System.currentTimeMillis() + m.seconds * 1000L, countdown = true) to tr(R.string.conv_timer_set_long, m.title) },
                { FeedItem.Act(nextId(), "⏱", tr(R.string.conv_timer_failed, m.title), tr(R.string.conv_clock_silent), state = "fail") to null })
            is Rules.Match.Alarm -> {
                val t = "%d:%02d".format(m.hour, m.minute)
                PhoneActions.setAlarm(context, m.hour, m.minute).fold(
                    { FeedItem.Act(nextId(), "⏰", tr(R.string.conv_alarm_set, t), tr(R.string.conv_on_phone), tr(R.string.conv_open_alarms), "alarms",
                        at = Phone.nextOccurrence(m.hour, m.minute)) to tr(R.string.conv_alarm_set_long, t) },
                    { FeedItem.Act(nextId(), "⏰", tr(R.string.conv_alarm_failed, t), tr(R.string.conv_clock_silent), state = "fail") to null })
            }
            is Rules.Match.Torch -> Phone.torch(context, m.on).fold(
                { FeedItem.Act(nextId(), "🔦", if (m.on) tr(R.string.conv_torch_on) else tr(R.string.conv_torch_off), tr(R.string.conv_on_phone),
                    undo = if (m.on) tr(R.string.conv_turn_off) else null, undoAction = if (m.on) "torch:off" else null) to (if (m.on) tr(R.string.conv_torch_on_long) else tr(R.string.conv_torch_off_long)) },
                { FeedItem.Act(nextId(), "🔦", tr(R.string.conv_torch_failed), tr(R.string.conv_torch_failed_why), state = "fail") to null })
        }
        push(mode.id, item)
        journal(policyId, text, (item as FeedItem.Act).title, if (note != null) tr(R.string.conv_done) else tr(R.string.conv_not_done), tr(R.string.conv_rule_head), text)
        // Мозг узнаёт о локальном действии из истории — «а перенеси его на 8» сработает.
        if (note != null) { appendHistory(mode.id, ChatMsg("user", text)); appendHistory(mode.id, ChatMsg("assistant", note)) }
        return true
    }

    private fun deleteNote(id: Long) = edit { it.copy(notes = it.notes.filterNot { n -> n.id == id }) }

    private fun journal(tool: String, params: String, title: String, status: String, who: String, phrase: String) {
        val locked = context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        edit { it.copy(journal = (it.journal + JournalEntry(nextId(), System.currentTimeMillis(), title, who, phrase, tool, params, status, locked)).takeLast(300)) }
    }

    /** Карточка «Поставить / Отмена» и ожидание ответа. */
    private suspend fun confirm(modeId: String, title: String, detail: String): Boolean {
        val id = nextId()
        val d = CompletableDeferred<Boolean>()
        pending[id] = d
        push(modeId, FeedItem.Card(id, tr(R.string.conv_asks, Brains.byId(_state.value.current.brain).title), title, detail, listOf(tr(R.string.ui_yes) to "yes", tr(R.string.ui_cancel) to "no")))
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
            "brain" -> Sheet.openBrain(context)
            else -> if (action.startsWith("ask:")) {
                val respond = asks.remove(card.id)
                val modeId = _state.value.saved.feeds.entries.firstOrNull { e -> e.value.any { it.id == card.id } }?.key ?: _state.value.mode
                remove(modeId, card.id)
                if (respond != null) scope.launch { respond(action.removePrefix("ask:")) }
            } else remove(_state.value.mode, card.id)
        }
    }

    /** Кнопки строки действия: «Открыть …» ([FeedItem.Act.action]) и «Отменить / Выключить / Вернуть» ([FeedItem.Act.undoAction]). */
    fun onActButton(item: FeedItem.Act, undo: Boolean = false) {
        val a = (if (undo) item.undoAction else item.action) ?: return
        val P = Phone
        val parts = a.split(":")
        val ok: Boolean = when (parts[0]) {
            "timers" -> Launch.activity(context, android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS)).isSuccess
            "alarms" -> Launch.activity(context, android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)).isSuccess
            "perm" -> { Launch.askPermissions(context, a.removePrefix("perm:").split(",")); true }
            "dndaccess" -> P.openDndAccess(context).isSuccess
            "map" -> P.openMap(context, parts[1].toDouble(), parts[2].toDouble(), parts.drop(3).joinToString(":")).isSuccess
            "cal" -> if (parts[1] == "open") P.openEvent(context, parts[2].toLong()).isSuccess else P.deleteEvent(context, parts[2].toLong()).isSuccess
            "torch" -> P.torch(context, false).isSuccess
            "vol" -> P.volumeRaw(context, parts[1], parts[2].toInt()).isSuccess
            "dnd" -> P.dndRestore(context, parts[1].toInt()).isSuccess
            "alarm" -> PhoneActions.setAlarm(context, parts[2].toInt(), parts[3].toInt()).isSuccess
            "media" -> P.media(context, parts[1]).isSuccess
            "note" -> { deleteNote(parts[2].toLong()); true }
            else -> false
        }
        // Отменили — строка гаснет и говорит, что сделано; модель узнаёт из истории режима.
        if (undo && ok) {
            val modeId = _state.value.saved.feeds.entries.firstOrNull { e -> e.value.any { it.id == item.id } }?.key ?: return
            replace(modeId, item.id) { (it as FeedItem.Act).copy(state = "undone", subtitle = tr(R.string.conv_cancelled), undo = null, undoAction = null, countdown = false, at = null) }
            appendHistory(modeId, ChatMsg("user", tr(R.string.conv_cancelled_by_user, item.title)))
            appendHistory(modeId, ChatMsg("assistant", tr(R.string.conv_cancelled_long, item.title)))
        }
    }
}
