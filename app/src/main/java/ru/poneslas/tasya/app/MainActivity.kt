package ru.poneslas.tasya.app

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.poneslas.tasya.App
import ru.poneslas.tasya.assistant.Sheet
import ru.poneslas.tasya.ui.Composer
import ru.poneslas.tasya.ui.Feed
import ru.poneslas.tasya.ui.Ink
import ru.poneslas.tasya.ui.LiveLine
import ru.poneslas.tasya.ui.TasyaTheme

/** Приложение — тот же разговор, что в листе, на весь экран; плюс настройки (Продукт 5.1, 5.11). */
class MainActivity : ComponentActivity() {
    private var fromSheet by mutableStateOf(false)
    private var page by mutableStateOf(Page.CHAT)

    enum class Page { CHAT, SETTINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        setContent { TasyaTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent) {
        fromSheet = i.getBooleanExtra(EXTRA_FROM_SHEET, false)
        if (i.getBooleanExtra(EXTRA_SETTINGS, false)) page = Page.SETTINGS
    }

    /** Свернуть обратно в лист: finish + showSession (Архитектура 3.1). */
    private fun collapse() {
        finish()
        Sheet.show()
    }

    @Composable
    private fun Screen() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        var live by remember { mutableStateOf<String?>(null) }
        BackHandler(page == Page.SETTINGS) { page = Page.CHAT }
        Column(Modifier.fillMaxSize().background(Ink.paper).systemBarsPadding().imePadding()) {
            when (page) {
                Page.CHAT -> {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (fromSheet) IconText("⌄", "Свернуть в лист") { collapse() }
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(state.current.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                            Text("DeepSeek · на связи", fontSize = 12.sp, color = Ink.soft)
                        }
                        IconText("⚙", "Настройки") { page = Page.SETTINGS }
                    }
                    Feed(state, onAct = { app.conversation.onActButton(it) }, modifier = Modifier.weight(1f).fillMaxWidth())
                    LiveLine(live)
                    Composer(
                        state = state, recognizer = app.recognizer,
                        onDraft = app.conversation::setDraft,
                        onSend = { t, v -> app.conversation.send(t, v) },
                        onSelectMode = app.conversation::selectMode,
                        onAddMode = { page = Page.SETTINGS },
                        onLive = { live = it },
                    )
                }
                Page.SETTINGS -> SettingsPage(onBack = { page = Page.CHAT })
            }
        }
    }

    @Composable
    private fun SettingsPage(onBack: () -> Unit) {
        val roleHeld = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconText("←", "Назад", onBack)
            Text("Настройки", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(start = 10.dp))
        }
        Section("Вызов")
        Item("Цифровой помощник", if (roleHeld) "Тася" else "Не выбран — боковая кнопка откроет другого помощника", warn = !roleHeld) {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }
        Section("О программе")
        Item("Версия", packageManager.getPackageInfo(packageName, 0).versionName ?: "") {}
    }

    @Composable
    private fun Section(title: String) =
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.server, modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 6.dp))

    @Composable
    private fun Item(title: String, subtitle: String, warn: Boolean = false, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text(title, fontSize = 16.sp, color = Ink.ink)
            Text(subtitle, fontSize = 13.sp, color = if (warn) Ink.danger else Ink.soft)
        }
    }

    @Composable
    private fun IconText(glyph: String, label: String, onClick: () -> Unit) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
            Text(glyph, fontSize = 20.sp, color = Ink.ink)
        }
    }

    companion object {
        const val EXTRA_FROM_SHEET = "from_sheet"
        const val EXTRA_SETTINGS = "settings"
    }
}
