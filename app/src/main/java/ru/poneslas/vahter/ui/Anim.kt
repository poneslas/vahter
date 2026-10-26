package ru.poneslas.vahter.ui

import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue

/** Время для сцен и живых элементов: петля [loop] 0…1, [ramp] и [window] — фазы долями петли (intro.css), [seconds] — для sin-волн. */

/** 0…1 между [a] и [b], с мягким разгоном и торможением. */
fun ramp(t: Float, a: Float, b: Float) = FastOutSlowInEasing.transform(((t - a) / (b - a)).coerceIn(0f, 1f))
/** Видно между [inA..inB] (появление) и [outA..outB] (исчезновение). */
fun window(t: Float, inA: Float, inB: Float, outA: Float = 2f, outB: Float = 2f) = ramp(t, inA, inB) * (1f - ramp(t, outA, outB))

@Composable
fun loop(ms: Int = 6000): Float {
    val tr = rememberInfiniteTransition(label = "intro")
    val t by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "t")
    return t
}

// ── общие кусочки: мини-лист, реплики, волна ───────────────────────────


/** Секунды с начала показа, непрерывно (для волн и свечения: sin(t / период)). */
@Composable
fun seconds(): Float {
    val tr = rememberInfiniteTransition(label = "seconds")
    val t by tr.animateFloat(0f, 100f, infiniteRepeatable(tween(100_000, easing = LinearEasing), RepeatMode.Restart), label = "s")
    return t
}


/**
 * Часы, которые идут, только пока есть жизнь: [active] — сейчас что-то происходит (запись, ответ, касание), иначе ещё [linger] мс
 * и стоп — кадры больше не рисуются (батарея: солнышко и свечение в покое замирают, 26.10). [wake] — разбудить ещё на [linger]
 * (сменилась иконка, режим). Время не прыгает: продолжает с того места, где замерло.
 */
@Composable
fun livelyClock(active: Boolean, wake: Any? = null, linger: Long = 6000L): androidx.compose.runtime.State<Float> {
    val t = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(active, wake) {
        val until = if (active) Long.MAX_VALUE else android.os.SystemClock.uptimeMillis() + linger
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (android.os.SystemClock.uptimeMillis() < until) {
            androidx.compose.runtime.withFrameNanos { n -> t.floatValue += ((n - last) / 1e9f).coerceAtMost(.1f); last = n }
        }
    }
    return t
}
