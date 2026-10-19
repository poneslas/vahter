package ru.poneslas.tasya.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.poneslas.tasya.core.Conversation
import ru.poneslas.tasya.core.FeedItem
import ru.poneslas.tasya.voice.Recognizer
import kotlin.math.abs
import kotlin.math.hypot

/** Лента разговора текущего режима. Пустой режим — подсказка из поля режима (hint). */
@Composable
fun Feed(state: Conversation.State, onAct: (FeedItem.Act) -> Unit, onCard: (FeedItem.Card, String) -> Unit, onCancelAttach: (Long) -> Unit = {}, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(state.feed.size, (state.feed.lastOrNull() as? FeedItem.Ai)?.text?.length) {
        if (state.feed.isNotEmpty()) list.animateScrollToItem(state.feed.size - 1)
    }
    if (state.feed.isEmpty()) {
        Box(modifier.padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.BottomStart) {
            Text(state.current.hint, color = Ink.soft, fontSize = 15.sp, lineHeight = 21.sp)
        }
        return
    }
    LazyColumn(
        modifier, state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.feed, key = { it.id }) { item ->
            when (item) {
                is FeedItem.Me -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                  Column(horizontalAlignment = Alignment.End) {
                    if (item.images.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            item.images.take(4).forEach { path ->
                                val bmp = remember(path) { ru.poneslas.tasya.media.Attachments.thumb(path, 300) }
                                if (bmp != null) androidx.compose.foundation.Image(bmp.asImageBitmap(), null,
                                    Modifier.size(width = if (item.images.size > 1) 76.dp else 150.dp, height = if (item.images.size > 1) 110.dp else 200.dp).clip(RoundedCornerShape(12.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.status?.let { "$it · " }.orEmpty() + item.via, fontSize = 11.sp, color = Ink.faint)
                            if (item.status != null) Text("  ✕", fontSize = 14.sp, color = Ink.soft, modifier = Modifier.clickable { onCancelAttach(item.id) }.padding(4.dp))
                        }
                    }
                    if (item.text.isNotEmpty() || item.images.isEmpty()) Column(
                        Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                            .background(Ink.me).padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(item.text, fontSize = 15.sp, color = Ink.ink)
                        Text(item.via, fontSize = 11.sp, color = Ink.faint, modifier = Modifier.align(Alignment.End))
                    }
                  }
                }
                is FeedItem.Ai -> Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 8.dp, end = 8.dp).size(7.dp).clip(CircleShape).background(Ink.server))
                    Text(item.text + if (item.done) "" else " ▍", fontSize = 15.sp, lineHeight = 21.sp, color = Ink.ink)
                }
                is FeedItem.Act -> Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surf)
                        .border(1.dp, Ink.line, RoundedCornerShape(14.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Ink.localT), contentAlignment = Alignment.Center) {
                        Text(item.icon, fontSize = 18.sp)
                    }
                    Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = Ink.ink)
                        Text(item.subtitle, fontSize = 12.sp, color = Ink.soft)
                    }
                    item.button?.let { b ->
                        Text(b, color = Color(0xFF9A6A00), fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                            modifier = Modifier.clickable { onAct(item) }.padding(6.dp))
                    }
                }
                is FeedItem.Steps -> Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.serverT).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (item.running) "${item.titles.last()}…" else "${item.titles.size} ${plural(item.titles.size, "шаг", "шага", "шагов")}",
                        fontSize = 13.sp, color = Color(0xFF2238B8), fontWeight = FontWeight.Medium)
                }
                is FeedItem.Card -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(16.dp)),
                ) {
                    Text(item.head, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (item.phone) Color(0xFF9A6A00) else Color(0xFF2238B8),
                        modifier = Modifier.fillMaxWidth().background(if (item.phone) Ink.localT else Ink.serverT).padding(horizontal = 12.dp, vertical = 6.dp))
                    Column(Modifier.padding(12.dp)) {
                        Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                        if (item.text.isNotEmpty()) Text(item.text, fontSize = 14.sp, color = Ink.soft, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.buttons.forEachIndexed { i, (label, action) ->
                                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (i == 0) Color.White else Ink.ink,
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (i == 0) Ink.ink else Color.Transparent)
                                        .clickable { onCard(item, action) }.padding(horizontal = 14.dp, vertical = 9.dp))
                            }
                        }
                    }
                }
                is FeedItem.Err -> Text(
                    item.text, color = Ink.danger, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.dangerT).padding(10.dp),
                )
            }
        }
    }
}

/** Вкладки режимов под полем ввода — видны всегда, последняя «+» (Продукт 5.17). */
private fun plural(n: Int, one: String, few: String, many: String) =
    if (n % 10 == 1 && n % 100 != 11) one else if (n % 10 in 2..4 && n % 100 !in 12..14) few else many

@Composable
fun ModeTabs(state: Conversation.State, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        items(state.tabs, key = { it.id }) { m ->
            val on = m.id == state.mode
            Text(
                m.title, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = if (on) Color.White else Ink.soft,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Ink.ink else Color.Transparent)
                    .clickable { onSelect(m.id) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        item {
            Box(
                Modifier.size(30.dp).clip(CircleShape).border(1.dp, Ink.line, CircleShape).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Text("+", fontSize = 17.sp, color = Ink.soft) }
        }
    }
}

/** Запись push-to-talk: зажал — пишет, отпустил — ушло; вверх — замочек; влево — отмена (Продукт 5.2). */
enum class RecMode { HOLD, LOCKED }

/**
 * Строка ввода: поле, большая кнопка (микрофон / «Отправить», если в поле есть текст) и вкладки режимов.
 * Жесты — как в прототипе: направление выбирается по первым 8 dp и держится до отпускания.
 */
@Composable
fun Composer(
    state: Conversation.State,
    recognizer: Recognizer,
    onDraft: (String) -> Unit,
    onSend: (String, String) -> Unit,
    onSelectMode: (String) -> Unit,
    onAddMode: () -> Unit,
    onLive: (String?) -> Unit,
    autoListen: kotlinx.coroutines.flow.StateFlow<Int>? = null,
    onNeedMic: (() -> Unit)? = null,
    onCamera: (() -> Unit)? = null,
    onGallery: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val photoMain = state.current.primary == ru.poneslas.tasya.core.Primary.PHOTO && state.current.accepts.photo && onCamera != null
    val scope = rememberCoroutineScope()
    var rec by remember { mutableStateOf<RecMode?>(null) }
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    var t0 by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val lockPx = with(density) { 45.dp.toPx() }
    val cancelPx = with(density) { 70.dp.toPx() }
    val axisPx = with(density) { 8.dp.toPx() }
    val partial by recognizer.partial.collectAsState()
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(rec) { while (rec != null) { now = System.currentTimeMillis(); delay(250) } }

    fun finish(how: String) {
        val mode = rec ?: return
        rec = null; dx = 0f; dy = 0f
        if (how == "cancel") { recognizer.cancel(); onLive(null); return }
        scope.launch {
            val text = recognizer.stop()
            if (text.isBlank()) {
                onLive(if (recognizer.available) "Ничего не расслышал" else "Распознавание речи появится в следующей сборке — пока пишите")
                delay(2500); onLive(null); return@launch
            }
            onLive(null)
            val full = (state.draft + " " + text).trim()
            onSend(full, "голос")
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    fun micOk(): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (onNeedMic != null) onNeedMic() else onLive("Нужен доступ к микрофону|Разрешите в настройках приложения")
            return false
        }
        if (!recognizer.available) { onLive("Распознавание ещё не готово|Настройки → Голос: скачать модель"); scope.launch { delay(3000); onLive(null) }; return false }
        return true
    }
    val micOn by recognizer.recording.collectAsState()
    LaunchedEffect(micOn) { if (!micOn && rec != null) { rec = null; dx = 0f; dy = 0f; onLive(null) } }
    // Переключение режима отменяет запись — ничего не уходит (Продукт 5.17).
    LaunchedEffect(state.mode) { if (rec != null) finish("cancel") }
    // Вызов кнопкой: голосовой режим сразу пишет без рук (замочек).
    val auto = autoListen?.collectAsState()?.value ?: 0
    LaunchedEffect(auto) {
        if (auto > 0 && rec == null && state.draft.isEmpty() &&
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED && recognizer.available) {
            rec = RecMode.LOCKED; t0 = System.currentTimeMillis(); recognizer.start()
        }
    }

    LaunchedEffect(rec, partial) {
        onLive(when (rec) {
            RecMode.HOLD -> "Запись · отпустите — отправится|" + partial.ifEmpty { "Слушаю…" }
            RecMode.LOCKED -> "Запись без рук · кнопка справа — отправить|" + partial.ifEmpty { "Слушаю…" }
            null -> null
        })
    }

    Column(Modifier.background(Ink.paper)) {
        if (menu) Column(Modifier.padding(start = 12.dp).clip(RoundedCornerShape(16.dp)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(16.dp)).padding(6.dp)) {
            if (onCamera != null && !photoMain) Text("📷  Камера", fontSize = 15.sp, color = Ink.ink, modifier = Modifier.clickable { menu = false; onCamera() }.padding(horizontal = 12.dp, vertical = 10.dp))
            if (onGallery != null) Text("🖼  Галерея", fontSize = 15.sp, color = Ink.ink, modifier = Modifier.clickable { menu = false; onGallery() }.padding(horizontal = 12.dp, vertical = 10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.surf)
                    .border(1.dp, Ink.line, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (rec != null) {
                    val sec = ((now - t0) / 1000).coerceAtLeast(0).toInt()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Ink.danger))
                        Text("  0:%02d".format(sec), fontSize = 14.sp, color = Ink.ink)
                        Spacer(Modifier.weight(1f))
                        Text("‹ влево — отмена", fontSize = 13.sp, color = Ink.faint.copy(alpha = (1f - dx / cancelPx).coerceIn(.25f, 1f)),
                            modifier = Modifier.clickable { finish("cancel") })
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    // Слева в поле — только скрепка; справа — микрофон фото-режима (Продукт 5.17).
                    val canAttach = state.current.accepts.photo && (onGallery != null || (onCamera != null && !photoMain))
                    if (canAttach) Text("📎", fontSize = 18.sp, modifier = Modifier.clickable { menu = !menu }.padding(end = 10.dp))
                    Box(Modifier.weight(1f)) {
                        if (state.draft.isEmpty()) Text(state.current.placeholder, color = Ink.faint, fontSize = 15.sp)
                        BasicTextField(
                            value = state.draft, onValueChange = onDraft,
                            textStyle = TextStyle(fontSize = 15.sp, color = Ink.ink),
                            cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (photoMain && state.draft.isEmpty() && state.current.accepts.voice)
                        Text("🎙", fontSize = 18.sp, modifier = Modifier.clickable {
                            if (micOk()) { rec = RecMode.LOCKED; t0 = System.currentTimeMillis(); recognizer.start() }
                        }.padding(start = 10.dp))
                }
            }
            Spacer(Modifier.size(8.dp))
            val hasText = state.draft.isNotBlank() && rec == null
            val scale by animateFloatAsState(if (rec == RecMode.HOLD) 1.15f else 1f, label = "mic")
            Box(
                Modifier
                    .offset { IntOffset(-dx.toInt().coerceAtMost(cancelPx.toInt()), -dy.toInt().coerceAtMost(lockPx.toInt())) }
                    .size((48 * scale).dp).clip(CircleShape)
                    .background(if (rec == RecMode.HOLD) Ink.local else Ink.ink)
                    .pointerInput(hasText, rec, photoMain) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (!hasText && rec == null && photoMain) {
                                // Фото-режим: большая кнопка — камера.
                                if (waitForUpOrCancellation() != null) onCamera?.invoke()
                                return@awaitEachGesture
                            }
                            if (hasText) {
                                val up = waitForUpOrCancellation()
                                if (up != null) onSend(state.draft, "клавиатура")
                                return@awaitEachGesture
                            }
                            val start = down.position
                            if (rec == RecMode.LOCKED) {
                                // Замочек: круг — «Отправить»; дёрнуть влево — отмена.
                                var moved = 0f
                                while (true) {
                                    val e = awaitPointerEvent(); val c = e.changes.first()
                                    moved = (start.x - c.position.x).coerceAtLeast(0f); dx = moved
                                    if (moved > cancelPx) { finish("cancel"); break }
                                    if (!c.pressed) { if (moved < 12f) finish("send") else dx = 0f; break }
                                }
                                return@awaitEachGesture
                            }
                            if (!micOk()) { waitForUpOrCancellation(); return@awaitEachGesture }
                            rec = RecMode.HOLD; t0 = System.currentTimeMillis(); recognizer.start()
                            var axis: Char? = null
                            while (true) {
                                val e = awaitPointerEvent(); val c = e.changes.first()
                                val mx = start.x - c.position.x; val my = start.y - c.position.y
                                if (axis == null && hypot(mx, my) > axisPx) axis = if (abs(my) >= abs(mx)) 'y' else 'x'
                                dx = if (axis == 'x') mx.coerceAtLeast(0f) else 0f
                                dy = if (axis == 'y') my.coerceAtLeast(0f) else 0f
                                if (axis == 'y' && my > lockPx) { rec = RecMode.LOCKED; dx = 0f; dy = 0f; break }
                                if (axis == 'x' && mx > cancelPx) { finish("cancel"); break }
                                if (!c.pressed) {
                                    if (System.currentTimeMillis() - t0 < 260) { rec = RecMode.LOCKED; dx = 0f; dy = 0f }
                                    else finish("send")
                                    break
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (hasText || rec == RecMode.LOCKED) "↑" else if (photoMain && rec == null) "📷" else "🎙", color = Color.White, fontSize = 20.sp)
            }
        }
        ModeTabs(state, onSelectMode, onAddMode)
    }
}


/** Живая строка над полем: статус записи и «караоке». Формат «статус|текст». */
@Composable
fun LiveLine(live: String?) {
    if (live == null) return
    val (status, text) = live.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(status, fontSize = 12.sp, color = Color(0xFF9A6A00), fontWeight = FontWeight.Medium)
        if (text.isNotEmpty()) Text(text, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium, color = Ink.ink)
    }
}
