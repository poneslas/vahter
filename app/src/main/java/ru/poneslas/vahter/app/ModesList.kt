package ru.poneslas.vahter.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ru.poneslas.vahter.R
import ru.poneslas.vahter.core.Mode
import ru.poneslas.vahter.core.Modes
import ru.poneslas.vahter.tr
import ru.poneslas.vahter.ui.Ic
import ru.poneslas.vahter.ui.Ink
import ru.poneslas.vahter.ui.Item
import ru.poneslas.vahter.ui.Sw
import kotlin.math.roundToInt

/**
 * Режимы в настройках (ui.js, settings): строка — значок, название, «для чего», переключатель. Долгое нажатие на строку —
 * перестановка: строка сразу подхвачена (тащить не отпуская), у всех строк переключатели становятся ручками ≡ ([onReorder]),
 * в шапке — «Готово». В режиме перестановки тянуть можно и за ручку сразу. Порядок — как во вкладках под полем.
 */
@Composable
fun ModesList(
    modes: List<Mode>, reorder: Boolean, summary: (Mode) -> String,
    onReorder: () -> Unit, onMove: (from: Int, to: Int) -> Unit, onToggle: (Mode) -> Unit, onOpen: (Mode) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var dragIdx by remember { mutableIntStateOf(-1) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var rowH by remember { mutableFloatStateOf(1f) }
    val target = if (dragIdx < 0) -1 else (dragIdx + (dragY / rowH).roundToInt()).coerceIn(0, modes.lastIndex)
    val onMoveNow by rememberUpdatedState(onMove)
    val last by rememberUpdatedState(modes.lastIndex)
    // куда отпустили — считаем в момент отпускания: обработчик жеста живёт дольше одной перерисовки
    fun finish() {
        val to = if (dragIdx < 0) -1 else (dragIdx + (dragY / rowH).roundToInt()).coerceIn(0, last)
        if (dragIdx >= 0 && to != dragIdx) onMoveNow(dragIdx, to)
        dragIdx = -1; dragY = 0f
    }
    Column {
        modes.forEachIndexed { i, m ->
            key(m.id) {
                val idx by rememberUpdatedState(i)
                // соседи расступаются, пока тащат
                val shift = when {
                    dragIdx < 0 || i == dragIdx -> 0f
                    dragIdx < target && i in (dragIdx + 1)..target -> -rowH
                    dragIdx > target && i in target until dragIdx -> rowH
                    else -> 0f
                }
                val slide by animateFloatAsState(shift, label = "slide")
                val dragging = i == dragIdx
                Row(
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned { rowH = it.size.height.toFloat() }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragY else slide }
                        .then(if (dragging) Modifier.shadow(10.dp, RoundedCornerShape(14.dp)) else Modifier)
                        .background(if (reorder) Ink.surf else Ink.paper)
                        .pointerInput(m.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onReorder(); dragIdx = idx; dragY = 0f; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onDrag = { ch, d -> ch.consume(); dragY += d.y },
                                onDragEnd = { finish() }, onDragCancel = { finish() },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        Item(m.title, summary(m).ifBlank { null }, icon = Ic.mode(m.icon), onClick = if (reorder) null else ({ onOpen(m) }))
                    }
                    when {
                        reorder -> Box(
                            Modifier.height(48.dp).width(64.dp).pointerInput(m.id) {
                                detectDragGestures(
                                    onDragStart = { dragIdx = idx; dragY = 0f; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                    onDrag = { ch, d -> ch.consume(); dragY += d.y },
                                    onDragEnd = { finish() }, onDragCancel = { finish() },
                                )
                            }, contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(Ic.grip), tr(R.string.set_modes_drag, m.title), Modifier.size(22.dp), tint = Ink.soft) }
                        m.id == Modes.MAIN -> Box(Modifier.width(91.dp))   // у Ассистента выключателя нет — место пустое, строки ровные
                        else -> {
                            Box(Modifier.width(1.dp).height(40.dp).background(Ink.line))
                            Box(Modifier.height(40.dp).clickable { onToggle(m) }.padding(start = 16.dp, end = 22.dp), contentAlignment = Alignment.Center) { Sw(m.enabled) }
                        }
                    }
                }
            }
        }
    }
}
