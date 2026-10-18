package ru.poneslas.tasya.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.assist.openAssistantSettings
import ru.poneslas.tasya.probe.core.Config
import ru.poneslas.tasya.probe.core.Header
import ru.poneslas.tasya.probe.core.Human
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.Results
import ru.poneslas.tasya.probe.core.Runner
import ru.poneslas.tasya.probe.core.Status
import ru.poneslas.tasya.probe.core.fmt
import ru.poneslas.tasya.probe.core.median
import ru.poneslas.tasya.probe.guide.Guide
import ru.poneslas.tasya.probe.guide.Part
import ru.poneslas.tasya.probe.ui.BigButton
import ru.poneslas.tasya.probe.ui.ProbeTheme
import ru.poneslas.tasya.probe.ui.PromptBlock
import ru.poneslas.tasya.probe.ui.SectionTitle
import ru.poneslas.tasya.probe.ui.StatusDot
import ru.poneslas.tasya.probe.ui.Tone
import ru.poneslas.tasya.probe.ui.statusColor
import ru.poneslas.tasya.probe.ui.statusWord

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick.intValue++
    }

    /** "home" | "run" | "results" | "all" | "report" | id проверки */
    private val screen = mutableStateOf("home")
    private var backTo = "home"

    private val corePermissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProbeTheme {
                Surface(Modifier.fillMaxSize(), color = Tone.bg) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) { Screens() }
                }
            }
        }
        handle(intent)
        Guide.resumeAfterUpdate(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * Управление с Mac:
     *   adb shell am start -n ru.poneslas.tasya.probe/.MainActivity --es run П18
     *   adb shell am start -n ru.poneslas.tasya.probe/.MainActivity --es answer Готово
     *   adb shell am start -n ru.poneslas.tasya.probe/.MainActivity --ez auto true
     *   adb shell am start -n ru.poneslas.tasya.probe/.MainActivity --es set server=http://127.0.0.1:8765
     */
    private fun handle(i: Intent?) {
        i ?: return
        i.getStringExtra("probe")?.let { screen.value = it }
        i.getStringExtra("run")?.let { id ->
            screen.value = id
            Runner.start(this, Registry.probe(id))
        }
        i.getStringExtra("answer")?.let { Human.answer(it) }
        i.getStringExtra("set")?.split('=', limit = 2)?.takeIf { it.size == 2 }?.let { (k, v) -> Config[k] = v }
        if (i.getBooleanExtra("auto", false)) Runner.startAllAutomatic(this)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("BatteryLife")
    private fun requestBatteryUnrestricted() {
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
    }

    private fun open(id: String, from: String) {
        backTo = from
        screen.value = id
    }

    @Composable
    private fun Screens() {
        val s by screen
        val current by Guide.current.collectAsState()
        // Пока идёт шаг прогона — экран шага; закончился — на главную или к итогу.
        LaunchedEffect(current) {
            if (current != null) screen.value = "run"
            else if (screen.value == "run") screen.value = if (Guide.parts.all { Guide.partDone(it) }) "results" else "home"
        }
        BackHandler(enabled = s != "home" && s != "run") {
            screen.value = if (Registry.probes.any { it.id == s }) backTo else "home"
        }
        when {
            s == "home" -> HomeScreen()
            s == "run" -> RunScreen()
            s == "results" -> ResultsScreen()
            s == "all" -> AllScreen()
            s == "report" -> ReportScreen()
            else -> DetailScreen(Registry.probe(s))
        }
    }

    // ——— Главный экран ———

    @Composable
    private fun HomeScreen() {
        val results by Results.all.collectAsState()
        val tick by resumeTick
        val role = remember(tick) { Header.roleHeld(this) }
        val perms = remember(tick) { corePermissions.all { granted(it) } }
        val battery = remember(tick) { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }
        val ready = role && perms
        results.size // перерисовка при новых итогах

        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Пробник Таси", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Tone.ink)
                    Text("Проверяет на этом телефоне то, на чём держится Тася. Около 25 минут.", fontSize = 15.sp, color = Tone.muted)
                    SectionTitle("Подготовка")
                    SetupRow("Пробник — цифровой помощник", role, "Назначить") { openAssistantSettings(this@MainActivity) }
                    SetupRow("Микрофон, камера, уведомления", perms, "Разрешить") { permissions.launch(corePermissions) }
                    SetupRow("Батарея без ограничений", battery, "Разрешить") { requestBatteryUnrestricted() }
                    SetupRow("Боковая кнопка: удержание → «Цифровой помощник»", null, "Открыть") {
                        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    }
                    Text(
                        "Путь: Дополнительные функции → Боковая кнопка → Нажатие и удержание.",
                        fontSize = 13.sp, color = Tone.muted, modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
                    )
                }
                Guide.parts.forEach { part ->
                    item { SectionTitle("Часть ${part.n}. ${part.title}", "Около ${part.minutes} минут. ${part.note}") }
                    items(part.steps) { st ->
                        StepRow(st.title, Results.get(st.id).status, Guide.plain(st.id)) { open(st.id, "home") }
                    }
                }
                item {
                    Row(Modifier.padding(vertical = 12.dp)) {
                        TextButton({ screen.value = "results" }) { Text("Итог") }
                        TextButton({ screen.value = "all" }) { Text("Все проверки") }
                        TextButton({ screen.value = "report" }) { Text("Отчёт") }
                    }
                }
            }
            Column(Modifier.padding(20.dp)) {
                val p1 = Guide.parts[0]
                val p2 = Guide.parts[1]
                when {
                    !ready -> BigButton("Сначала подготовка", enabled = false) {}
                    !Guide.partDone(p1) -> BigButton(if (Guide.nextIn(p1) == p1.steps.first()) "Начать часть 1" else "Продолжить часть 1") { Guide.start(this@MainActivity, p1) }
                    !Guide.partDone(p2) -> BigButton(if (Guide.nextIn(p2) == p2.steps.first()) "Начать часть 2" else "Продолжить: ${Guide.nextIn(p2)!!.title}") { Guide.start(this@MainActivity, p2) }
                    else -> BigButton("Посмотреть итог") { screen.value = "results" }
                }
            }
        }
    }

    /** Строка подготовки: ок — галочка; нет — кнопка действия. [ok] = null — проверить нельзя, только кнопка. */
    @Composable
    private fun SetupRow(label: String, ok: Boolean?, action: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (ok) { true -> "✓"; else -> "○" },
                color = if (ok == true) Tone.pass else Tone.muted, fontSize = 18.sp, modifier = Modifier.width(28.dp),
            )
            Text(label, Modifier.weight(1f), fontSize = 15.sp, color = Tone.ink)
            if (ok != true) TextButton(onClick) { Text(action) }
        }
    }

    @Composable
    private fun StepRow(title: String, status: Status, summary: String, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status)
                Spacer(Modifier.width(18.dp))
                Text(title, Modifier.weight(1f), fontSize = 16.sp, color = Tone.ink)
                Text(statusWord(status), fontSize = 13.sp, color = statusColor(status))
            }
            if (summary.isNotBlank() && status != Status.NOT_RUN) {
                Text(summary, fontSize = 13.sp, color = Tone.muted, maxLines = 2, modifier = Modifier.padding(start = 28.dp, top = 2.dp))
            }
        }
        HorizontalDivider(color = Tone.line)
    }

    // ——— Экран шага ———

    @Composable
    private fun RunScreen() {
        val current by Guide.current.collectAsState()
        val prompt by Human.prompt.collectAsState()
        val ui by Sheet.ui.collectAsState()
        val step = current ?: return
        val part = Guide.partOf(step.id)
        val i = Guide.indexIn(step.id)
        // Экран не гаснет, пока идёт прогон (кроме шага, где экран гасят нарочно).
        DisposableEffect(step.id) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Часть ${part.n}. ${part.title}", Modifier.weight(1f), fontSize = 14.sp, color = Tone.muted)
                Text("${i + 1} из ${part.steps.size}", fontSize = 14.sp, color = Tone.muted)
            }
            LinearProgressIndicator(
                progress = { (i + 1) / part.steps.size.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = Tone.action, trackColor = Tone.line,
            )
            Spacer(Modifier.height(28.dp))
            Text(step.title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Tone.ink, lineHeight = 36.sp)
            Spacer(Modifier.height(20.dp))
            val p = prompt?.takeIf { it.probeId == step.id }
            if (p != null) {
                PromptBlock(p, { Human.answer(it) })
            } else {
                Text(if (part.n == 1) part.note else "Проверяю…", fontSize = 22.sp, lineHeight = 29.sp, color = Tone.ink)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tone.action, trackColor = Tone.line)
                if (ui.status.isNotBlank()) Text(ui.status, fontSize = 14.sp, color = Tone.muted, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.weight(1f))
            if (part.n == 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    part.steps.forEach { s ->
                        val st = Guide.status(s.id)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (s == step) Status.RUNNING else st, 8)
                            Spacer(Modifier.width(12.dp))
                            Text(s.title, fontSize = 14.sp, color = if (s == step) Tone.ink else Tone.muted)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            TextButton({ Guide.stop() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Остановить", color = Tone.muted) }
        }
    }

    // ——— Итог ———

    @Composable
    private fun ResultsScreen() {
        val results by Results.all.collectAsState()
        results.size
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                item {
                    TextButton({ screen.value = "home" }) { Text("Назад") }
                    Text("Итог", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Tone.ink)
                }
                Guide.parts.forEach { part ->
                    item { SectionTitle("Часть ${part.n}. ${part.title}") }
                    items(part.steps) { st ->
                        StepRow(st.title, Results.get(st.id).status, Guide.plain(st.id)) { open(st.id, "results") }
                    }
                }
            }
            Column(Modifier.padding(20.dp)) { BigButton("Отправить отчёт") { share() } }
        }
    }

    // ——— Все проверки (подробные, для отладки) ———

    @Composable
    private fun AllScreen() {
        val results by Results.all.collectAsState()
        val running by Runner.running.collectAsState()
        var debug by remember { mutableStateOf(Config.flag("debug_buttons")) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                TextButton({ screen.value = "home" }) { Text("Назад") }
                Text("Все проверки", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Tone.ink)
                Text("Подробные проверки для разбора. Для обычного прогона они не нужны.", fontSize = 14.sp, color = Tone.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(debug, { debug = it; Config.setFlag("debug_buttons", it) })
                    Text("Показывать в листе все кнопки проверок", fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ Runner.startAllAutomatic(this@MainActivity) }, enabled = running == null) { Text("Все автоматические") }
                    OutlinedButton({ App.scope.launch { Sheet.open() } }) { Text("Открыть лист") }
                }
                if (running != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Идёт $running", Modifier.weight(1f), color = Tone.action)
                        TextButton({ Runner.cancel() }) { Text("Остановить") }
                    }
                }
                HorizontalDivider(color = Tone.line, modifier = Modifier.padding(top = 8.dp))
            }
            items(Registry.probes) { p ->
                val r = results[p.id]
                StepRow("${p.id}  ${p.title}", r?.status ?: Status.NOT_RUN, r?.summary ?: "") { open(p.id, "all") }
            }
        }
    }

    // ——— Одна проверка ———

    @Composable
    private fun DetailScreen(p: Probe) {
        val results by Results.all.collectAsState()
        val running by Runner.running.collectAsState()
        val prompt by Human.prompt.collectAsState()
        val r = results[p.id]
        var note by remember(p.id) { mutableStateOf(r?.note ?: "") }
        var more by remember(p.id) { mutableStateOf(false) }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TextButton({ screen.value = backTo }) { Text("Назад") }
                Text(p.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Tone.ink, lineHeight = 32.sp)
                if (p.what.isNotBlank()) Text(p.what, fontSize = 15.sp, color = Tone.muted)
                val st = r?.status ?: Status.NOT_RUN
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    StatusDot(st)
                    Spacer(Modifier.width(10.dp))
                    Text(statusWord(st), color = statusColor(st), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                val plain = if (Guide.isGuideStep(p.id)) Guide.plain(p.id) else r?.summary.orEmpty()
                if (plain.isNotBlank()) Text(plain, fontSize = 16.sp, color = Tone.ink)
                if (Guide.isGuideStep(p.id) && plain != r?.summary && !r?.summary.isNullOrBlank()) {
                    Text(r!!.summary, fontSize = 13.sp, color = Tone.muted)
                }
                prompt?.takeIf { it.probeId == p.id }?.let { Spacer(Modifier.height(8.dp)); PromptBlock(it, { a -> Human.answer(a) }, large = false) }
                Spacer(Modifier.height(8.dp))
                if (running == p.id) OutlinedButton({ Guide.stop(); Runner.cancel() }) { Text("Остановить") }
                else if (Guide.isGuideStep(p.id)) BigButton(if (r == null) "Запустить" else "Повторить", enabled = running == null) {
                    Guide.start(this@MainActivity, Guide.partOf(p.id), Guide.allSteps.first { it.id == p.id })
                } else BigButton(if (r == null) "Запустить" else "Повторить", enabled = running == null) { Runner.start(this@MainActivity, p) }
                TextButton({ more = !more }) { Text(if (more) "Скрыть подробности" else "Подробности") }
                if (more) {
                    p.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 13.sp, color = Tone.muted) }
                    OutlinedTextField(
                        note, { note = it; Results.update(p.id) { x -> x.copy(note = it) } },
                        Modifier.fillMaxWidth(), label = { Text("Заметка") },
                    )
                    r?.metrics?.forEach { (k, v) ->
                        Text(
                            "$k: " + if (v.size > 1) "медиана ${fmt(median(v))}, n=${v.size}" else fmt(v.first()),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Tone.muted,
                        )
                    }
                    r?.errors?.forEach { Text("${it.type}: ${it.msg}", fontSize = 12.sp, color = Tone.fail) }
                }
            }
            if (more) items(r?.events?.takeLast(120)?.reversed() ?: emptyList()) { e ->
                Text("${e.t} ${e.tag} ${e.text}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Tone.muted)
            }
        }
    }

    // ——— Отчёт и настройки ———

    @Composable
    private fun ReportScreen() {
        val tick by resumeTick
        val header = remember(tick) { Header.collect(this) }
        var ownServer by remember { mutableStateOf(Config.useOwnServer) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TextButton({ screen.value = "home" }) { Text("Назад") }
                Text("Отчёт", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Tone.ink)
                Text("Все цифры и журналы одним файлом — отправьте в Telegram или почту.", fontSize = 15.sp, color = Tone.muted)
                BigButton("Отправить отчёт") { share() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("report", Results.toText()))
                    }) { Text("Скопировать текст") }
                    OutlinedButton({ Results.clear() }) { Text("Очистить") }
                }
                SectionTitle("Телефон")
                header.forEach { (k, v) -> Text("$k: $v", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Tone.muted) }
                SectionTitle("Для подробных проверок")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ownServer, { ownServer = it; Config.useOwnServer = it })
                    Text("Свой тестовый сервер (scripts/serve.py на Mac)", fontSize = 14.sp)
                }
                Config.fields.forEach { f ->
                    var v by remember { mutableStateOf(Config[f.key]) }
                    OutlinedTextField(v, { v = it; Config[f.key] = it }, Modifier.fillMaxWidth(), label = { Text(f.label) }, singleLine = true)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    private fun share() {
        Results.saveNow()
        val uri = FileProvider.getUriForFile(this, "$packageName.files", Results.reportFile("report.json"))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, Results.toText())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Отчёт пробника"))
    }
}
