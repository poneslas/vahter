package ru.poneslas.vahter.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Цвета прототипа один в один (docs/design/prototype/app.css, :root). Янтарный — телефон, синий — мозг.
 * Шрифт — системный (на S24 — шрифт One UI): свои шрифты не встраиваем.
 */
object Ink {
    val page = Color(0xFFDFE3DA)
    val paper = Color(0xFFF7F8F4)
    val surf = Color(0xFFFFFFFF)
    val ink = Color(0xFF0E1A36)
    val soft = Color(0xFF4A5670)
    val faint = Color(0xFF8A93A6)
    val line = Color(0xFFD3D8CE)
    val local = Color(0xFFE8A317)
    val localD = Color(0xFF9A6A00)
    val localT = Color(0xFFFBEFD2)
    val server = Color(0xFF2F4BE8)
    val serverD = Color(0xFF2238B8)
    val danger = Color(0xFFC2412D)
    val dangerT = Color(0xFFF8E1DC)
    val ok = Color(0xFF2E7D4F)
    val okT = Color(0xFFDCEFE3)

    // Нейтральные из правил app.css
    val me = Color(0xFFE7EAE2)        // .me
    val chipBg = Color(0xFFECEEE8)    // .cb, .seg2, .recbtn
    val swOff = Color(0xFFC9CFC4)     // .sw.off, .dots i
    val swOffThumb = Color(0xFF7A8090)
    val grab = Color(0xFFCBD0C6)
    val prog = Color(0xFFE7EAE2)
    val noteText = Color(0xFF5B4300)
    val errText = Color(0xFF7A2416)
    val dlg = Color(0xFFFAFAFC)
    val brain = Color(0xFF3B5BDB)     // var(--brain) в полях режима и диалогах
}

/** Базовый текст прототипа: 15px, межстрочный 1.45, без разрядки. */
val BaseText = TextStyle(fontSize = 15.sp, lineHeight = 1.45.em, letterSpacing = 0.sp, color = Ink.ink)

@Composable
fun VahterTheme(content: @Composable () -> Unit) {
    val t = Typography()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink.ink, onPrimary = Color.White,
            background = Ink.paper, surface = Ink.surf, onSurface = Ink.ink, onBackground = Ink.ink,
            error = Ink.danger,
        ),
        typography = t.copy(bodyLarge = BaseText, bodyMedium = BaseText, bodySmall = BaseText.copy(fontSize = 13.sp)),
        content = content,
    )
}
