package ru.poneslas.vahter.core

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import ru.poneslas.vahter.App
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.AlarmClock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 30 минут без дела: новый разговор режима, возврат к Ассистенту, а у Hermes — новая сессия (Архитектура 9.7). */
private const val IDLE = 30 * 60_000L

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

    /** id элементов ленты: зовут и с главного потока, и из действий мозга (IO) — повтор id роняет ленту («Key was already used»). */
    private val seq = AtomicLong(System.currentTimeMillis())
    private fun nextId() = seq.incrementAndGet()
    /** Идущий ход каждого режима. */
    private val jobs = mutableMapOf<String, Job>()
    /** Карточки «Да / Отмена» действий телефона: заводятся из IO (PhoneTools), отвечаются с главного потока. */
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    /** Открытые просьбы Hermes подтвердить команду (id карточки → ответ серверу). */
    private val asks = ConcurrentHashMap<Long, suspend (String) -> Boolean>()

    init {
        // пустые «Новые режимы», оставшиеся с прошлого раза (приложение закрыли прямо на странице режима)
        _state.value.modes.filter { blank(it) }.forEach { deleteMode(it.id) }
        _state.drop(1).debounce(400).onEach { s -> withContext(Dispatchers.IO) { runCatching { store.save(s.saved) }.onFailure { Log.e("Conversation", "не сохранилось", it) } } }.launchIn(scope)
    }

    /** Без дела дольше — новый разговор режима и (по настройке) возврат к Ассистенту. */
    val IDLE_MS = IDLE

    private fun edit(f: (Saved) -> Saved) = _state.update { it.copy(saved = f(it.saved)) }

    fun selectMode(id: String) { freshen(); edit { it.copy(mode = id, lastUse = System.currentTimeMillis()) } }
    fun setDraft(text: String) = _state.update { it.copy(drafts = it.drafts + (it.mode to text)) }
    fun updateSettings(f: (Settings) -> Settings) = edit { it.copy(settings = f(it.settings)) }
    fun updateModes(f: (List<Mode>) -> List<Mode>) = edit { it.copy(modes = f(it.modes)) }
    fun touch() = edit { it.copy(lastUse = System.currentTimeMillis()) }
    /** Нажали «Ответ готов»: открыть режим с ответом. Ответ мог ждать дольше 30 минут — он не должен уйти под «Прошлый разговор». */
    fun openAnswer(id: String) { edit { it.copy(modeUse = it.modeUse + (id to System.currentTimeMillis())) }; selectMode(id) }
    /** В режиме что-то сказали или прислали — от этого считается «30 минут без дела» для его истории (просмотр не считается). */
    private fun spoke(modeId: String) { freshen(); edit { it.copy(lastUse = System.currentTimeMillis(), modeUse = it.modeUse + (modeId to System.currentTimeMillis())) } }

    /**
     * «30 минут без дела» — чистый лист (Продукт 5.17): у каждого режима, где с последнего использования прошло больше 30 минут,
     * старое уходит под «Прошлый разговор». Зовётся при каждом показе листа и приложения и при выборе режима — не только
     * при вызове кнопкой (раньше проверка жила в onShow сессии и открытие из уведомления или приложения её пропускало).
     */
    fun freshen(now: Long = System.currentTimeMillis()) = edit { s ->
        val upd = s.feeds.mapNotNull { (id, feed) ->
            val last = s.modeUse[id] ?: 0L   // до 0.9.4 отметок не было — старая переписка один раз уходит под «Прошлый разговор»
            if (now - last > IDLE_MS && feed.size > (s.fresh[id] ?: 0)) id to feed.size else null
        }
        if (upd.isEmpty()) s else s.copy(fresh = s.fresh + upd)
    }

    private fun push(modeId: String, item: FeedItem) = edit { it.copy(feeds = it.feeds + (modeId to (it.feeds[modeId].orEmpty() + item))) }
    private fun replace(modeId: String, id: Long, f: (FeedItem) -> FeedItem) = edit { s ->
        s.copy(feeds = s.feeds + (modeId to s.feeds[modeId].orEmpty().map { if (it.id == id) f(it) else it }))
    }
    private fun remove(modeId: String, id: Long) = edit { s -> s.copy(feeds = s.feeds + (modeId to s.feeds[modeId].orEmpty().filterNot { it.id == id })) }
    private fun appendHistory(modeId: String, msg: ChatMsg) = edit { it.copy(histories = it.histories + (modeId to (it.histories[modeId].orEmpty() + msg))) }

    /** «+»: новый режим. id выдаётся один раз и не меняется при переименовании (для Hermes это ключ сессии).
     *  select — «+» во вкладках: новый режим сразу выбран; если его бросят пустым, вернётся прежний. */
    fun addMode(select: Boolean = false): String {
        val id = "m-" + UUID.randomUUID().toString().take(4)
        updateModes { it + freshMode(id) }
        if (select) { beforeAdd = _state.value.mode; selectMode(id) }
        return id
    }
    private fun freshMode(id: String) = Mode(id = id, title = tr(R.string.feed_new_mode), icon = "star", placeholder = tr(R.string.mode_assistant_hint))
    private var beforeAdd: String? = null
    /** Новый режим так и не заполнили: всё как при создании (название пустое или «Новый режим»), разговора нет. */
    private fun blank(m: Mode) = !m.builtin && m.copy(title = m.title.ifBlank { tr(R.string.feed_new_mode) }) == freshMode(m.id) &&
        _state.value.saved.feeds[m.id].isNullOrEmpty()

    /** Ушли со страницы режима: пустой новый режим не нужен — удалить; был выбран — вернуть прежний. */
    fun dropIfBlank(id: String) {
        val m = _state.value.modes.firstOrNull { it.id == id } ?: return
        if (!blank(m)) return
        val back = beforeAdd?.takeIf { b -> _state.value.mode == id && _state.value.modes.any { it.id == b } }
        deleteMode(id)
        if (back != null) edit { it.copy(mode = back) }
        beforeAdd = null
    }

    fun editMode(id: String, f: (Mode) -> Mode) = updateModes { list -> list.map { if (it.id == id) f(it) else it } }

    /** Перестановка режимов (настройки, долгое нажатие): порядок — как во вкладках под полем; Ассистент — наравне. */
    fun moveMode(from: Int, to: Int) = edit { s ->
        val l = s.modes.toMutableList(); if (from !in l.indices || to !in l.indices) return@edit s
        l.add(to, l.removeAt(from)); s.copy(modes = l, settings = s.settings.copy(modesReordered = true))
    }

    fun deleteMode(id: String) = edit { s ->
        s.copy(modes = s.modes.filterNot { it.id == id }, feeds = s.feeds - id, histories = s.histories - id, hermesSessions = s.hermesSessions - id, fresh = s.fresh - id,
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
        edit { it.copy(feeds = it.feeds - modeId, histories = it.histories - modeId, hermesSessions = it.hermesSessions - modeId, hermesRuns = it.hermesRuns - modeId, fresh = it.fresh - modeId) }
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
        return if (System.currentTimeMillis() - s.at > IDLE) null else s.id
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
        spoke(mode.id)
        push(mode.id, FeedItem.Me(meId, "", via, paths, status = tr(R.string.conv_preparing)))
        scope.launch {
            delay(2000)
            while (pendingAttach === p) {
                val draft = _state.value.drafts[p.modeId].orEmpty()
                if (draft.isBlank()) { commit(p, ""); break }
                replace(p.modeId, p.meId) { (it as FeedItem.Me).copy(status = tr(R.string.conv_awaits_question)) }
                delay(300)
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
        spoke(mode.id)
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

    /**
     * Файл для Hermes (скрепка → «Файл»): в ленте — «📎 имя», файл уходит на сервер (HermesBrain.upload), в реплике мозгу — путь
     * и вопрос из поля, если был. DeepSeek файлы не читает — пункта у него нет.
     */
    fun sendFile(local: File, name: String) {
        val mode = _state.value.current
        val link = App.instance.hermesLink() ?: return
        val q = _state.value.draft.trim()
        _state.update { it.copy(drafts = it.drafts + (mode.id to "")) }
        spoke(mode.id)
        val meId = nextId()
        push(mode.id, FeedItem.Me(meId, listOf("📎 $name", q).filter { it.isNotBlank() }.joinToString("\n"), tr(R.string.conv_file_sending)))
        scope.launch {
            val r = withContext(Dispatchers.IO) { App.instance.hermesBrain.upload(link, local, name) }
            local.delete()
            r.fold({ path ->
                replace(mode.id, meId) { (it as FeedItem.Me).copy(via = tr(R.string.conv_file_via)) }
                toBrain(mode, tr(R.string.conv_file_for_hermes, name, path) + if (q.isNotBlank()) "\n\n$q" else "", emptyList())
            }, { e -> replace(mode.id, meId) { (it as FeedItem.Me).copy(via = tr(R.string.conv_file_failed, e.message ?: "")) } })
        }
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
        return PhoneTools(
            context, settings = { _state.value.settings },
            confirm = { title, detail -> confirm(modeId, title, detail) },
            report = { a -> push(modeId, a.copy(id = nextId())) },
            notes = { _state.value.saved.notes },
            saveNote = { n -> edit { it.copy(notes = (it.notes + n).takeLast(500)) } },
            deleteNote = ::deleteNote,
            timers = { _state.value.saved.feeds.values.flatten().filterIsInstance<FeedItem.Act>().filter { it.countdown && it.state == "done" }.mapNotNull { it.at } },
        )
    }

    /** Ход мозга: события потока → лента режима. Один на режим; у Hermes ход переживает закрытие приложения ([resumePending]). */
    private fun run(mode: Mode, phrase: String, events: Flow<BrainEvent>) {
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
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                // Сбой внутри мозга (не сеть: разбор ответа, пропавшее вложение…) — красная строка в ленте, а не падение приложения.
                Log.e("Conversation", "ход мозга упал", e)
                ended = true
                push(modeId, FeedItem.Err(nextId(), tr(R.string.act_failed, e.message ?: e.javaClass.simpleName)))
            } finally {
                askId?.let { id -> asks.remove(id); remove(modeId, id) }
                stepsId?.let { id -> replace(modeId, id) { (it as FeedItem.Steps).copy(running = false) } }
                edit { st -> st.copy(feeds = st.feeds + (modeId to st.feeds[modeId].orEmpty().map { if (it is FeedItem.Ai && !it.done) it.copy(done = true, text = it.text.trimEnd()) else it }),
                    hermesRuns = if (ended) st.hermesRuns - modeId else st.hermesRuns) }
                _state.update { it.copy(busyModes = it.busyModes - modeId) }
                // Пришедший ответ — тоже дело в режиме: долгая команда Hermes не уводит его под «Прошлый разговор».
                if (answer.isNotBlank()) { edit { it.copy(modeUse = it.modeUse + (modeId to System.currentTimeMillis())) }; onAnswer(mode, answer.toString()) }
            }
        }
    }

    /** Точные короткие команды — без мозга и без сети (Архитектура 5). */
    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
        // Мозг узнаёт о локальном действии из истории — «а перенеси его на 8» сработает.
        if (note != null) { appendHistory(mode.id, ChatMsg("user", text)); appendHistory(mode.id, ChatMsg("assistant", note)) }
        return true
    }

    private fun deleteNote(id: Long) = edit { it.copy(notes = it.notes.filterNot { n -> n.id == id }) }

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
                if (respond != null) scope.launch {
                    // Ответ не дошёл до сервера — карточка обратно (можно нажать ещё раз) и честная строка, а не тишина до таймаута.
                    if (!respond(action.removePrefix("ask:")) && jobs[modeId]?.isActive == true) {
                        asks[card.id] = respond; push(modeId, card); push(modeId, FeedItem.Err(nextId(), tr(R.string.hermes_ask_failed)))
                    }
                }
            } else remove(_state.value.mode, card.id)
        }
    }

    /** Кнопки строки действия: «Открыть …» ([FeedItem.Act.action]) и «Отменить / Выключить / Вернуть» ([FeedItem.Act.undoAction]). */
    private fun replaceAct(item: FeedItem.Act, f: (FeedItem.Act) -> FeedItem.Act) {
        val modeId = _state.value.saved.feeds.entries.firstOrNull { e -> e.value.any { it.id == item.id } }?.key ?: return
        replace(modeId, item.id) { f(it as FeedItem.Act) }
    }

    /** Фонарик и «Не беспокоить» из карточки — туда-обратно; надпись карточки отражает, что сейчас. */
    private fun toggleAct(item: FeedItem.Act) {
        if (item.icon == "🔦") {
            val on = item.title != tr(R.string.conv_torch_on)
            if (Phone.torch(context, on).isSuccess) replaceAct(item) { it.copy(title = tr(if (on) R.string.conv_torch_on else R.string.conv_torch_off), undo = null, undoAction = null) }
        } else {
            val on = item.title != tr(R.string.act_dnd_on)
            if (Phone.dnd(context, on).isSuccess) replaceAct(item) { it.copy(title = tr(if (on) R.string.act_dnd_on else R.string.act_dnd_off), undo = null, undoAction = null) }
        }
    }

    fun onActButton(item: FeedItem.Act, undo: Boolean = false) {
        val a = (if (undo) item.undoAction else item.action) ?: return
        val P = Phone
        val parts = a.split(":")
        val ok: Boolean = when (parts[0]) {
            "timers" -> Launch.activity(context, Intent(AlarmClock.ACTION_SHOW_TIMERS)).isSuccess
            // карточка-переключатель (фонарик, «Не беспокоить»): живое состояние — в самой карточке
            "toggle" -> { toggleAct(item); return }
            "timerstop" -> { if (P.dismissTimer(context).isSuccess) replaceAct(item) { it.copy(title = tr(R.string.act_timer_off_done), subtitle = "", at = null, countdown = false, action = null) }; return }
            "alarms" -> Launch.activity(context, Intent(AlarmClock.ACTION_SHOW_ALARMS)).isSuccess
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
