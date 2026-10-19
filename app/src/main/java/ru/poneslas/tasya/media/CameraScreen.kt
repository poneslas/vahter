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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.res.painterResource
import ru.poneslas.tasya.ui.Ic
import ru.poneslas.tasya.ui.Ink
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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
fun CameraScreen(owner: LifecycleOwner, mode: Mode, maxImages: Int, onClose: () -> Unit, onSend: (files: List<String>, question: String) -> Unit) {
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
            // задняя; нет — передняя; нет и её (эмулятор, внешняя камера) — любая
            val sel = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                .firstOrNull { runCatching { p.hasCamera(it) }.getOrDefault(false) }
                ?: p.availableCameraInfos.firstOrNull()?.cameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
            p.bindToLifecycle(owner, sel, pv)
        } catch (t: Throwable) { error = "Камера недоступна: ${t.message ?: t.javaClass.simpleName}" }
    }
    DisposableEffect(Unit) { onDispose { runCatching { provider?.unbindAll() } } }
    // Картинка не пошла за 4 с — сказать словами, а не держать чёрный экран.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000)
        if (preview.previewStreamState.value != PreviewView.StreamState.STREAMING && error == null)
            error = "Камера не показывает изображение. Закройте и откройте ещё раз; если повторится — перезапустите телефон."
    }

    fun live() { frozen = null; editIdx = null; tool = null; marks.clear(); crop = null; draft = emptyList() }
    fun current(): Frame? = (frozen ?: runCatching { preview.bitmap }.getOrNull())?.let { Frame(it, marks.toList(), crop) }
    fun freeze(t: String) { if (frozen == null) frozen = runCatching { preview.bitmap }.getOrNull(); tool = if (tool == t) null else t }
    fun commit(): Boolean {
        val f = current() ?: return false
        val i = editIdx
        if (i != null) frames[i] = f else frames += f
        return true
    }

    // Вопрос голосом к снимку (.mic2): зажал — пишет, отпустил — текст уходит вместе с кадрами.
    val voice = App.instance.voice
    var question by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }

    fun send(q: String) {
        if (busy) return
        commit()
        val all = frames.toList()
        if (all.isEmpty()) { error = "Кадр не получен — камера не показывает изображение."; return }
        busy = true
        scope.launch { onSend(all.map { Attachments.render(ctx, it.bitmap, it.marks, it.crop).path }, q) }
    }

    val white = Color.White
    val shadow = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), blurRadius = 8f))
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12))) {
        // .vf — видоискатель на весь экран; заморожен — белая рамка 3
        AndroidView({ preview }, Modifier.fillMaxSize())
        frozen?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // пометки: красные 5, затемнение вне обрезки .55, рамка обрезки белая 2
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
                            val (p1, p2) = pts.first() to pts.last()
                            val c = Attachments.Crop(min(p1.first, p2.first), min(p1.second, p2.second), abs(p1.first - p2.first), abs(p1.second - p2.second))
                            if (c.w > .08f && c.h > .08f) crop = c
                        }
                    }
                    draft = emptyList()
                },
            )
        }) {
            val red = Color(0xFFFF3B30)
            val sw = 5.dp.toPx()
            fun p(x: Float, y: Float) = Offset(x * size.width, y * size.height)
            fun arrow(a: Offset, b: Offset) {
                drawLine(red, a, b, sw, cap = StrokeCap.Round)
                val ang = atan2(b.y - a.y, b.x - a.x); val l = 16.dp.toPx()
                drawLine(red, b, Offset(b.x - l * cos(ang - .5f), b.y - l * sin(ang - .5f)), sw, cap = StrokeCap.Round)
                drawLine(red, b, Offset(b.x - l * cos(ang + .5f), b.y - l * sin(ang + .5f)), sw, cap = StrokeCap.Round)
            }
            val cr = if (tool == "crop" && draft.size > 1) Attachments.Crop(min(draft.first().x, draft.last().x) / size.width, min(draft.first().y, draft.last().y) / size.height,
                abs(draft.first().x - draft.last().x) / size.width, abs(draft.first().y - draft.last().y) / size.height) else crop
            cr?.let { c ->
                val dim = Color(0x8C000000)
                drawRect(dim, Offset.Zero, Size(size.width, c.y * size.height))
                drawRect(dim, p(0f, c.y + c.h), Size(size.width, (1 - c.y - c.h) * size.height))
                drawRect(dim, p(0f, c.y), Size(c.x * size.width, c.h * size.height))
                drawRect(dim, p(c.x + c.w, c.y), Size((1 - c.x - c.w) * size.width, c.h * size.height))
                drawRect(white, p(c.x, c.y), Size(c.w * size.width, c.h * size.height), style = Stroke(2.dp.toPx()))
            }
            for (m in marks) when (m) {
                is Attachments.Mark.Circle -> drawPath(Path().apply { m.points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) } },
                    red, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                is Attachments.Mark.Arrow -> arrow(p(m.from.first, m.from.second), p(m.to.first, m.to.second))
            }
            if (draft.size > 1) when (tool) {
                "arrow" -> arrow(draft.first(), draft.last())
                "circle" -> drawPath(Path().apply { draft.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }, red, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (frozen != null) drawRect(Color(0xE6FFFFFF), style = Stroke(3.dp.toPx() * 2))
        }
        // затемнение сверху 120
        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color(0x8C000000), Color.Transparent))))

        // .ctop: ✕ (или «Переснять»), режим в «таблетке», «Отменить последнее»
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CBtn(Ic.x, if (frozen != null) "Переснять" else "Закрыть камеру") { if (frozen != null) live() else onClose() }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x73000000)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Ic.mode(mode.icon)), null, Modifier.size(16.dp), tint = white)
                Spacer(Modifier.width(6.dp))
                Text(mode.title, color = white, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            if (frozen != null && (marks.isNotEmpty() || crop != null)) CBtn(Ic.undo, "Отменить последнее") { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) else crop = null }
            else Spacer(Modifier.size(40.dp))
        }
        // .chint — подсказка инструмента
        tool?.let { t ->
            Text(when (t) { "crop" -> "Выделите нужную часть"; "circle" -> "Обведите пальцем"; else -> "Проведите от начала к острию" },
                color = white, fontSize = 14.sp, style = shadow, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 62.dp))
        }
        error?.let { Text(it, color = white, fontSize = 15.sp, style = shadow, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
        // .q — вопрос голосом
        if (listening || asking || question.isNotEmpty()) Text(if (listening) "Слушаю…" else if (asking) "Распознаю…" else question,
            color = white, fontSize = 20.sp, fontWeight = FontWeight.Medium, style = shadow,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 230.dp))

        // .cbottom: кадры, инструменты, «＋» · «Отправить» · микрофон
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xA6000000))))
            .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (frames.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                frames.forEachIndexed { i, f ->
                    Box {
                        val ratio = f.bitmap.width.toFloat() / f.bitmap.height
                        Image(f.bitmap.asImageBitmap(), "Кадр ${i + 1}: править", Modifier.height(64.dp).width((64 * ratio).coerceIn(24f, 60f).dp)
                            .clip(RoundedCornerShape(10.dp)).border(2.dp, if (editIdx == i) Color(0xFFFFD60A) else Color(0xB3FFFFFF), RoundedCornerShape(10.dp))
                            .clickable {
                                if (frozen != null && editIdx == null) commit()
                                frozen = f.bitmap; editIdx = i; tool = null; marks.clear(); marks += f.marks; crop = f.crop
                            }, contentScale = ContentScale.Crop)
                        Box(Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(22.dp).clip(CircleShape).background(Color(0xFF111111)).clickable {
                            frames.removeAt(i); if (editIdx == i) live() else editIdx?.let { if (it > i) editIdx = it - 1 }
                        }, contentAlignment = Alignment.Center) { Icon(painterResource(Ic.x), "Удалить кадр ${i + 1}", Modifier.size(14.dp), tint = white) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                listOf(Triple("crop", "Обрезать", Ic.crop), Triple("circle", "Обвести", Ic.circle), Triple("arrow", "Стрелка", Ic.arrow)).forEach { (k, t, ic) ->
                    val on = tool == k
                    Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) white else Color(0x80000000)).clickable { freeze(k) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(ic), null, Modifier.size(18.dp), tint = if (on) Color(0xFF111111) else white)
                        Spacer(Modifier.width(6.dp))
                        Text(t, fontSize = 13.5.sp, color = if (on) Color(0xFF111111) else white)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                val canAdd = editIdx != null || frames.size < maxImages - 1
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0x2EFFFFFF)).alpha(if (canAdd) 1f else .35f)
                    .clickable(enabled = canAdd, onClickLabel = "Оставить кадр и снять ещё") { if (commit()) live() }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(Ic.plus), null, Modifier.size(24.dp), tint = white)
                }
                val count = frames.size + if (editIdx == null) 1 else 0
                Box {
                    Box(Modifier.size(76.dp).clip(CircleShape).background(white).border(4.dp, Color(0x99FFFFFF), CircleShape)
                        .clickable(enabled = !busy, onClickLabel = if (frames.isEmpty()) "Отправить" else "Отправить все кадры") { send(question) }, contentAlignment = Alignment.Center) {
                        if (busy) Text("…", fontSize = 26.sp, color = Color(0xFF111111)) else Icon(painterResource(Ic.send), null, Modifier.size(30.dp), tint = Color(0xFF111111))
                    }
                    if (count > 1) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).heightIn(min = 24.dp).widthIn(min = 24.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFFD60A)).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) { Text("$count", fontSize = 13.sp, color = Color(0xFF111111)) }
                }
                // .mic2: зажать и спросить голосом — вопрос уйдёт вместе со снимком
                Box(Modifier.size(56.dp).clip(CircleShape).background(if (listening) Ink.local else Color(0x2EFFFFFF))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            if (!voice.hasMic() || !voice.installed.value) { error = if (!voice.hasMic()) "Нужен доступ к микрофону" else "Голос ещё не скачан: Настройки → Голос"; waitForUpOrCancellation(); return@awaitEachGesture }
                            listening = voice.start()
                            waitForUpOrCancellation()
                            if (!listening) return@awaitEachGesture
                            listening = false; asking = true
                            scope.launch {
                                val r = voice.stop(); asking = false
                                if (r is ru.poneslas.tasya.voice.Voice.Result.Text) { question = r.text; send(r.text) }
                            }
                        }
                    }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(Ic.mic), "Зажмите и спросите голосом", Modifier.size(24.dp), tint = white)
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { if (voice.recording.value) voice.cancel() } }
}

/** .cbtn: круг 40, чёрный 45 %. */
@Composable
private fun CBtn(icon: Int, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x73000000)).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), label, Modifier.size(20.dp), tint = Color.White)
    }
}

suspend fun cameraProvider(ctx: android.content.Context): ProcessCameraProvider = suspendCancellableCoroutine { c ->
    val f = ProcessCameraProvider.getInstance(ctx)
    f.addListener({ runCatching { f.get() }.onSuccess { c.resume(it) }.onFailure { c.resumeWithException(it) } }, ContextCompat.getMainExecutor(ctx))
}

