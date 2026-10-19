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

