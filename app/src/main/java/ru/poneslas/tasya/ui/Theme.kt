package ru.poneslas.tasya.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Цвета прототипа (docs/design/prototype/app.css, :root). Янтарный — телефон, синий — мозг. */
object Ink {
    val page = Color(0xFFDFE3DA)
    val paper = Color(0xFFF7F8F4)
    val surf = Color(0xFFFFFFFF)
    val ink = Color(0xFF0E1A36)
    val soft = Color(0xFF4A5670)
    val faint = Color(0xFF8A93A6)
    val line = Color(0xFFD3D8CE)
    val local = Color(0xFFE8A317)
    val localT = Color(0xFFFBEFD2)
    val server = Color(0xFF2F4BE8)
    val serverT = Color(0xFFE1E6FD)
    val danger = Color(0xFFC2412D)
    val dangerT = Color(0xFFF8E1DC)
    val ok = Color(0xFF2E7D4F)
    val me = Color(0xFFE7EAE2)
}

@Composable
fun TasyaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink.ink, onPrimary = Color.White,
            background = Ink.paper, surface = Ink.surf, onSurface = Ink.ink, onBackground = Ink.ink,
            error = Ink.danger,
        ),
        content = content,
    )
}
