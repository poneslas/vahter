package ru.poneslas.tasya.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.poneslas.tasya.App
import ru.poneslas.tasya.core.Brains
import ru.poneslas.tasya.voice.ModelFiles
import ru.poneslas.tasya.voice.Models
import ru.poneslas.tasya.ui.Ink
import android.app.role.RoleManager

/** Распознавание: скачать (≈240 МБ, с докачкой), прогрев, удалить. */
@Composable
fun VoicePage(onBack: () -> Unit) {
    val app = App.instance
    TopBar("Распознавание речи", "GigaAM v3 на телефоне — голос не уходит наружу", onBack = onBack)
    ScrollPage { VoiceBlock() }
}

@Composable
fun VoiceBlock() {
    val app = App.instance
    val status by app.models.status.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(status) { if (status == Models.Status.Ready) { app.warmUp(); while (!app.sherpa.available) { delay(400); tick++ } ; tick++ } }
    val mb = ModelFiles.totalBytes / 1_048_576
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val s = status) {
            Models.Status.None -> {
                Text("Один раз скачать $mb МБ. Голос распознаётся на телефоне и работает без интернета.", fontSize = 15.sp, color = Ink.soft)
                if (app.models.isMetered()) Note("Сейчас мобильная сеть. Можно скачать и так или подождать Wi-Fi — загрузка продолжится с того же места.")
                PrimaryButton("Скачать · $mb МБ") { app.models.start() }
            }
            is Models.Status.Downloading -> {
                Text(if (s.waitingNetwork) "Ждёт сеть — продолжит с того же места: ${s.percent} %" else "Качается: ${s.percent} % из $mb МБ", fontSize = 15.sp, color = Ink.ink)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ink.line)) {
                    Box(Modifier.fillMaxWidth(s.percent / 100f).height(6.dp).background(Ink.local))
                }
                Text("Можно закрыть экран — докачается, пока приложение живо, иначе продолжит при следующем открытии.", fontSize = 13.sp, color = Ink.soft)
                TextButton("Остановить") { app.models.cancel() }
            }
            Models.Status.Verifying -> Text("Проверяю файл…", fontSize = 15.sp, color = Ink.ink)
            Models.Status.Ready -> {
                Note(if (app.sherpa.available) "Готово: распознавание на телефоне, $mb МБ." else "Скачано. Прогреваю — несколько секунд…", ok = app.sherpa.available.takeIf { it })
                TextButton("Удалить модель") { app.models.delete() }
            }
            is Models.Status.Failed -> {
                Note(s.message, ok = false)
                PrimaryButton("Скачать заново") { app.models.start() }
            }
        }
        @Suppress("UNUSED_EXPRESSION") tick
    }
}

/** Страница режима: что делает, что принимает, мозг, включён ли, «Начать заново» (Продукт 5.17). */
@Composable
fun ModePage(activity: MainActivity, id: String) {
    val app = App.instance
    val state by app.conversation.state.collectAsState()
    val m = state.modes.firstOrNull { it.id == id } ?: return
    TopBar(m.title, if (m.builtin) "встроенный режим" else "свой режим · id ${m.id}", onBack = activity::back)
    ScrollPage {
        Section("Что делать")
        Text(if (m.id == "main") "Основной разговор: без своего промпта, все действия телефона." else m.prompt,
            fontSize = 15.sp, color = Ink.ink, modifier = Modifier.padding(horizontal = 22.dp))
        Section("Принимает")
        Item(listOfNotNull("голос".takeIf { m.accepts.voice }, "текст", "фото".takeIf { m.accepts.photo }, "файл".takeIf { m.accepts.file }).joinToString(", "))
        if (m.id != "main") SwitchItem("Режим включён", "Показывается во вкладках", m.enabled) {
            app.conversation.updateModes { list -> list.map { if (it.id == m.id) it.copy(enabled = !it.enabled) else it } }
        }
        Section("Мозг")
        Item(Brains.byId(m.brain).title, "История режима хранится на телефоне и целиком уходит мозгу с каждой репликой.")
        val n = state.saved.feeds[m.id].orEmpty().size
        Item("Начать заново", if (n > 0) "Стереть разговор режима — $n сообщ." else "Разговор пуст") { if (n > 0) app.conversation.restart(m.id) }
    }
}

/**
 * Первый запуск (Продукт 5.13): привет → боковая кнопка → голос (+ микрофон) → мозг (ключ) → готово.
 * Ничего не блокирует: пропущенное потом напомнит баннер.
 */
@Composable
fun Onboarding(activity: MainActivity, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val app = App.instance
    val keyV by app.keyVersion.collectAsState()
    val steps = 5
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(24.dp))
            when (step) {
                0 -> {
                    Title("Зажал кнопку — сказал — готово")
                    Body("Таймеры и будильники телефон ставит сам, даже без интернета. Остальное отвечает модель, которую выберете вы. Голос распознаётся на телефоне.")
                }
                1 -> {
                    Title("Боковая кнопка")
                    Body("Android разрешает выбрать помощника только в настройках. Откройте оба экрана по очереди.")
                    val role = activity.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
                    Item("Цифровой помощник", if (role) "Готово" else "Выберите «Тася»") { activity.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                    Item("Боковая кнопка", SideKey.describe(activity)) { SideKey.open(activity) }
                }
                2 -> {
                    Title("Голос на телефоне")
                    VoiceBlock()
                    val mic = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (!mic) Item("Микрофон", "Нужен для голосового ввода — разрешите сейчас") { activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
                    else Note("Микрофон разрешён.", ok = true)
                }
                3 -> {
                    Title("Мозг")
                    Body("Отвечать будет DeepSeek — нужен ключ API с platform.deepseek.com.")
                    @Suppress("UNUSED_EXPRESSION") keyV
                    KeyEditor(Brains.deepseek)
                    Body("Свой сервер Hermes — с памятью, поиском и Telegram — можно подключить потом: Настройки → Мозги.")
                }
                else -> {
                    Title("Готово")
                    Body("Зажмите боковую кнопку в любом приложении и скажите: «Таймер на минуту».")
                    val notif = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (!notif) Item("Уведомления", "Чтобы узнать об ответе, если закрыли лист раньше") { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (step < steps - 1) TextButton("Позже") { step++ } else Spacer(Modifier.size(60.dp))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(steps) { i -> Box(Modifier.size(if (i == step) 20.dp else 7.dp, 7.dp).clip(CircleShape).background(if (i == step) Ink.ink else Ink.line)) }
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton(if (step == 0) "Начать" else if (step < steps - 1) "Далее" else "Открыть") { if (step < steps - 1) step++ else onDone() }
        }
    }
}

@Composable private fun Title(t: String) = Text(t, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(horizontal = 22.dp))
@Composable private fun Body(t: String) = Text(t, fontSize = 15.sp, color = Ink.soft, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 22.dp))
