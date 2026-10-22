package ru.poneslas.vahter.app

import ru.poneslas.vahter.ui.UpLine
import ru.poneslas.vahter.media.PickerActivity
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Spacer
import ru.poneslas.vahter.ui.Feed
import androidx.compose.foundation.shape.RoundedCornerShape
import ru.poneslas.vahter.R
import ru.poneslas.vahter.ui.Composer
import ru.poneslas.vahter.ui.LiveLine
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ru.poneslas.vahter.tr
import ru.poneslas.vahter.App
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ru.poneslas.vahter.assistant.Sheet
import ru.poneslas.vahter.ui.Ink
import ru.poneslas.vahter.ui.Ic
import androidx.compose.runtime.mutableStateOf
import ru.poneslas.vahter.core.Brains
import ru.poneslas.vahter.ui.Banner
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import ru.poneslas.vahter.ui.PromptStrip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import ru.poneslas.vahter.ui.TopBar
import ru.poneslas.vahter.ui.IconBtn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Главный экран приложения — тот же разговор, что в листе, на весь экран (ui.js, chat). */

@Composable
fun ColumnScope.Chat(a: MainActivity) {
    val app = App.instance
    val state by app.conversation.state.collectAsState()
    var live by remember { mutableStateOf<String?>(null) }
    val brain = Brains.byId(state.current.brain)
    val keyV by app.keyVersion.collectAsState()
    val connected = remember(keyV, brain.id) { app.hasKey(brain.id) }
    // chat(): [свернуть] заголовок режима, точка связи · «DeepSeek · на связи», шестерёнка
    TopBar(state.current.title, tr(R.string.set_link_status, brain.title, tr(if (connected) R.string.set_link_on else R.string.set_link_off)),
        onBack = if (a.fromSheet) ({ a.collapse() }) else null, backIcon = Ic.down, dot = if (connected) Ink.local else Ink.faint) {
        IconBtn(Ic.gear, tr(R.string.ui_settings)) { a.go("settings") }
        // ⋮ — меню разговора (ui.js, chatMenu): изменить режим, начать заново (неактивно, пока разговор пуст).
        Box {
            var menu by remember { mutableStateOf(false) }
            IconBtn(Ic.more, tr(R.string.restart_more)) { menu = true }
            DropdownMenu(menu, { menu = false }, containerColor = Ink.surf,
                shape = RoundedCornerShape(16.dp)) {
                @Composable fun row(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
                    text = { Text(label, fontSize = 15.sp, color = if (enabled) Ink.ink else Ink.faint) },
                    leadingIcon = { Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (enabled) Ink.soft else Ink.faint) },
                    enabled = enabled, onClick = { menu = false; onClick() })
                row(Ic.edit, tr(R.string.mode_edit)) { a.go("mode:${state.mode}") }
                row(Ic.hist, tr(R.string.ui_restart), enabled = state.feed.isNotEmpty()) { app.conversation.askRestart() }
            }
        }
    }
    if (!connected) Banner(tr(R.string.set_no_brain_banner),
        actions = listOf(tr(R.string.ui_connect) to { a.go("brain:${brain.id}") }))
    PromptStrip(state.current, onEdit = { a.go("mode:$it") })
    Feed(state, onAct = { a, undo -> app.conversation.onActButton(a, undo) }, onCard = { c, a -> app.conversation.onCardButton(c, a) }, onCancelAttach = app.conversation::cancelAttach,
        modifier = Modifier.weight(1f).fillMaxWidth(), inSheet = false, onChip = { app.conversation.send(it, tr(R.string.cmp_via_keyboard)) },
        from = state.saved.fresh[state.mode] ?: 0)
    UpLine(state)
    LiveLine(live)
    Composer(
        state = state, voice = app.voice,
        placeholder = ru.poneslas.vahter.ui.composerPlaceholder(state, connected),
        onDraft = app.conversation::setDraft,
        onSend = { t, v -> app.conversation.send(t, v) },
        onSelectMode = app.conversation::selectMode,
        onAddMode = { a.go("mode:" + app.conversation.addMode()) },
        onLive = { live = it },
        onNeedMic = a::askMic,
        onGallery = { PickerActivity.launch(a) },
        onFile = if (state.current.brain == ru.poneslas.vahter.core.Brains.hermes.id && app.hermesLink() != null) ({ PickerActivity.launchFile(a) }) else null,
    )
    Spacer(Modifier.navigationBarsPadding())
}
