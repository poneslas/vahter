package ru.poneslas.tasya.assistant

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
    TasyaTheme {
        if (camera) {
            ru.poneslas.tasya.media.CameraScreen(session, state.current, ru.poneslas.tasya.core.Brains.byId(state.current.brain).maxImages,
                onClose = { Sheet.camera.value = false },
                onSend = { files, question -> Sheet.camera.value = false; app.conversation.attach(files, if (files.size > 1) "альбом · ${files.size}" else "фото"); if (question.isNotBlank()) app.conversation.send(question, "голос") })
            return@TasyaTheme
        }
        val brain = ru.poneslas.tasya.core.Brains.byId(state.current.brain)
        val keyV by app.keyVersion.collectAsState()
        val connected = remember(keyV, brain.id) { app.hasKey(brain.id) }
        // .ov .dim — затемнение rgba(14,26,54,.32), лист не закрывает
        Box(Modifier.fillMaxSize().background(Color(0x520E1A36)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // .sheet: отступ 8 от краёв, скругление 30, бумага, поля 8 сверху / 12 снизу
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp)
                    .shadow(24.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x660E1A36), spotColor = Color(0x660E1A36))
                    .clip(RoundedCornerShape(30.dp)).background(Ink.paper)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(top = 8.dp, bottom = 0.dp),
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
                    Box(Modifier.fillMaxWidth().height(20.dp).clickable(onClickLabel = "Развернуть", onClick = onExpand), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.grab))
                    }
                // .hdr: точка · «Режим · на связи» 12 · ⤢ ⚙ ✕ (36, приглушённые)
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Ink.server else Ink.local))
                    Spacer(Modifier.width(6.dp))
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = Ink.ink, fontWeight = FontWeight.SemiBold)) { append(state.current.title) }
                        append(if (connected) " · на связи" else " · нет ключа · только команды телефона")
                    }, fontSize = 12.sp, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconBtn(Ic.expand, "Развернуть в приложение", size = 36.dp, tint = Ink.soft, onClick = onExpand)
                    IconBtn(Ic.gear, "Настройки", size = 36.dp, tint = Ink.soft, onClick = onSettings)
                    IconBtn(Ic.x, "Закрыть", size = 36.dp, tint = Ink.soft, onClick = onClose)
                }
                }
                Feed(state, onAct = { a, undo -> app.conversation.onActButton(a, undo) }, onCard = { c, a -> app.conversation.onCardButton(c, a) }, onCancelAttach = app.conversation::cancelAttach,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 420.dp))
                LiveLine(live)
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

