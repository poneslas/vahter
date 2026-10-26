package ru.poneslas.vahter.media

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.poneslas.vahter.ui.Ink
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * Зум, объективы и фокус видоискателя (лист «солнышко», 26.10): щипок — зум, и пока щиплют — шкала, как в системной камере;
 * в покое — кнопки объективов «0,6 · 1× · 2× · 5×». Шкала и кнопки — одно и то же: отметки шкалы и есть объективы,
 * активная кнопка показывает текущее увеличение («2,4×»). Касание — фокус и экспозиция по точке, янтарное колечко.
 * Объективы — из характеристик камер (физические камеры логической); телефон их не отдаёт — разумные отметки по пределам зума.
 */

/** Зум камеры: текущее, пределы, отметки объективов; шкала видна, пока зумят (и ещё миг после). */
@Stable
class ZoomCtl(private val scope: CoroutineScope, private val ctx: android.content.Context) {
    var camera: Camera? = null
    var ratio by mutableFloatStateOf(1f)
    var min by mutableFloatStateOf(1f)
    var max by mutableFloatStateOf(1f)
    var marks by mutableStateOf(listOf(1f))
    var ruler by mutableStateOf(false)
    private var hide: Job? = null
    private var glide: Job? = null

    val zoomable get() = max > min * 1.05f

    fun bind(c: Camera) {
        camera = c
        val z = c.cameraInfo.zoomState.value
        if (z != null) { min = z.minZoomRatio; max = z.maxZoomRatio; ratio = z.zoomRatio }
        marks = lensMarks(ctx, c.cameraInfo, min, max)
    }

    fun onState(r: Float, lo: Float, hi: Float) {
        ratio = r
        if (lo != min || hi != max) { min = lo; max = hi; camera?.let { marks = lensMarks(ctx, it.cameraInfo, lo, hi) } }
    }

    /** Сразу (щипок, шкала): без анимации — камера поспевает за пальцем. */
    fun set(r: Float, showRuler: Boolean = true) {
        glide?.cancel()
        val v = r.coerceIn(min, max)
        ratio = v
        camera?.cameraControl?.setZoomRatio(v)
        if (showRuler) touch()
    }

    /** Кнопка объектива: плавно доехать (≈ 260 мс), как в системной камере. */
    fun glideTo(r: Float) {
        glide?.cancel()
        val from = ratio; val to = r.coerceIn(min, max)
        glide = scope.launch {
            val a = Animatable(0f)
            a.animateTo(1f, tween(260)) { val v = exp(ln(from) + (ln(to) - ln(from)) * value); ratio = v; camera?.cameraControl?.setZoomRatio(v) }
        }
    }

    /** Шкала показана; спрятать через 1,4 с без движения. */
    fun touch() {
        ruler = true
        hide?.cancel()
        hide = scope.launch { delay(1400); ruler = false }
    }
}

/** Отметки объективов: из фокусных расстояний физических камер (эквивалент — фокус / диагональ матрицы), иначе — по пределам зума. */
@OptIn(ExperimentalCamera2Interop::class)
fun lensMarks(ctx: android.content.Context, info: CameraInfo, min: Float, max: Float): List<Float> {
    val found: List<Float> = runCatching {
        val c2 = Camera2CameraInfo.from(info)
        val cm = ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)
        fun eq(ch: CameraCharacteristics): Float? {
            val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
            val s = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            return f / hypot(s.width, s.height)
        }
        val logical = cm.getCameraCharacteristics(c2.cameraId)
        val main = eq(logical) ?: return@runCatching emptyList<Float>()
        logical.physicalCameraIds.mapNotNull { id -> eq(cm.getCameraCharacteristics(id))?.div(main) }
    }.getOrDefault(emptyList())
    val raw: List<Float> = (found + 1f).filter { it >= min * .97f && it <= max * 1.01f }
    val lenses: List<Float> = if (raw.size > 1) raw else buildList {
        if (min < .95f) add(min); add(1f); if (max >= 2f) add(2f); if (max >= 5f) add(5f)
    }
    // ровно и без дублей: 0,6 · 1 · 2 · 3 · 5 · 10
    return lenses.map { if (it < 1f) (it * 10).roundToInt() / 10f else it.roundToInt().toFloat().coerceAtLeast(1f) }
        .sorted().fold(mutableListOf<Float>()) { acc, v -> if (acc.isEmpty() || v / acc.last() > 1.25f) acc.add(v); acc }
}

/** «0,6» · «1×» · «2,4×» — по-русски, с запятой; у кнопки без отметки «×» не пишем, как в системной камере. */
fun zoomLabel(v: Float, x: Boolean = true): String {
    val r = (v * 10).roundToInt() / 10f
    val s = if (r % 1f == 0f) r.toInt().toString() else "%.1f".format(r).replace('.', ',')
    return if (x) "$s×" else s
}

/** Кнопки объективов в покое, шкала — пока зумят. Внизу видоискателя, на тёмной капсуле. */
@Composable
fun ZoomBar(z: ZoomCtl, modifier: Modifier = Modifier) {
    if (!z.zoomable) return
    // провести пальцем по кнопкам объективов или по шкале — зум, шкала раскрывается (как в системной камере)
    val drag = Modifier.pointerInput(Unit) {
        val per = 110.dp.toPx()
        detectHorizontalDragGestures(onDragStart = { z.touch() }) { ch, dx -> ch.consume(); z.set(exp(ln(z.ratio) - dx / per)) }
    }
    AnimatedContent(z.ruler, modifier.then(drag), transitionSpec = { ((fadeIn(tween(160)) + scaleIn(tween(200), .92f)) togetherWith (fadeOut(tween(140)) + scaleOut(tween(160), .92f))) using SizeTransform(clip = false) },
        contentAlignment = Alignment.BottomCenter, label = "zoom") { r ->
        if (r) Ruler(z) else Lenses(z)
    }
}

@Composable
private fun Lenses(z: ZoomCtl) {
    // активный объектив — последний, до которого дошёл зум; на нём — текущее увеличение
    val active = z.marks.indexOfLast { z.ratio >= it * .97f }.coerceAtLeast(0)
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x59000000)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        z.marks.forEachIndexed { i, m ->
            val on = i == active
            Box(Modifier.size(if (on) 40.dp else 34.dp).clip(CircleShape).background(if (on) Color(0xE6FFFFFF) else Color.Transparent)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = zoomLabel(m)) { z.glideTo(m) },
                contentAlignment = Alignment.Center) {
                Text(if (on) zoomLabel(z.ratio) else zoomLabel(m, x = false), fontSize = if (on) 13.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold, color = if (on) Ink.localD else Color.White)
            }
        }
    }
}


/** Шкала: неподвижная риска по центру, под ней едет линейка (логарифм — каждый «×2» одинаковой длины); отметки объективов подписаны. */
@Composable
private fun Ruler(z: ZoomCtl) {
    val tm = rememberTextMeasurer()
    Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x66000000))) {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) { ruler(z, tm) }
    }
}

private fun DrawScope.ruler(z: ZoomCtl, tm: TextMeasurer) {
    val per = 110.dp.toPx()   // пикселей на «×e»: 1× → 2× ≈ 76 dp
    val cx = size.width / 2
    val base = size.height - 14.dp.toPx()
    fun x(v: Float) = cx + (ln(v) - ln(z.ratio)) * per
    // мелкие риски — 10 на каждый «×e», гаснут к краям
    val lo = ln(z.min); val hi = ln(z.max)
    var t = lo
    while (t <= hi + 1e-3f) {
        val px = cx + (t - ln(z.ratio)) * per
        if (px in 0f..size.width) {
            val edge = 1f - (abs(px - cx) / cx).coerceIn(0f, 1f)
            drawLine(Color.White.copy(alpha = .25f + .45f * edge), Offset(px, base - 6.dp.toPx()), Offset(px, base), 1.dp.toPx())
        }
        t += .1f
    }
    // объективы: длинная риска и подпись
    z.marks.forEach { m ->
        val px = x(m); if (px < -40f || px > size.width + 40f) return@forEach
        drawLine(Color.White, Offset(px, base - 12.dp.toPx()), Offset(px, base), 1.5.dp.toPx())
        val l = tm.measure(zoomLabel(m), TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium))
        drawText(l, topLeft = Offset(px - l.size.width / 2, base - 14.dp.toPx() - l.size.height))
    }
    // риска по центру и текущее увеличение — янтарём
    drawLine(Ink.local, Offset(cx, base - 18.dp.toPx()), Offset(cx, base + 4.dp.toPx()), 2.5.dp.toPx())
    val cur = tm.measure(zoomLabel(z.ratio), TextStyle(color = Ink.local, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    drawText(cur, topLeft = Offset(cx - cur.size.width / 2, 4.dp.toPx()))
}

/** Фокус по касанию: камера наводится и меряет свет в точке, колечко сжимается к ней и гаснет. */
class FocusRing { var at by mutableStateOf<Offset?>(null); val k = Animatable(0f) }

fun focusAt(camera: Camera?, preview: PreviewView, p: Offset, ring: FocusRing, scope: CoroutineScope) {
    val pt = preview.meteringPointFactory.createPoint(p.x, p.y)
    runCatching {
        camera!!.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS).build())
    }
    ring.at = p
    scope.launch { ring.k.snapTo(0f); ring.k.animateTo(1f, tween(1300)); ring.at = null }
}

/** Колечко фокуса: 0..0,25 — сжимается от 1,5 до 1; дальше держится и к концу гаснет. */
fun DrawScope.focusRing(ring: FocusRing) {
    val p = ring.at ?: return
    val k = ring.k.value
    val s = if (k < .25f) 1.5f - 2f * k else 1f
    val a = if (k < .75f) 1f else (1f - k) * 4f
    val r = 34.dp.toPx() * s
    drawCircle(Ink.local.copy(alpha = a), r, p, style = Stroke(2.dp.toPx()))
    drawCircle(Ink.local.copy(alpha = a * .9f), 3.dp.toPx(), p)
}
