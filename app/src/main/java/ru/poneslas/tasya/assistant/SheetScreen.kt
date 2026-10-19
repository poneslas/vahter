package ru.poneslas.tasya.assistant

import ru.poneslas.tasya.R
import ru.poneslas.tasya.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import ru.poneslas.tasya.ui.Ic
import ru.poneslas.tasya.app.IconBtn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.poneslas.tasya.App
import ru.poneslas.tasya.ui.Composer
import ru.poneslas.tasya.ui.Feed
import ru.poneslas.tasya.ui.Ink
import ru.poneslas.tasya.ui.LiveLine
import ru.poneslas.tasya.ui.TasyaTheme

/** Лист поверх любого приложения: шапка (режим · связь, развернуть, настройки, закрыть), лента, живая строка, строка ввода. */
@Composable
fun SheetScreen(onClose: () -> Unit, onExpand: () -> Unit, onSettings: () -> Unit, onNeedPerm: (String) -> Unit, session: AssistSession, onAddMode: () -> Unit = onSettings) {
    val app = App.instance
    val state by app.conversation.state.collectAsState()
    var live by remember { mutableStateOf<String?>(null) }
    val camera by Sheet.camera.collectAsState()
    fun openCamera() {
        if (session.context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) onNeedPerm(android.Manifest.permission.CAMERA)
        else Sheet.camera.value = true
    }
    var camFull by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(camera) { if (!camera) camFull = false }
    // Переключили режим — камера закрывается, остаётся обычный лист.
    // Смена вкладки: камера остаётся только если новый режим — фото-режим (там камера — главное действие).
    var lastMode by remember { mutableStateOf(state.mode) }
    androidx.compose.runtime.LaunchedEffect(state.mode) {
        if (state.mode != lastMode) {
            lastMode = state.mode
            val c = state.current
            Sheet.camera.value = Sheet.camera.value && c.primary == ru.poneslas.tasya.core.Primary.PHOTO && c.accepts.photo
        }
    }
    val cameraPanel: @Composable (Modifier, Boolean) -> Unit = { m, full ->
        ru.poneslas.tasya.media.CameraPanel(session, state.current, ru.poneslas.tasya.core.Brains.byId(state.current.brain).maxImages, full,
            onClose = { Sheet.camera.value = false }, onCollapse = { camFull = false },
            onSend = { files, question -> Sheet.camera.value = false; app.conversation.attach(files, if (files.size > 1) tr(R.string.sheet_albom_1_s, files.size) else tr(R.string.sheet_foto)); if (question.isNotBlank()) app.conversation.send(question, tr(R.string.sheet_golos)) },
            modifier = m)
    }
    TasyaTheme {
        if (camera && camFull) { cameraPanel(Modifier.fillMaxSize(), true); return@TasyaTheme }
        // Баг №16. Окно помощника система при клавиатуре сдвигает вверх (прокрутка к полю) — отключить нельзя.
        // Поэтому отступ снизу = высота клавиатуры минус уже сделанный сдвиг; без этого лист уезжал выше клавиатуры.
        val d = androidx.compose.ui.platform.LocalDensity.current
        val view = androidx.compose.ui.platform.LocalView.current
        val imePx = imeBottom(d)
        var pan by remember { mutableStateOf(0) }
        androidx.compose.runtime.LaunchedEffect(imePx) { val loc = IntArray(2); view.getLocationOnScreen(loc); pan = (-loc[1]).coerceAtLeast(0) }
        val navPx = navBottom(d)
        // С клавиатурой полоска навигации под ней — её отступ не нужен.
        val bottomPad = with(d) { (if (imePx > 0) (imePx - pan).coerceAtLeast(0) else navPx).toDp() }
        val brain = ru.poneslas.tasya.core.Brains.byId(state.current.brain)
        val keyV by app.keyVersion.collectAsState()
        val connected = remember(keyV, brain.id) { app.hasKey(brain.id) }
        // .ov .dim — затемнение rgba(14,26,54,.32), лист не закрывает
        Box(Modifier.fillMaxSize().background(Color(0x520E1A36)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // .sheet: на всю ширину, скруглены только верхние углы (как нижние панели Android и One UI), бумага до низа экрана;
            // отступ под полоску жестов и клавиатуру — внутри листа.
            Column(
                Modifier.fillMaxWidth()
                    .then(if (camera) Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top)).padding(top = 40.dp).fillMaxHeight() else Modifier)
                    .shadow(24.dp, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp), ambientColor = Color(0x660E1A36), spotColor = Color(0x660E1A36))
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(Ink.paper)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(top = 8.dp, bottom = bottomPad),
            ) {
                // .grab 36×4 и шапка: потянуть вниз — закрыть лист, вверх — развернуть в приложение (баг №5)
                val density = androidx.compose.ui.platform.LocalDensity.current
                var drag by remember { mutableStateOf(0f) }
                val dragArea = Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onVerticalDrag = { ch, dy -> ch.consume(); drag += dy },
                        onDragEnd = {
                            val px = with(density) { 48.dp.toPx() }
                            if (drag > px) onClose() else if (drag < -px) onExpand()
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                    )
                }
                Column(dragArea.graphicsLayer { translationY = drag.coerceAtLeast(0f) * 0.4f }) {
                    Box(Modifier.fillMaxWidth().height(20.dp).clickable(onClickLabel = tr(R.string.sheet_razvernut), onClick = onExpand), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.grab))
                    }
                // .hdr: точка · «Режим · на связи» 12 · ⤢ ⚙ ✕ (36, приглушённые)
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Ink.server else Ink.local))
                    Spacer(Modifier.width(6.dp))
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = Ink.ink, fontWeight = FontWeight.SemiBold)) { append(state.current.title) }
                        append(if (connected) tr(R.string.sheet_na_svyazi) else tr(R.string.sheet_net_klyucha_tolko_komandy_telefona))
                    }, fontSize = 12.sp, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (camera) {
                        IconBtn(Ic.expand, androidx.compose.ui.res.stringResource(ru.poneslas.tasya.R.string.cam_fullscreen), size = 36.dp, tint = Ink.soft) { camFull = true }
                        IconBtn(Ic.x, androidx.compose.ui.res.stringResource(ru.poneslas.tasya.R.string.cam_close), size = 36.dp, tint = Ink.soft) { Sheet.camera.value = false }
                    } else {
                        IconBtn(Ic.expand, tr(R.string.sheet_razvernut_v_prilozhenie), size = 36.dp, tint = Ink.soft, onClick = onExpand)
                        IconBtn(Ic.gear, tr(R.string.sheet_nastroyki), size = 36.dp, tint = Ink.soft, onClick = onSettings)
                        IconBtn(Ic.x, tr(R.string.sheet_zakryt), size = 36.dp, tint = Ink.soft, onClick = onClose)
                    }
                }
                }
                if (camera) {
                    cameraPanel(Modifier.weight(1f).padding(top = 6.dp), false)
                    ru.poneslas.tasya.ui.ModeTabs(state, app.conversation::selectMode, onAddMode)
                } else {
                // Волна записи — поверх низа ленты, а не отдельной строкой: высота листа при записи не меняется.
                Box(Modifier.fillMaxWidth()) {
                    Feed(state, onAct = { a, undo -> app.conversation.onActButton(a, undo) }, onCard = { c, a -> app.conversation.onCardButton(c, a) }, onCancelAttach = app.conversation::cancelAttach,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 420.dp))
                    ru.poneslas.tasya.ui.RecPill(Modifier.align(Alignment.BottomCenter))
                }
                LiveLine(live, pill = false)
                Composer(
                    state = state,
                    voice = app.voice,
                    onDraft = app.conversation::setDraft,
                    onSend = { text, via -> app.conversation.send(text, via) },
                    onSelectMode = app.conversation::selectMode,
                    onAddMode = onAddMode,
                    onLive = { live = it },
                    autoListen = Sheet.autoListen,
                    onNeedMic = { onNeedPerm(android.Manifest.permission.RECORD_AUDIO) },
                    onCamera = ::openCamera,
                    onGallery = { ru.poneslas.tasya.media.PickerActivity.launchFromSheet(session) },
                )
                }
            }
        }
    }
}


@Composable private fun imeBottom(d: androidx.compose.ui.unit.Density) = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(d)
@Composable private fun navBottom(d: androidx.compose.ui.unit.Density) = androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(d)
