package ru.poneslas.tasya.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import ru.poneslas.tasya.probe.core.ProbeResult
import ru.poneslas.tasya.probe.core.Results
import ru.poneslas.tasya.probe.core.Runner
import ru.poneslas.tasya.probe.core.Status
import ru.poneslas.tasya.probe.core.fmt
import ru.poneslas.tasya.probe.core.median

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick.intValue++
    }

    /** "home" | "report" | "all" | "E<n>" (главный пункт) | id проверки */
    private val screen = mutableStateOf("home")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Color(0xFFF6F6F6)) { Screens() }
            }
        }
        handle(intent)
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

    private fun requestAllPermissions() {
        // ACCESS_LOCAL_NETWORK не просим: П13 сама меряет «без разрешения» и запрашивает его.
        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryUnrestricted() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    @Composable
    private fun Screens() {
        val s by screen
        BackHandler(enabled = s != "home") {
            screen.value = when {
                Registry.probes.any { it.id == s } && backTo != null -> backTo!!
                else -> "home"
            }
        }
        when {
            s == "home" -> HomeScreen()
            s == "report" -> ReportScreen()
            s == "all" -> AllScreen()
            s.startsWith("E") -> EssentialScreen(Essentials.all.first { "E${it.n}" == s })
            else -> DetailScreen(Registry.probe(s))
        }
    }

    /** Куда возвращаться из проверки: в пункт главного или в «Все проверки». */
    private var backTo: String? = null

    private fun openProbe(id: String, from: String) {
        backTo = from
        screen.value = id
    }

    // ——— Главный экран ———

    @Composable
    private fun HomeScreen() {
        val results by Results.all.collectAsState()
        val running by Runner.running.collectAsState()
        val tick by resumeTick
        val done = Essentials.all.count { Essentials.verdict(it, results) !in setOf(Status.NOT_RUN, Status.RUNNING) }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Spacer(Modifier.size(8.dp))
                Text("Пробник Таси", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Проверяет на этом телефоне то, что эмулятор не скажет.", fontSize = 14.sp, color = Color.Gray)
            }
            item { SetupCard(tick) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Главное", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("проверено $done из ${Essentials.all.size}", fontSize = 13.sp, color = Color.Gray)
                }
                Spacer(Modifier.size(4.dp))
                LinearProgressIndicator(progress = { done / Essentials.all.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            if (running != null) item { RunningBar(running!!) }
            items(Essentials.all) { e ->
                val v = Essentials.verdict(e, results)
                Card(
                    Modifier.fillMaxWidth().clickable { screen.value = "E${e.n}" },
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.n}", fontSize = 20.sp, color = Color.Gray, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(e.why, fontSize = 12.sp, color = Color.Gray, maxLines = 2)
                        }
                        StatusChip(v)
                    }
                }
            }
            item {
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ screen.value = "all" }) { Text("Дополнительно") }
                    OutlinedButton({ screen.value = "report" }) { Text("Отчёт") }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }

    @Composable
    private fun SetupCard(tick: Int) {
        val role = remember(tick) { Header.roleHeld(this) }
        val battery = remember(tick) { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Подготовка", style = MaterialTheme.typography.titleMedium)
                SetupRow("Пробник — цифровой помощник", role, "Назначить") { openAssistantSettings(this@MainActivity) }
                SetupRow("Батарея: без ограничений", battery, "Включить") { requestBatteryUnrestricted() }
                Text(
                    "Боковая кнопка: Настройки → Дополнительные функции → Боковая кнопка → Нажатие и удержание → «Цифровой помощник».",
                    fontSize = 13.sp,
                )
                Text("Разрешения пункт 2 запросит сам. Нужен интернет; провод и Mac не нужны.", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }

    @Composable
    private fun SetupRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "✓" else "•", color = if (ok) Color(0xFF2E7D32) else Color(0xFFE65100), modifier = Modifier.width(20.dp))
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            if (!ok) TextButton(onClick) { Text(action) }
        }
    }

    @Composable
    private fun StatusChip(s: Status) {
        val c = Color(s.color)
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(Essentials.word(s), color = c, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun RunningBar(id: String) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Идёт $id · ${Registry.probe(id).title}", Modifier.weight(1f), fontSize = 13.sp)
                    TextButton({ Runner.cancel() }) { Text("Остановить") }
                }
                PromptBlock(null)
            }
        }
    }

    @Composable
    private fun PromptBlock(probeId: String?) {
        val prompt by Human.prompt.collectAsState()
        val p = prompt ?: return
        if (probeId != null && p.probeId != probeId) return
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFF3E0)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(p.text, fontSize = 15.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.options.forEach { o -> Button({ Human.answer(o) }) { Text(o) } }
            }
        }
    }

    // ——— Пункт главного ———

    @Composable
    private fun EssentialScreen(e: Essential) {
        val results by Results.all.collectAsState()
        val running by Runner.running.collectAsState()
        val v = Essentials.verdict(e, results)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                TextButton({ screen.value = "home" }) { Text("← Главное") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.n}. ${e.title}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    StatusChip(v)
                }
                Text(e.why, fontSize = 14.sp, color = Color.Gray)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Что сделать", fontWeight = FontWeight.Medium)
                        e.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 14.sp) }
                    }
                }
            }
            items(e.probes) { id ->
                val p = Registry.probe(id)
                val r = results[id]
                val st = r?.status ?: Status.NOT_RUN
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("$id · ${p.title}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(if (p.needsHuman) "с вашим участием" else "сама, телефон не трогать", fontSize = 12.sp, color = Color.Gray)
                            }
                            StatusChip(st)
                        }
                        if (!r?.summary.isNullOrBlank()) Text(r!!.summary, fontSize = 13.sp)
                        PromptBlock(id)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (running == id) OutlinedButton({ Runner.cancel() }) { Text("Остановить") }
                            else Button({ Runner.start(this@MainActivity, p) }, enabled = running == null) { Text("Запустить") }
                            TextButton({ openProbe(id, "E${e.n}") }) { Text("Подробнее") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    // ——— Все проверки ———

    @Composable
    private fun AllScreen() {
        val results by Results.all.collectAsState()
        val running by Runner.running.collectAsState()
        val essential = Essentials.all.flatMap { it.probes }.toSet()
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                TextButton({ screen.value = "home" }) { Text("← Главное") }
                Text("Все проверки", style = MaterialTheme.typography.titleLarge)
                Text("Отмеченные ★ входят в «Главное». Некоторым нужен Mac с scripts/serve.py или провод.", fontSize = 13.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    Button({ Runner.startAllAutomatic(this@MainActivity) }, enabled = running == null) { Text("Все автоматические") }
                    OutlinedButton({ App.scope.launch { Sheet.open() } }) { Text("Открыть лист") }
                }
                if (running != null) RunningBar(running!!)
                HorizontalDivider()
            }
            items(Registry.probes) { p ->
                val r = results[p.id]
                val status = r?.status ?: Status.NOT_RUN
                Column(Modifier.fillMaxWidth().clickable { openProbe(p.id, "all") }.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(status.color), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${p.id}${if (p.id in essential) " ★" else ""}  ${p.title}",
                            Modifier.weight(1f), fontSize = 14.sp,
                        )
                        Text(status.label + if (p.needsHuman) " · 👤" else "", fontSize = 12.sp, color = Color(status.color))
                    }
                    keyFigure(r)?.let { Text(it, fontSize = 12.sp, color = Color.Gray, maxLines = 2) }
                }
            }
        }
    }

    private fun keyFigure(r: ProbeResult?): String? {
        if (r == null) return null
        if (r.summary.isNotBlank()) return r.summary
        val m = r.metrics.entries.firstOrNull() ?: return null
        return "${m.key}: медиана ${fmt(median(m.value))}"
    }

    // ——— Одна проверка (подробно) ———

    @Composable
    private fun DetailScreen(p: Probe) {
        val results by Results.all.collectAsState()
        val running by Runner.running.collectAsState()
        val r = results[p.id]
        var note by remember(p.id) { mutableStateOf(r?.note ?: "") }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                TextButton({ screen.value = backTo ?: "home" }) { Text("← Назад") }
                Text("${p.id}. ${p.title}", style = MaterialTheme.typography.titleMedium)
                if (p.what.isNotBlank()) Text(p.what, fontSize = 13.sp)
                p.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ Runner.start(this@MainActivity, p) }, enabled = running == null) { Text("Запустить") }
                    if (running == p.id) OutlinedButton({ Runner.cancel() }) { Text("Остановить") }
                }
                PromptBlock(p.id)
                OutlinedTextField(
                    note, { note = it; Results.update(p.id) { x -> x.copy(note = it) } },
                    Modifier.fillMaxWidth(), label = { Text("Заметка") },
                )
                Text("Поставить итог вручную:", fontSize = 12.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Status.PASS, Status.PARTIAL, Status.FAIL).forEach { st ->
                        OutlinedButton({ Results.update(p.id) { it.copy(status = st) } }) { Text(Essentials.word(st), fontSize = 12.sp) }
                    }
                }
                if (r != null) {
                    Text("Итог: ${Essentials.word(r.status)}", fontWeight = FontWeight.Medium, color = Color(r.status.color))
                    if (r.summary.isNotBlank()) Text(r.summary)
                    r.metrics.forEach { (k, v) ->
                        Text(
                            "$k: " + if (v.size > 1) "медиана ${fmt(median(v))}, n=${v.size}, [${v.joinToString { fmt(it) }}]" else fmt(v.first()),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    r.errors.forEach { Text("! ${it.type}: ${it.msg}", fontSize = 12.sp, color = Color(0xFFC62828)) }
                    HorizontalDivider()
                    Text("Журнал", fontWeight = FontWeight.Medium)
                }
            }
            items(r?.events?.takeLast(150)?.reversed() ?: emptyList()) { e ->
                Text("${e.t} ${e.tag} ${e.text}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    // ——— Отчёт и настройки ———

    @Composable
    private fun ReportScreen() {
        val tick by resumeTick
        val header = remember(tick) { Header.collect(this) }
        var ownServer by remember { mutableStateOf(Config.useOwnServer) }
        var autoBlockerOff by remember { mutableStateOf(Config.flag("autoblocker_off")) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TextButton({ screen.value = "home" }) { Text("← Главное") }
                Text("Отчёт", style = MaterialTheme.typography.titleLarge)
                Text("Отправьте отчёт в Telegram или почту — там все цифры и журналы.", fontSize = 13.sp, color = Color.Gray)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ share() }) { Text("Поделиться") }
                    OutlinedButton({
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("report", Results.toText()))
                    }) { Text("Скопировать текст") }
                    OutlinedButton({ Results.clear() }) { Text("Очистить результаты") }
                }
                HorizontalDivider()
                Text("Телефон", style = MaterialTheme.typography.titleMedium)
                header.forEach { (k, v) -> Text("$k: $v", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                OutlinedButton({ requestAllPermissions() }) { Text("Выдать все разрешения") }
                HorizontalDivider()
                Text("Для дополнительных проверок", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ownServer, { ownServer = it; Config.useOwnServer = it })
                    Text("Свой тестовый сервер (scripts/serve.py на Mac)", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autoBlockerOff, { autoBlockerOff = it; Config.setFlag("autoblocker_off", it) })
                    Text("Auto Blocker выключен", fontSize = 13.sp)
                }
                Config.fields.forEach { f ->
                    var v by remember { mutableStateOf(Config[f.key]) }
                    OutlinedTextField(v, { v = it; Config[f.key] = it }, Modifier.fillMaxWidth(), label = { Text(f.label) }, singleLine = true)
                }
                Spacer(Modifier.size(24.dp))
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
