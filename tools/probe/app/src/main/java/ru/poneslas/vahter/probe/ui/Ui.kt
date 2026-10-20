package ru.poneslas.vahter.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.poneslas.vahter.probe.core.Prompt
import ru.poneslas.vahter.probe.core.Status

/**
 * Пробник — прибор, а не продукт: холодный светлый фон, тёмные чернила, один рабочий цвет для действий
 * и три цвета итога. Всё внимание — на одну фразу «что сделать» и одну большую кнопку.
 */
object Tone {
    val bg = Color(0xFFEDF1EF)
    val surface = Color(0xFFFFFFFF)
    val ink = Color(0xFF16201D)
    val muted = Color(0xFF5D6A66)
    val line = Color(0xFFD5DDD9)
    val action = Color(0xFF0E5A6A)
    val pass = Color(0xFF2E7A4C)
    val fail = Color(0xFFB2432A)
    val partial = Color(0xFFA36A10)
    val idle = Color(0xFF9AA5A1)
}

val probeColors: ColorScheme = lightColorScheme(
    primary = Tone.action, onPrimary = Color.White,
    background = Tone.bg, onBackground = Tone.ink,
    surface = Tone.surface, onSurface = Tone.ink,
    outline = Tone.line,
)

@Composable
fun ProbeTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = probeColors, content = content)

fun statusColor(s: Status) = when (s) {
    Status.PASS -> Tone.pass
    Status.FAIL -> Tone.fail
    Status.PARTIAL -> Tone.partial
    Status.RUNNING -> Tone.action
    else -> Tone.idle
}

fun statusWord(s: Status) = when (s) {
    Status.PASS -> "работает"
    Status.FAIL -> "не работает"
    Status.PARTIAL -> "частично"
    Status.RUNNING -> "идёт"
    Status.NOT_IMPL -> "нет"
    else -> "не проверено"
}

@Composable
fun StatusDot(s: Status, size: Int = 10) {
    Box(Modifier.size(size.dp).background(statusColor(s), CircleShape))
}

/** Главная кнопка экрана: во всю ширину, высокая, у большого пальца. */
@Composable
fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick, Modifier.fillMaxWidth().height(56.dp), enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Tone.action),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
}

/**
 * Вопрос проверки — один и тот же вид в приложении и в листе: крупная фраза и ответы столбиком.
 * Первый ответ — главный (залитый), остальные — контурные.
 */
@Composable
fun PromptBlock(p: Prompt, onAnswer: (String) -> Unit, large: Boolean = true, actions: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(p.text, fontSize = if (large) 24.sp else 18.sp, lineHeight = if (large) 31.sp else 24.sp, fontWeight = FontWeight.Medium, color = Tone.ink)
        // Кнопки шага (например «Выбрать фото») — сразу под фразой, выше запасных выходов.
        actions()
        p.options.forEachIndexed { i, o ->
            // Запасные выходы («Не открывается», «Пропустить») — всегда контурные: главное действие тогда — на телефоне.
            if (i == 0 && o !in escapes) {
                Button(
                    { onAnswer(o) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tone.action),
                ) { Text(o, fontSize = 16.sp) }
            } else {
                OutlinedButton({ onAnswer(o) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(o, fontSize = 16.sp, color = Tone.ink)
                }
            }
        }
    }
}

private val escapes = setOf("Не открывается", "Не получается", "Пропустить", "Лист не открылся", "Хватит", "Закончить", "Не пришло", "Кнопка не сработала")

@Composable
fun SectionTitle(text: String, note: String? = null) {
    Column(Modifier.padding(top = 20.dp, bottom = 6.dp)) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Tone.ink)
        note?.let { Text(it, fontSize = 14.sp, color = Tone.muted) }
    }
}
