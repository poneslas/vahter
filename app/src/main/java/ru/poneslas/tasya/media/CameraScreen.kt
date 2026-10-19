package ru.poneslas.tasya.media

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.poneslas.tasya.App
import ru.poneslas.tasya.core.Mode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.min

/** Снятый кадр в ленте: картинка с превью + пометки + обрезка (в долях кадра). */
class Frame(val bitmap: Bitmap, val marks: List<Attachments.Mark>, val crop: Attachments.Crop?)

/**
 * Камера в листе на весь экран (Продукт 5.17; на S24 — видоискатель за ~480 мс, заморозка 42–47 мс).
 * Внизу «Отправить» = «этот кадр + всё из ленты»; «＋» — оставить кадр и снять ещё; инструменты
 * «Обрезать / Обвести / Стрелка» замораживают кадр. PreviewView — COMPATIBLE (TextureView): обрезается в контейнерах.
 */
@Composable
fun CameraScreen(owner: LifecycleOwner, mode: Mode, maxImages: Int, onClose: () -> Unit, onSend: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember { PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var error by remember { mutableStateOf<String?>(null) }
    val frames = remember { mutableStateListOf<Frame>() }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    var editIdx by remember { mutableStateOf<Int?>(null) }
    var tool by remember { mutableStateOf<String?>(null) }
    val marks = remember { mutableStateListOf<Attachments.Mark>() }
    var crop by remember { mutableStateOf<Attachments.Crop?>(null) }
    var draft by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        try {
            val p = cameraProvider(ctx); provider = p
            val pv = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pv)
        } catch (t: Throwable) { error = "Камера недоступна: ${t.message ?: t.javaClass.simpleName}" }
    }
    DisposableEffect(Unit) { onDispose { runCatching { provider?.unbindAll() } } }

    fun live() { frozen = null; editIdx = null; tool = null; marks.clear(); crop = null; draft = emptyList() }
    fun current(): Frame? = (frozen ?: runCatching { preview.bitmap }.getOrNull())?.let { Frame(it, marks.toList(), crop) }
    fun freeze(t: String) { if (frozen == null) frozen = runCatching { preview.bitmap }.getOrNull(); tool = if (tool == t) null else t }
    fun commit(): Boolean {
        val f = current() ?: return false
        val i = editIdx
        if (i != null) frames[i] = f else frames += f
        return true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        frozen?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // Пометки поверх кадра; рисуем пальцем, когда выбран инструмент.
        Canvas(Modifier.fillMaxSize().pointerInput(tool) {
            if (tool == null) return@pointerInput
            detectDragGestures(
                onDragStart = { draft = listOf(it) },
                onDrag = { c, _ -> draft = draft + c.position },
                onDragEnd = {
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val pts = draft.map { (it.x / w) to (it.y / h) }
                    when (tool) {
                        "circle" -> if (pts.size > 3) marks += Attachments.Mark.Circle(pts)
                        "arrow" -> if (pts.size > 1) marks += Attachments.Mark.Arrow(pts.first(), pts.last())
                        "crop" -> if (pts.size > 1) {
                            val (a, b) = pts.first() to pts.last()
                            val c = Attachments.Crop(min(a.first, b.first), min(a.second, b.second), abs(a.first - b.first), abs(a.second - b.second))
                            if (c.w > .08f && c.h > .08f) crop = c
                        }
                    }
                    draft = emptyList()
                },
            )
        }) {
            val red = Color(0xFFFF3B30)
            fun p(x: Float, y: Float) = Offset(x * size.width, y * size.height)
            crop?.let { c ->
                val dim = Color(0x8C000000)
                drawRect(dim, Offset.Zero, Size(size.width, c.y * size.height))
                drawRect(dim, p(0f, c.y + c.h), Size(size.width, (1 - c.y - c.h) * size.height))
                drawRect(dim, p(0f, c.y), Size(c.x * size.width, c.h * size.height))
                drawRect(dim, p(c.x + c.w, c.y), Size((1 - c.x - c.w) * size.width, c.h * size.height))
                drawRect(Color.White, p(c.x, c.y), Size(c.w * size.width, c.h * size.height), style = Stroke(3f))
            }
            for (m in marks) when (m) {
                is Attachments.Mark.Circle -> drawPath(Path().apply { m.points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) } }, red, style = Stroke(10f))
                is Attachments.Mark.Arrow -> drawLine(red, p(m.from.first, m.from.second), p(m.to.first, m.to.second), 10f)
            }
            if (draft.size > 1) when (tool) {
                "crop" -> drawRect(Color.White, Offset(min(draft.first().x, draft.last().x), min(draft.first().y, draft.last().y)),
                    Size(abs(draft.first().x - draft.last().x), abs(draft.first().y - draft.last().y)), style = Stroke(3f))
                "arrow" -> drawLine(red, draft.first(), draft.last(), 10f)
                else -> drawPath(Path().apply { draft.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }, red, style = Stroke(10f))
            }
        }

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Round("✕", "Закрыть камеру") { if (frozen != null) live() else onClose() }
            Spacer(Modifier.weight(1f))
            Text(mode.title, color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x73000000)).padding(horizontal = 12.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            if (frozen != null && (marks.isNotEmpty() || crop != null)) Round("↶", "Отменить последнее") { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) else crop = null }
            else Spacer(Modifier.size(44.dp))
        }
        tool?.let { t ->
            Text(when (t) { "crop" -> "Выделите нужную часть"; "circle" -> "Обведите пальцем"; else -> "Проведите от начала к острию" },
                color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
        }
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (frames.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                frames.forEachIndexed { i, f ->
                    Box {
                        Image(f.bitmap.asImageBitmap(), "Кадр ${i + 1}", Modifier.size(46.dp, 64.dp).clip(RoundedCornerShape(8.dp))
                            .border(2.dp, if (editIdx == i) Color(0xFFFFD60A) else Color.White, RoundedCornerShape(8.dp))
                            .clickable {
                                if (frozen != null && editIdx == null) commit()
                                frozen = f.bitmap; editIdx = i; tool = null; marks.clear(); marks += f.marks; crop = f.crop
                            }, contentScale = ContentScale.Crop)
                        Box(Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(Color.Black).clickable {
                            frames.removeAt(i); if (editIdx == i) live() else editIdx?.let { if (it > i) editIdx = it - 1 }
                        }, contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 11.sp) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                listOf("crop" to "Обрезать", "circle" to "Обвести", "arrow" to "Стрелка").forEach { (k, t) ->
                    Text(t, color = if (tool == k) Color.Black else Color.White, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (tool == k) Color.White else Color(0x80000000))
                            .clickable { freeze(k) }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val canAdd = editIdx != null || frames.size < maxImages - 1
                Round("＋", "Оставить кадр и снять ещё", big = false, enabled = canAdd) { if (commit()) live() }
                Spacer(Modifier.weight(1f))
                val count = frames.size + if (editIdx == null) 1 else 0
                Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable(enabled = !busy) {
                    commit()
                    val all = frames.toList()
                    if (all.isEmpty()) { error = "Кадр не получен — камера не показывает изображение."; return@clickable }
                    busy = true
                    scope.launch {
                        val files = all.map { Attachments.render(ctx, it.bitmap, it.marks, it.crop).path }
                        onSend(files)
                    }
                }, contentAlignment = Alignment.Center) {
                    Text(if (busy) "…" else "↑", fontSize = 30.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    if (count > 1) Text("$count", fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Color(0xFFFFD60A)).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun Round(glyph: String, label: String, big: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(if (big) 56.dp else 48.dp).clip(CircleShape).background(Color(if (enabled) 0x73000000 else 0x33000000))
        .clickable(enabled = enabled, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, color = Color.White.copy(alpha = if (enabled) 1f else .4f), fontSize = 20.sp)
    }
}

suspend fun cameraProvider(ctx: android.content.Context): ProcessCameraProvider = suspendCancellableCoroutine { c ->
    val f = ProcessCameraProvider.getInstance(ctx)
    f.addListener({ runCatching { f.get() }.onSuccess { c.resume(it) }.onFailure { c.resumeWithException(it) } }, ContextCompat.getMainExecutor(ctx))
}

@Suppress("unused")
private val keepApp = App::class
