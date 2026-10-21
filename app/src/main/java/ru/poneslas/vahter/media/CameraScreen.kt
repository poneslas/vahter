package ru.poneslas.vahter.media

import ru.poneslas.vahter.tr
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.poneslas.vahter.App
import ru.poneslas.vahter.R
import ru.poneslas.vahter.core.Mode
import ru.poneslas.vahter.ui.Ic
import ru.poneslas.vahter.ui.Ink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Снятый кадр: картинка с превью + пометки + обрезка (в долях кадра). */
class Frame(val bitmap: Bitmap, val marks: List<Attachments.Mark>, val crop: Attachments.Crop?)

/** Что тянем в рамке обрезки. */
private enum class Grip { TL, TR, BL, BR, L, R, T, B, MOVE }

/**
 * Камера внутри листа (решение 19.10): видоискатель — скруглённая карточка под шапкой листа, инструменты и кнопки —
 * на бумаге листа под ним, не поверх картинки. [full] — на весь экран (кнопка ⤢ в шапке). На S24: видоискатель ~480 мс,
 * заморозка 42–47 мс. «Отправить» = этот кадр + всё из ленты; «＋» — оставить кадр и снять ещё.
 * Обрезка — как в редакторе фото: кадр с отступами, рамка с кругляшками в углах и чёрточками на сторонах.
 */
@Composable
fun CameraPanel(
    owner: LifecycleOwner, mode: Mode, maxImages: Int, full: Boolean,
    onClose: () -> Unit, onCollapse: () -> Unit, onSend: (files: List<String>, question: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val preview = remember { PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var error by remember { mutableStateOf<String?>(null) }
    val frames = remember { mutableStateListOf<Frame>() }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    var editIdx by remember { mutableStateOf<Int?>(null) }
    var tool by remember { mutableStateOf<String?>(null) }
    val marks = remember { mutableStateListOf<Attachments.Mark>() }
    var crop by remember { mutableStateOf<Attachments.Crop?>(null) }
    var draft by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var cropDragging by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val errUnavailable = stringResource(R.string.cam_unavailable)
    val errNoPicture = stringResource(R.string.cam_no_picture)
    val errNoFrame = stringResource(R.string.cam_no_frame)
    val errNeedMic = stringResource(R.string.cmp_need_mic)
    val errNeedVoice = stringResource(R.string.cmp_no_voice)

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
        } catch (t: Throwable) { error = errUnavailable.format(t.message ?: t.javaClass.simpleName) }
    }
    DisposableEffect(Unit) { onDispose { runCatching { provider?.unbindAll() } } }
    // Картинка не пошла за 4 с — сказать словами, а не держать чёрный экран.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000)
        if (preview.previewStreamState.value != PreviewView.StreamState.STREAMING && error == null) error = errNoPicture
    }

    fun live() { frozen = null; editIdx = null; tool = null; marks.clear(); crop = null; draft = emptyList() }
    fun grab(): Bitmap? = frozen ?: runCatching { preview.bitmap }.getOrNull() ?: testFrame(ctx, preview.width, preview.height)
    fun current(): Frame? = grab()?.let { Frame(it, marks.toList(), crop) }
    fun pick(t: String) {
        if (frozen == null) frozen = grab()
        if (frozen == null) { error = errNoFrame; return }
        tool = if (tool == t) null else t
        if (tool == "crop" && crop == null) crop = Attachments.Crop(0f, 0f, 1f, 1f)
    }
    fun commit(): Boolean {
        val f = current() ?: return false
        val i = editIdx
        if (i != null) frames[i] = f else frames += f
        return true
    }

    // Вопрос голосом к снимку: зажал — пишет, отпустил — текст уходит вместе с кадрами.
    val voice = App.instance.voice
    var question by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }

    fun send(q: String) {
        if (busy) return
        commit()
        val all = frames.toList()
        if (all.isEmpty()) { error = errNoFrame; return }
        busy = true
        // Обрезка «на весь кадр» — не обрезка.
        scope.launch { onSend(all.map { f -> Attachments.render(ctx, f.bitmap, f.marks, f.crop?.takeIf { it.w < .99f || it.h < .99f }).path }, q) }
    }

    DisposableEffect(full) {
        ru.poneslas.vahter.assistant.Sheet.cameraBack = { if (tool == "crop") tool = null else if (frozen != null) live() else if (full) onCollapse() else onClose() }
        onDispose { ru.poneslas.vahter.assistant.Sheet.cameraBack = null }
    }
    DisposableEffect(Unit) { onDispose { if (voice.recording.value) voice.cancel() } }

    val white = Color.White
    val shadow = TextStyle(shadow = Shadow(Color(0xCC000000), blurRadius = 8f))
    val red = Color(0xFFFF3B30)

    Column(modifier.then(if (full) Modifier.background(Color(0xFF0B0D12)) else Modifier)) {
        // ── видоискатель ──
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (full) Modifier else Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)))
                .background(Color(0xFF0B0D12)),
        ) {
            val boxW = with(density) { maxWidth.toPx() }; val boxH = with(density) { maxHeight.toPx() }
            val pad = if (tool == "crop") with(density) { 28.dp.toPx() } else 0f
            val bmp = frozen
            // Где на экране лежит кадр: целиком, по центру, с отступами в режиме обрезки.
            val img: Rect = if (bmp == null) Rect(0f, 0f, boxW, boxH) else {
                val k = min((boxW - 2 * pad) / bmp.width, (boxH - 2 * pad) / bmp.height)
                val w = bmp.width * k; val h = bmp.height * k
                Rect((boxW - w) / 2, (boxH - h) / 2, (boxW + w) / 2, (boxH + h) / 2)
            }
            fun toImg(o: Offset) = Pair(((o.x - img.left) / img.width).coerceIn(0f, 1f), ((o.y - img.top) / img.height).coerceIn(0f, 1f))
            fun toScr(x: Float, y: Float) = Offset(img.left + x * img.width, img.top + y * img.height)

            AndroidView({ preview }, Modifier.fillMaxSize())
            if (bmp != null) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12)))
                Image(bmp.asImageBitmap(), null, Modifier.offset { IntOffset(img.left.roundToInt(), img.top.roundToInt()) }
                    .size(with(density) { img.width.toDp() }, with(density) { img.height.toDp() }), contentScale = ContentScale.FillBounds)
            }

            // Пометки и рамка обрезки.
            val gripR = with(density) { 36.dp.toPx() }
            Canvas(Modifier.fillMaxSize().pointerInput(tool, img) {
                when (tool) {
                    "crop" -> awaitEachGesture {
                        val down = awaitFirstDown()
                        val c = crop ?: Attachments.Crop(0f, 0f, 1f, 1f)
                        val tl = toScr(c.x, c.y); val br = toScr(c.x + c.w, c.y + c.h)
                        val p = down.position
                        fun near(a: Offset) = hypot(p.x - a.x, p.y - a.y) < gripR
                        val g = when {
                            near(tl) -> Grip.TL; near(Offset(br.x, tl.y)) -> Grip.TR; near(Offset(tl.x, br.y)) -> Grip.BL; near(br) -> Grip.BR
                            abs(p.x - tl.x) < gripR && p.y in tl.y..br.y -> Grip.L; abs(p.x - br.x) < gripR && p.y in tl.y..br.y -> Grip.R
                            abs(p.y - tl.y) < gripR && p.x in tl.x..br.x -> Grip.T; abs(p.y - br.y) < gripR && p.x in tl.x..br.x -> Grip.B
                            p.x in tl.x..br.x && p.y in tl.y..br.y -> Grip.MOVE
                            else -> null
                        } ?: return@awaitEachGesture
                        cropDragging = true
                        val start = toImg(p); val c0 = c; val minS = .15f
                        while (true) {
                            val e = awaitPointerEvent(); val ch = e.changes.first()
                            if (!ch.pressed) break
                            ch.consume()
                            val (x, y) = toImg(ch.position)
                            val dx = x - start.first; val dy = y - start.second
                            var l = c0.x; var t = c0.y; var r = c0.x + c0.w; var b = c0.y + c0.h
                            if (g == Grip.MOVE) {
                                l = (c0.x + dx).coerceIn(0f, 1f - c0.w); t = (c0.y + dy).coerceIn(0f, 1f - c0.h); r = l + c0.w; b = t + c0.h
                            } else {
                                if (g == Grip.TL || g == Grip.BL || g == Grip.L) l = (c0.x + dx).coerceIn(0f, r - minS)
                                if (g == Grip.TR || g == Grip.BR || g == Grip.R) r = (c0.x + c0.w + dx).coerceIn(l + minS, 1f)
                                if (g == Grip.TL || g == Grip.TR || g == Grip.T) t = (c0.y + dy).coerceIn(0f, b - minS)
                                if (g == Grip.BL || g == Grip.BR || g == Grip.B) b = (c0.y + c0.h + dy).coerceIn(t + minS, 1f)
                            }
                            crop = Attachments.Crop(l, t, r - l, b - t)
                        }
                        cropDragging = false
                    }
                    "circle", "arrow" -> detectDragGestures(
                        onDragStart = { draft = listOf(it) },
                        onDrag = { c, _ -> draft = draft + c.position },
                        onDragEnd = {
                            val pts = draft.map { toImg(it) }
                            if (tool == "circle" && pts.size > 3) marks += Attachments.Mark.Circle(pts)
                            if (tool == "arrow" && pts.size > 1) marks += Attachments.Mark.Arrow(pts.first(), pts.last())
                            draft = emptyList()
                        },
                    )
                }
            }) {
                val sw = 5.dp.toPx()
                fun arrow(a: Offset, b: Offset) {
                    drawLine(red, a, b, sw, cap = StrokeCap.Round)
                    val ang = atan2(b.y - a.y, b.x - a.x); val l = 16.dp.toPx()
                    drawLine(red, b, Offset(b.x - l * cos(ang - .5f), b.y - l * sin(ang - .5f)), sw, cap = StrokeCap.Round)
                    drawLine(red, b, Offset(b.x - l * cos(ang + .5f), b.y - l * sin(ang + .5f)), sw, cap = StrokeCap.Round)
                }
                for (m in marks) when (m) {
                    is Attachments.Mark.Circle -> drawPath(Path().apply { m.points.forEachIndexed { i, (x, y) -> val o = toScr(x, y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } },
                        red, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    is Attachments.Mark.Arrow -> arrow(toScr(m.from.first, m.from.second), toScr(m.to.first, m.to.second))
                }
                if (draft.size > 1) when (tool) {
                    "arrow" -> arrow(draft.first(), draft.last())
                    "circle" -> drawPath(Path().apply { draft.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }, red, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                crop?.let { c -> drawCrop(toScr(c.x, c.y), toScr(c.x + c.w, c.y + c.h), editing = tool == "crop", grid = cropDragging) }
            }

            // Поверх видоискателя — только то, что относится к кадру: переснять, отменить пометку, подсказка, вопрос.
            val top = if (full) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier
            Row(top.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (full) { CBtn(Ic.down, stringResource(R.string.cam_collapse), onCollapse); Spacer(Modifier.width(8.dp)) }
                if (frozen != null && tool != "crop") CBtn(Ic.x, stringResource(R.string.cam_retake)) { live() }
                Spacer(Modifier.weight(1f))
                if (frozen != null && tool != "crop" && (marks.isNotEmpty() || crop != null))
                    CBtn(Ic.undo, stringResource(R.string.cam_undo)) { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) else crop = null }
            }
            tool?.let { t ->
                Text(stringResource(when (t) { "crop" -> R.string.cam_hint_crop; "circle" -> R.string.cam_hint_circle; else -> R.string.cam_hint_arrow }),
                    color = white, fontSize = 13.5.sp, style = shadow,
                    modifier = top.align(Alignment.TopCenter).padding(top = if (tool == "crop") 4.dp else 20.dp))
            }
            if (frozen == null) error?.let { Text(it, color = white, fontSize = 15.sp, style = shadow, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
            if (listening || asking || question.isNotEmpty())
                Text(if (listening) stringResource(R.string.ui_listening) else if (asking) stringResource(R.string.cam_recognizing) else question,
                    color = white, fontSize = 19.sp, fontWeight = FontWeight.Medium, style = shadow,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 18.dp))
        }

        // ── на бумаге листа: кадры, инструменты, «＋» · «Отправить» · микрофон ──
        val onDark = full
        val fg = if (onDark) white else Ink.ink
        Column(Modifier.fillMaxWidth().then(if (full) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (frames.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                frames.forEachIndexed { i, f ->
                    Box {
                        val ratio = f.bitmap.width.toFloat() / f.bitmap.height
                        Image(f.bitmap.asImageBitmap(), stringResource(R.string.cam_frame_edit, i + 1),
                            // В пропорциях видоискателя (в листе он шире, чем на весь экран) и целиком, а не кусок.
                            Modifier.height(56.dp).width((56 * ratio).coerceIn(24f, 112f).dp).clip(RoundedCornerShape(10.dp))
                                .border(2.dp, if (editIdx == i) Ink.local else Ink.line, RoundedCornerShape(10.dp))
                                .clickable {
                                    if (frozen != null && editIdx == null) commit()
                                    frozen = f.bitmap; editIdx = i; tool = null; marks.clear(); marks += f.marks; crop = f.crop
                                }, contentScale = ContentScale.Fit)
                        Box(Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(22.dp).clip(CircleShape).background(Ink.ink).clickable {
                            frames.removeAt(i); if (editIdx == i) live() else editIdx?.let { if (it > i) editIdx = it - 1 }
                        }, contentAlignment = Alignment.Center) { Icon(painterResource(Ic.x), stringResource(R.string.cam_frame_delete, i + 1), Modifier.size(14.dp), tint = white) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                if (tool == "crop") {
                    ToolChip(stringResource(R.string.cam_reset), null, false, onDark) { crop = Attachments.Crop(0f, 0f, 1f, 1f) }
                    ToolChip(stringResource(R.string.ui_done), null, true, onDark) { tool = null }
                } else listOf(Triple("crop", R.string.cam_crop, Ic.crop), Triple("circle", R.string.cam_circle, Ic.circle), Triple("arrow", R.string.cam_arrow, Ic.arrow)).forEach { (k, t, ic) ->
                    ToolChip(stringResource(t), ic, tool == k, onDark) { pick(k) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                val canAdd = editIdx != null || frames.size < maxImages - 1
                // В листе кнопки ряда меньше («Отправить» 64, «＋» и микрофон 52) — вкладки режимов под рядом не наезжают; на весь экран — 72 и 56.
                val big = if (full) 72.dp else 64.dp; val small = if (full) 56.dp else 52.dp
                Round(Ic.plus, stringResource(R.string.cam_add), enabled = canAdd, onDark = onDark, size = small) { if (commit()) live() }
                val count = frames.size + if (editIdx == null) 1 else 0
                Box {
                    Box(Modifier.size(big).clip(CircleShape).background(if (onDark) white else Ink.ink)
                        .clickable(enabled = !busy, onClickLabel = stringResource(if (frames.isEmpty()) R.string.ui_send else R.string.cam_send_all)) { send(question) },
                        contentAlignment = Alignment.Center) {
                        if (busy) Text("…", fontSize = 26.sp, color = if (onDark) Ink.ink else white)
                        else Icon(painterResource(Ic.send), null, Modifier.size(28.dp), tint = if (onDark) Ink.ink else white)
                    }
                    if (count > 1) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).heightIn(min = 24.dp).widthIn(min = 24.dp).clip(RoundedCornerShape(12.dp))
                        .background(Ink.local).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) { Text("$count", fontSize = 13.sp, color = Ink.ink, fontWeight = FontWeight.SemiBold) }
                }
                // Микрофон: зажать и спросить голосом — вопрос уйдёт вместе со снимком.
                Box(Modifier.size(small).clip(CircleShape).background(if (listening) Ink.local else if (onDark) Color(0x2EFFFFFF) else Ink.chipBg)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            if (!voice.hasMic() || !voice.installed.value) { error = if (!voice.hasMic()) errNeedMic else errNeedVoice; waitForUpOrCancellation(); return@awaitEachGesture }
                            listening = voice.start()
                            waitForUpOrCancellation()
                            if (!listening) return@awaitEachGesture
                            listening = false; asking = true
                            scope.launch {
                                val r = voice.stop(); asking = false
                                if (r is ru.poneslas.vahter.voice.Voice.Result.Text) { question = r.text; send(r.text) }
                            }
                        }
                    }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(Ic.mic), stringResource(R.string.cam_ask_voice), Modifier.size(24.dp), tint = fg)
                }
            }
        }
    }
}

/** Рамка обрезки: затемнение вне её, белая рамка; при правке — кругляшки в углах, чёрточки на сторонах, сетка 3×3 при перетаскивании. */
private fun DrawScope.drawCrop(tl: Offset, br: Offset, editing: Boolean, grid: Boolean) {
    val dim = Color(0x99000000)
    drawRect(dim, Offset.Zero, Size(size.width, tl.y))
    drawRect(dim, Offset(0f, br.y), Size(size.width, size.height - br.y))
    drawRect(dim, Offset(0f, tl.y), Size(tl.x, br.y - tl.y))
    drawRect(dim, Offset(br.x, tl.y), Size(size.width - br.x, br.y - tl.y))
    drawRect(Color.White, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(2.dp.toPx()))
    if (!editing) return
    if (grid) for (i in 1..2) {
        val x = tl.x + (br.x - tl.x) * i / 3; val y = tl.y + (br.y - tl.y) * i / 3
        drawLine(Color(0x99FFFFFF), Offset(x, tl.y), Offset(x, br.y), 1.dp.toPx())
        drawLine(Color(0x99FFFFFF), Offset(tl.x, y), Offset(br.x, y), 1.dp.toPx())
    }
    val r = 10.dp.toPx()
    listOf(tl, Offset(br.x, tl.y), Offset(tl.x, br.y), br).forEach { drawCircle(Color.White, r, it); drawCircle(Ink.ink, r, it, style = Stroke(2.dp.toPx())) }
    val bar = 22.dp.toPx(); val th = 5.dp.toPx()
    val cx = (tl.x + br.x) / 2; val cy = (tl.y + br.y) / 2
    drawLine(Color.White, Offset(cx - bar / 2, tl.y), Offset(cx + bar / 2, tl.y), th, cap = StrokeCap.Round)
    drawLine(Color.White, Offset(cx - bar / 2, br.y), Offset(cx + bar / 2, br.y), th, cap = StrokeCap.Round)
    drawLine(Color.White, Offset(tl.x, cy - bar / 2), Offset(tl.x, cy + bar / 2), th, cap = StrokeCap.Round)
    drawLine(Color.White, Offset(br.x, cy - bar / 2), Offset(br.x, cy + bar / 2), th, cap = StrokeCap.Round)
}

/** Инструмент: «таблетка» как .mchip; выбранный — тёмный. На тёмном (весь экран) — инверсия. */
@Composable
private fun ToolChip(label: String, icon: Int?, on: Boolean, onDark: Boolean, onClick: () -> Unit) {
    val bg = when { on && onDark -> Color.White; on -> Ink.ink; onDark -> Color(0x80000000); else -> Ink.surf }
    val fg = when { on && onDark -> Color(0xFF111111); on -> Color.White; onDark -> Color.White; else -> Ink.ink }
    Row(Modifier.clip(RoundedCornerShape(50)).background(bg).then(if (!on && !onDark) Modifier.border(1.dp, Ink.line, RoundedCornerShape(50)) else Modifier)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(painterResource(icon), null, Modifier.size(18.dp), tint = fg); Spacer(Modifier.width(6.dp)) }
        Text(label, fontSize = 13.5.sp, color = fg)
    }
}

@Composable
private fun Round(icon: Int, label: String, enabled: Boolean, onDark: Boolean, size: androidx.compose.ui.unit.Dp = 56.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(if (onDark) Color(0x2EFFFFFF) else Ink.chipBg)
        .clickable(enabled = enabled, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), label, Modifier.size(24.dp), tint = (if (onDark) Color.White else Ink.ink).copy(alpha = if (enabled) 1f else .35f))
    }
}

/** Круглая кнопка на видоискателе: чёрный 45 %. */
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

/** Только отладочная сборка: у эмулятора камера без картинки — тестовый кадр, чтобы проверить обрезку и пометки. */
private fun testFrame(ctx: android.content.Context, w: Int, h: Int): Bitmap? {
    if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 || w <= 0 || h <= 0) return null
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF36506B.toInt(), 0xFFE8A317.toInt(), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = null; p.color = 0xFFFFFFFF.toInt(); p.textSize = w / 12f; p.textAlign = android.graphics.Paint.Align.CENTER
    c.drawText(tr(R.string.cam_test_frame), w / 2f, h / 2f, p)
    return b
}
