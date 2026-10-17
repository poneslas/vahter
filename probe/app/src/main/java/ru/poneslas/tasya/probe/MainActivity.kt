package ru.poneslas.tasya.probe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) { Screens() }
            }
        }
        intent?.getStringExtra("probe")?.let { screen.value = it }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    fun requestPermissions() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37) list += "android.permission.ACCESS_LOCAL_NETWORK"
        permissions.launch(list.toTypedArray())
    }

    /** "list" | "report" | id проверки */
    private val screen = mutableStateOf("list")

    @Composable
    private fun Screens() {
        val s by screen
        BackHandler(enabled = s != "list") { screen.value = "list" }
        when (s) {
            "list" -> ListScreen()
            "report" -> ReportScreen()
            else -> DetailScreen(Registry.probe(s))
        }
    }

    @Composable
    private fun ListScreen() {
        val results by Results.all.collectAsState()
        val running by Runner.running.collectAsState()
        val tick by resumeTick
        val header = remember(tick, results) { Header.collect(this) }
        var autoBlockerOff by remember { mutableStateOf(Config.flag("autoblocker_off")) }

        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text("Пробник Таси", style = MaterialTheme.typography.titleLarge)
                header.forEach { (k, v) -> Text("$k: $v", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autoBlockerOff, { autoBlockerOff = it; Config.setFlag("autoblocker_off", it) })
                    Text("Auto Blocker выключен (проверить руками)", fontSize = 13.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ requestPermissions() }) { Text("Разрешения") }
                    OutlinedButton({ openAssistantSettings(this@MainActivity) }) { Text("Роль помощника") }
                    OutlinedButton({ App.scope.launch { Sheet.open() } }) { Text("Открыть лист") }
                    OutlinedButton({ screen.value = "report" }) { Text("Отчёт и настройки") }
                }
                Button({ Runner.startAllAutomatic(this@MainActivity) }, enabled = running == null) {
                    Text("Запустить всё автоматическое")
                }
                if (running != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Идёт: $running", Modifier.weight(1f))
                        OutlinedButton({ Runner.cancel() }) { Text("Остановить") }
                    }
                    PromptBlock(null)
                }
                HorizontalDivider()
            }
            items(Registry.probes) { p ->
                val r = results[p.id]
                val status = r?.status ?: if (p.implemented) Status.NOT_RUN else Status.NOT_IMPL
                Column(Modifier.fillMaxWidth().clickable { screen.value = p.id }.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(status.color), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("${p.id}  ${p.title}", Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(status.label + if (p.needsHuman) " · 👤" else "", fontSize = 12.sp, color = Color(status.color))
                    }
                    keyFigure(r)?.let { Text(it, fontSize = 12.sp, color = Color.Gray, maxLines = 2) }
                }
            }
        }
    }

    private fun keyFigure(r: ru.poneslas.tasya.probe.core.ProbeResult?): String? {
        if (r == null) return null
        if (r.summary.isNotBlank()) return r.summary
        val m = r.metrics.entries.firstOrNull() ?: return null
        return "${m.key}: медиана ${fmt(median(m.value))}"
    }

    @Composable
    private fun PromptBlock(probeId: String?) {
        val prompt by Human.prompt.collectAsState()
        val p = prompt ?: return
        if (probeId != null && p.probeId != probeId) return
        Column(Modifier.fillMaxWidth().background(Color(0xFFFFF3E0)).padding(8.dp)) {
            Text("${p.probeId}: ${p.text}", fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.options.forEach { o -> Button({ Human.answer(o) }) { Text(o) } }
            }
        }
    }

    @Composable
    private fun DetailScreen(p: Probe) {
        val results by Results.all.collectAsState()
        val running by Runner.running.collectAsState()
        val r = results[p.id]
        var note by remember(p.id) { mutableStateOf(r?.note ?: "") }

        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                OutlinedButton({ screen.value = "list" }) { Text("← Все проверки") }
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
                    Modifier.fillMaxWidth(), label = { Text("Заметка человека") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Status.PASS, Status.PARTIAL, Status.FAIL).forEach { st ->
                        OutlinedButton({ Results.update(p.id) { it.copy(status = st) } }) { Text(st.label, fontSize = 12.sp) }
                    }
                }
                if (r != null) {
                    Text("Статус: ${r.status.label}", fontWeight = FontWeight.Medium, color = Color(r.status.color))
                    if (r.summary.isNotBlank()) Text(r.summary)
                    r.metrics.forEach { (k, v) ->
                        Text(
                            "$k: " + if (v.size > 1) "медиана ${fmt(median(v))}, n=${v.size}, [${v.joinToString { fmt(it) }}]" else fmt(v.first()),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    r.errors.forEach { Text("! ${it.type}: ${it.msg}", fontSize = 12.sp, color = Color(0xFFC62828)) }
                    HorizontalDivider()
                    Text("Лог", fontWeight = FontWeight.Medium)
                }
            }
            items(r?.events?.takeLast(150)?.reversed() ?: emptyList()) { e ->
                Text("${e.t} ${e.tag} ${e.text}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    @Composable
    private fun ReportScreen() {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ screen.value = "list" }) { Text("← Все проверки") }
            Text("Настройки прогона", style = MaterialTheme.typography.titleMedium)
            Config.fields.forEach { f ->
                var v by remember { mutableStateOf(Config[f.key]) }
                OutlinedTextField(v, { v = it; Config[f.key] = it }, Modifier.fillMaxWidth(), label = { Text(f.label) }, singleLine = true)
            }
            HorizontalDivider()
            Text("Отчёт", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ share() }) { Text("Поделиться") }
                OutlinedButton({
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("report", Results.toText()))
                }) { Text("Скопировать текст") }
                OutlinedButton({ Results.clear() }) { Text("Очистить результаты") }
            }
            Text("Файлы: files/report.json, report.txt. С Mac: scripts/pull-report.sh", fontSize = 12.sp, color = Color.Gray)
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
