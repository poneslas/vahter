package ru.poneslas.vahter.ui

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.poneslas.vahter.core.Conversation
import ru.poneslas.vahter.core.FeedItem
import ru.poneslas.vahter.core.Mode
import ru.poneslas.vahter.core.Modes
import ru.poneslas.vahter.app.SideKey
import ru.poneslas.vahter.media.Attachments
import ru.poneslas.vahter.actions.Phone
import ru.poneslas.vahter.trp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Промпт режима над лентой (ui.js, promptHtml): серая полоска, две строки, карандаш → страница режима. У Ассистента промпта нет — полоски нет. */
@Composable
fun PromptStrip(mode: Mode, onEdit: (String) -> Unit, modifier: Modifier = Modifier) {
    if (mode.prompt.isBlank()) return
    Row(modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.chipBg)
        .clickable { onEdit(mode.id) }.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Text(mode.prompt, Modifier.weight(1f), color = Ink.soft, fontSize = 13.sp, lineHeight = 1.35.em, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Icon(painterResource(Ic.edit), tr(R.string.mode_edit), Modifier.padding(top = 1.dp).size(16.dp), tint = Ink.faint)
    }
}

/** Лента разговора (ui.js, feedHtml/itemHtml): промежутки 10, поля 6/14/12, текст 14.5. Прижата к низу. */
@Composable
fun Feed(
    state: Conversation.State, onAct: (FeedItem.Act, Boolean) -> Unit, onCard: (FeedItem.Card, String) -> Unit, onCancelAttach: (Long) -> Unit = {},
    modifier: Modifier = Modifier, inSheet: Boolean = true, onChip: ((String) -> Unit)? = null,
    /** Чистый лист после 30 минут без дела (ui.js, sheetFrom): в листе — лента с этого места; в приложении — черта «Новый разговор · из листа». */
    from: Int = 0,
) {
    // Баг №20: у каждого режима своё положение ленты; при переключении — сразу в конец, без анимации.
    val list = remember(state.mode) { LazyListState((state.feed.size - 1).coerceAtLeast(0)) }
    LaunchedEffect(state.mode) { if (state.feed.isNotEmpty()) list.scrollToItem(state.feed.size - 1, 1_000_000) }
    LaunchedEffect(state.feed.size, (state.feed.lastOrNull() as? FeedItem.Ai)?.text?.length) {
        if (state.feed.isNotEmpty()) list.animateScrollToItem(state.feed.size - 1, 1_000_000)
    }
    val pad = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)
    if (state.feed.isEmpty()) {
        Column(modifier.padding(pad), verticalArrangement = Arrangement.Bottom) {
            if (inSheet || state.mode != Modes.MAIN) {
                Text(state.current.hint, color = Ink.soft, fontSize = 14.5.sp, lineHeight = 1.45.em, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
            } else {
                val ctx = LocalContext.current
                val way = tr(if (SideKey.gestureNav(ctx)) R.string.feed_way_gesture else R.string.feed_way_home)
                Text(tr(R.string.feed_empty, way), color = Ink.soft, fontSize = 14.5.sp, lineHeight = 1.45.em,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp))
                if (onChip != null) FlowRow(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(tr(R.string.feed_chip_timer), tr(R.string.feed_chip_alarm), tr(R.string.feed_chip_brain)).forEach { c ->
                        Text(c, fontSize = 13.sp, color = Ink.ink, modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.surf)
                            .border(1.dp, Ink.line, RoundedCornerShape(50)).clickable { onChip(c) }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
        return
    }
    CompositionLocalProvider(LocalTextStyle provides BaseText.copy(fontSize = 14.5.sp)) {
        LazyColumn(modifier, state = list, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
            val shown = if (inSheet) state.feed.drop(from) else state.feed
            val sep = if (!inSheet && from in 1 until state.feed.size) state.feed[from].id else null
            items(shown, key = { it.id }) { item ->
              Column(Modifier.fillMaxWidth()) {
                if (item.id == sep) Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(Ink.line))
                    Text(tr(R.string.feed_new_conv), fontSize = 12.sp, color = Ink.faint, modifier = Modifier.padding(horizontal = 10.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(Ink.line))
                }
                when (item) {
                    is FeedItem.Me -> MeItem(item, onCancelAttach)
                    is FeedItem.Ai -> AiItem(item)
                    is FeedItem.Act -> ActItem(item, onAct)
                    is FeedItem.Steps -> StepsItem(item)
                    is FeedItem.Card -> CardItem(item, onCard)
                    is FeedItem.Err -> Text(item.text, color = Ink.errText, fontSize = 13.5.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)).background(Ink.dangerT)
                            .drawBehind { drawRect(Ink.danger, size = Size(3.dp.toPx(), size.height)) }
                            .padding(start = 15.dp, end = 12.dp, top = 8.dp, bottom = 8.dp))
                }
              }
            }
        }
    }
}

/** .me — пузырь справа; .meimg — миниатюры 170×128, под ними статус и «✕», пока не ушло. */
@Composable
private fun MeItem(item: FeedItem.Me, onCancel: (Long) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val maxW = maxWidth * 0.84f
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.images.isNotEmpty()) {
                Box {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val n = item.images.size
                        item.images.take(4).forEach { path ->
                            val bmp = remember(path) { Attachments.thumb(path, 400) }
                            if (bmp != null) Image(bmp.asImageBitmap(), null,
                                Modifier.size(if (n > 1) 80.dp else 170.dp, if (n > 1) 106.dp else 128.dp).clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop)
                        }
                    }
                    if (item.status != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp).clip(CircleShape).background(Color(0x8C000000))
                        .clickable { onCancel(item.id) }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(Ic.x), tr(R.string.feed_cancel_upload), Modifier.size(16.dp), tint = Color.White)
                    }
                }
                if (item.status != null) {
                    Text(item.status, fontSize = 11.5.sp, color = Ink.soft)
                    if (item.text.isEmpty()) Text(tr(R.string.feed_add_question), fontSize = 11.5.sp, color = Ink.faint)
                } else if (item.text.isEmpty()) Text(item.via, fontSize = 10.5.sp, color = Ink.faint)
            }
            if (item.text.isNotEmpty()) Column(
                Modifier.widthIn(max = maxW).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(Ink.me).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(item.text, color = Ink.ink)
                Text(item.via, fontSize = 10.5.sp, lineHeight = 1.3.em, color = Ink.faint, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

/** .ai — точка 7 слева (синяя — мозг), текст с простой разметкой (жирный, заголовки, списки, код). */
@Composable
private fun AiItem(item: FeedItem.Ai) {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp)) {
        Box(Modifier.offset(x = (-15).dp, y = 8.dp).size(7.dp).clip(CircleShape).background(Ink.server))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Markdown(item.text) }
    }
}

/**
 * .act — итог действия на телефоне: значок на янтарном с отметкой (✓ сделано, × не вышло), заголовок с глаголом,
 * пояснение и срок («прозвенит через …», у таймера — живой отсчёт), справа «Отменить» и «Открыть …».
 */
@Composable
private fun ActItem(item: FeedItem.Act, onAct: (FeedItem.Act, Boolean) -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    if (item.at != null && item.state != "undone") LaunchedEffect(item.id, item.at) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(if (item.countdown) 1000 else 30_000) }
    }
    val sub = buildString {
        append(item.subtitle)
        item.at?.takeIf { item.state != "undone" }?.let { at ->
            val left = at - now
            append(" · ")
            append(when {
                item.countdown && left > 0 -> tr(R.string.feed_left) + (left / 1000).let { if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60) }
                item.countdown -> tr(R.string.feed_rang)
                left > 0 -> tr(R.string.feed_rings_in) + Phone.inText(left)
                else -> tr(R.string.feed_rang)
            })
        }
    }
    Row(Modifier.fillMaxWidth().alpha(if (item.state == "undone") .55f else 1f).clip(RoundedCornerShape(14.dp)).background(Ink.surf)
        .border(1.dp, Ink.line, RoundedCornerShape(14.dp)).padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(if (item.state == "fail") Ink.dangerT else Ink.localT), contentAlignment = Alignment.Center) {
                Text(item.icon, fontSize = 15.sp)
            }
            val (mark, bg) = when (item.state) { "done" -> "✓" to Ink.ok; "fail" -> "×" to Ink.danger; else -> null to null }
            if (mark != null && bg != null) Box(Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(15.dp).clip(CircleShape).background(bg)
                .border(1.5.dp, Ink.surf, CircleShape), contentAlignment = Alignment.Center) {
                Text(mark, fontSize = 9.sp, lineHeight = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 1.35.em,
                color = if (item.state == "fail") Ink.danger else Ink.ink)
            Text(sub, fontSize = 11.5.sp, color = Ink.soft, lineHeight = 1.35.em)
        }
        Column(horizontalAlignment = Alignment.End) {
            item.undo?.let { u -> Text(u, color = Ink.localD, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.clickable { onAct(item, true) }.padding(horizontal = 2.dp, vertical = 4.dp)) }
            item.button?.let { b -> Text(b, color = Ink.localD, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.clickable { onAct(item, false) }.padding(horizontal = 2.dp, vertical = 4.dp)) }
        }
    }
}

/** .steps — свёрнутая строка «N шагов», по нажатию — список; пока идёт — название текущего шага. */
@Composable
private fun StepsItem(item: FeedItem.Steps) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.serverT)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (item.running) item.titles.last() else trp(R.plurals.feed_steps, item.titles.size),
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Ink.serverD, modifier = Modifier.weight(1f))
            Icon(painterResource(Ic.chev), null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (open) 180f else 0f }, tint = Ink.serverD)
        }
        if (open) Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            item.titles.forEach { t ->
                Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink.surf).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Ink.localT), contentAlignment = Alignment.Center) { Text("📱", fontSize = 12.sp) }
                    Text(t, fontSize = 12.5.sp, color = Ink.ink, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** .card — шапка (янтарная — телефон, синяя — мозг), заголовок 15.5, текст, кнопки .cb (первая — тёмная). */
@Composable
private fun CardItem(item: FeedItem.Card, onCard: (FeedItem.Card, String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(16.dp))) {
        Text(item.head, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = if (item.phone) Ink.localD else Ink.serverD,
            modifier = Modifier.fillMaxWidth().background(if (item.phone) Ink.localT else Ink.serverT).padding(horizontal = 12.dp, vertical = 7.dp))
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Text(item.title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            if (item.text.isNotEmpty()) Text(item.text, fontSize = 13.sp, color = Ink.soft, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        }
        FlowRow(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item.buttons.forEachIndexed { i, (label, action) ->
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (i == 0) Color.White else Ink.ink,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (i == 0) Ink.ink else Ink.chipBg)
                        .clickable { onCard(item, action) }.padding(horizontal = 13.dp, vertical = 8.dp))
            }
        }
    }
}

/** Простая разметка ответа: «# заголовок», «- пункт», «1. пункт», **жирный**, `код`, блоки ``` . */
@Composable
fun Markdown(text: String) {
    val blocks = remember(text) { mdBlocks(text) }
    blocks.forEach { (kind, line) ->
        when (kind) {
            'h' -> Text(mdInline(line), fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.3.em)
            'l' -> Row { Text("•", modifier = Modifier.padding(start = 4.dp, end = 8.dp)); Text(mdInline(line)) }
            'n' -> Text(mdInline(line), modifier = Modifier.padding(start = 4.dp))
            'c' -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFE3E8F6),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF10182B)).padding(horizontal = 12.dp, vertical = 10.dp))
            else -> Text(mdInline(line))
        }
    }
}

private fun mdBlocks(text: String): List<Pair<Char, String>> {
    val out = mutableListOf<Pair<Char, String>>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += 'p' to para.toString().trim(); para.clear() }
    var code: StringBuilder? = null
    for (raw in text.lines()) {
        val l = raw.trimEnd()
        if (l.trimStart().startsWith("```")) { if (code == null) { flush(); code = StringBuilder() } else { out += 'c' to code.toString().trimEnd(); code = null }; continue }
        if (code != null) { code.appendLine(raw); continue }
        val t = l.trimStart()
        when {
            t.isEmpty() -> flush()
            t.startsWith("#") -> { flush(); out += 'h' to t.trimStart('#').trim() }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> { flush(); out += 'l' to t.drop(2) }
            Regex("^\\d+[.)] ").containsMatchIn(t) -> { flush(); out += 'n' to t }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
    }
    code?.let { out += 'c' to it.toString().trimEnd() }
    flush()
    return out
}

private fun mdInline(s: String) = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> { val e = s.indexOf("**", i + 2); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2, e)) }; i = e + 2 }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> { val e = s.indexOf('`', i + 1); withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Ink.chipBg)) { append(s.substring(i + 1, e)) }; i = e + 1 }
            else -> { append(s[i]); i++ }
        }
    }
}

/** .mtabs — вкладки режимов под полем: 13/500, выбранная — тёмная «таблетка», в конце «+» 30. */
@Composable
fun ModeTabs(state: Conversation.State, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(state.tabs, key = { it.id }) { m ->
            val on = m.id == state.mode
            Text(m.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) Color.White else Ink.soft,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Ink.ink else Color.Transparent)
                    .clickable { onSelect(m.id) }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        item {
            Box(Modifier.padding(start = 2.dp).size(30.dp).clip(CircleShape).border(1.dp, Ink.line, CircleShape).clickable(onClickLabel = tr(R.string.feed_new_mode), onClick = onAdd),
                contentAlignment = Alignment.Center) { Text("+", fontSize = 17.sp, color = Ink.soft) }
        }
    }
}
