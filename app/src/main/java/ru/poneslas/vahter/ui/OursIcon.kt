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
import ru.poneslas.vahter.assistant.SheetSt
import ru.poneslas.vahter.assistant.VMark

/** Значок «Вахтёр» (ui.js, oursIcon; .ours): бледно-янтарная плитка, в ней знак — ядро и два кольца. [live] — знак «слушает» (вступление). */
@Composable
fun OursIcon(size: Dp, radius: Dp = size * 0.31f, live: Boolean = false) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius))
        .background(Brush.radialGradient(0f to Color(0xFFFFF8E6), .55f to Color(0xFFFBEFD2), 1f to Color(0xFFF5DFA8))), contentAlignment = Alignment.Center) {
        VMark(if (live) SheetSt.REC else SheetSt.IDLE, size * .74f, still = !live, fake = live)
    }
}
