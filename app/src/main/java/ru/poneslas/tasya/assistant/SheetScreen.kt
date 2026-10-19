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
                onSend = { files -> Sheet.camera.value = false; app.conversation.attach(files, if (files.size > 1) "альбом · ${files.size}" else "фото") })
            return@TasyaTheme
        }
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null) { /* затемнение лист не закрывает */ },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding().imePadding()
                    .clip(RoundedCornerShape(30.dp)).background(Ink.paper)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(top = 8.dp),
            ) {
                Box(Modifier.fillMaxWidth().clickable(onClick = onExpand), contentAlignment = Alignment.Center) {
                    Box(Modifier.padding(vertical = 4.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Ink.line))
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Ink.server))
                    Text("  ${state.current.title}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                    Text(" · на связи", fontSize = 13.sp, color = Ink.soft)
                    Spacer(Modifier.weight(1f))
                    HeaderButton("⤢", "Развернуть", onExpand)
                    HeaderButton("⚙", "Настройки", onSettings)
                    HeaderButton("✕", "Закрыть", onClose)
                }
                Feed(state, onAct = { app.conversation.onActButton(it) }, onCard = { c, a -> app.conversation.onCardButton(c, a) }, onCancelAttach = app.conversation::cancelAttach, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 420.dp))
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

@Composable
private fun HeaderButton(glyph: String, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 18.sp, color = Ink.soft)
    }
}
