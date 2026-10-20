package ru.poneslas.vahter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Значок «Вахтёр» (ui.js, oursIcon; .ours): кольцо янтарный→синий, тёмная середина, четыре столбика волны. */
@Composable
fun OursIcon(size: Dp, radius: Dp = size * 0.31f) {
    val k = size.value / 58f
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().drawBehind {
            // conic-gradient(from 200deg …): поворачиваем сам градиент, квадрат с запасом закрывает углы
            rotate(200f - 90f) { drawRect(Brush.sweepGradient(listOf(Ink.local, Color(0xFFF5C35A), Ink.server, Color(0xFF6F86FF), Ink.local), center),
                topLeft = Offset(-this.size.width, -this.size.height), size = Size(this.size.width * 3, this.size.height * 3)) }
        })
        Box(Modifier.fillMaxSize().padding((11 * k).dp).clip(RoundedCornerShape((12 * k).dp)).background(Color(0xFF10182B)))
        Row(horizontalArrangement = Arrangement.spacedBy((3 * k).dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(8, 16, 22, 12).forEachIndexed { i, h ->
                Box(Modifier.width((3.5f * k).dp).height((h * k).dp).clip(RoundedCornerShape((2 * k).dp))
                    .background(if (i < 2) Color(0xFFF5C35A) else Color(0xFF7C93FF)))
            }
        }
    }
}
