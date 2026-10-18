package ru.poneslas.tasya.probe.assist

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.Registry
import ru.poneslas.tasya.probe.core.Human

@Composable
fun SheetContent(session: ProbeSession) {
    val ui by Sheet.ui.collectAsState()
    val last by Sheet.last.collectAsState()
    val prompt by Human.prompt.collectAsState()
    var text by remember { mutableStateOf("") }
    val screenH = LocalConfiguration.current.screenHeightDp

    MaterialTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable(remember { MutableInteractionSource() }, null) { session.hide() },
        ) {
            // Видоискатель и встроенный пикер — отдельные поверхности (SurfaceView): контейнер с прокруткой
            // их не обрезает, и они вылезают за край листа. Поэтому панель стоит вне прокрутки, а лист обрезан по форме.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = (screenH * 0.7f).dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Color(0xFFF4F4F4))
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Лист пробника", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { session.hide() }) { Text("Закрыть") }
                }
                last?.let { Text(it.describe(), fontSize = 10.sp, color = Color.Gray, maxLines = 2) }
                prompt?.let { p ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFF3E0)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(p.text, fontSize = 15.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.options.forEach { o -> Button(onClick = { Human.answer(o) }) { Text(o) } }
                        }
                    }
                }
                if (ui.status.isNotBlank()) Text(ui.status, fontSize = 13.sp)
                ui.panel?.let { panel -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) { panel(session) } }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Registry.sheetActions
                        .filter { ui.groups == null || it.group in ui.groups!! }
                        .groupBy { it.group }
                        .forEach { (group, actions) ->
                            Text(groupTitle(group), fontSize = 11.sp, color = Color.Gray)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                actions.forEach { a ->
                                    FilledTonalButton(onClick = { App.scope.launch { a.run(session) } }) {
                                        Text(a.label, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Поле ввода (клавиатура)") },
                    )
                }
            }
        }
    }
}

private fun groupTitle(g: String) = when (g) {
    "assist" -> "Процесс"
    "session" -> "Микрофон, блокировка"
    "camera" -> "Камера"
    "pick" -> "Фото и файлы"
    "upload" -> "Загрузка"
    "expand" -> "Развернуть"
    "net" -> "Сеть"
    "perm" -> "Разрешения"
    "actions" -> "Действия"
    else -> g
}
