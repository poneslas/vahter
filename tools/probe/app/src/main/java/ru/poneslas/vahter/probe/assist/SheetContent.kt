package ru.poneslas.vahter.probe.assist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.Registry
import ru.poneslas.vahter.probe.core.Config
import ru.poneslas.vahter.probe.core.Human
import ru.poneslas.vahter.probe.core.Runner
import ru.poneslas.vahter.probe.guide.Guide
import ru.poneslas.vahter.probe.ui.ProbeTheme
import ru.poneslas.vahter.probe.ui.PromptBlock
import ru.poneslas.vahter.probe.ui.Tone

/**
 * Лист пробника. В обычном прогоне — только то, что нужно сейчас: шаг, вопрос, одна-две кнопки шага.
 * Все кнопки подробных проверок — когда идёт подробная проверка или включено «Показывать все кнопки».
 */
@Composable
fun SheetContent(session: ProbeSession) {
    val ui by Sheet.ui.collectAsState()
    val last by Sheet.last.collectAsState()
    val prompt by Human.prompt.collectAsState()
    val running by Runner.running.collectAsState()
    val step by Guide.current.collectAsState()
    var text by remember { mutableStateOf("") }
    val screenH = LocalConfiguration.current.screenHeightDp
    val debug = Config.flag("debug_buttons")

    val actions = when {
        ui.groups != null -> Registry.sheetActions.filter { it.group in ui.groups!! }
        debug || (running != null && !Guide.isGuideStep(running)) -> Registry.sheetActions.filter { !it.group.startsWith("guide_") }
        else -> emptyList()
    }
    val header = step?.let { s -> "Шаг ${Guide.indexIn(s.id) + 1} из ${Guide.partOf(s.id).steps.size} · ${s.title}" } ?: "Пробник Вахтёра"

    ProbeTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable(remember { MutableInteractionSource() }, null) { session.hide() },
        ) {
            if (ui.fullscreen && ui.panel != null) {
                // Камера на весь экран: панель занимает окно листа, вопрос шага и «Закрыть» — поверх.
                Box(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) {}) {
                    ui.panel!!(session)
                    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(header, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { session.hide() }) { Text("Закрыть", color = Color.White) }
                        }
                        prompt?.let { p ->
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xF2FFFFFF)).padding(14.dp)) {
                                PromptBlock(p, { Human.answer(it) }, large = false)
                            }
                        }
                    }
                }
                return@Box
            }
            // Видоискатель и встроенный пикер — отдельные поверхности (SurfaceView): контейнер с прокруткой
            // их не обрезает, и они вылезают за край листа. Поэтому панель стоит вне прокрутки, а лист обрезан по форме.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = (screenH * 0.75f).dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Tone.surface)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(header, fontSize = 14.sp, color = Tone.muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = { session.hide() }) { Text("Закрыть") }
                }
                if (debug) last?.let { Text(it.describe(), fontSize = 10.sp, color = Tone.muted, maxLines = 2) }
                val stepActions = if (ui.groups != null) actions else emptyList()
                prompt?.let { p ->
                    PromptBlock(p, { Human.answer(it) }, large = false) {
                        stepActions.forEach { a ->
                            Button(
                                onClick = { App.scope.launch { a.run(session) } },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Tone.action),
                            ) { Text(a.label, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
                if (ui.status.isNotBlank()) Text(ui.status, fontSize = 15.sp, color = Tone.ink)
                ui.panel?.let { panel -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) { panel(session) } }
                if (prompt == null && actions.isEmpty() && ui.status.isBlank() && ui.panel == null) {
                    Text(
                        if (running != null) "Идёт проверка. Ничего нажимать не нужно." else "Здесь будет Вахтёр. Сейчас пробнику ничего не нужно.",
                        fontSize = 16.sp, color = Tone.muted,
                    )
                }
                if (actions.isNotEmpty() && !(prompt != null && ui.groups != null)) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Кнопки шага — крупные, главные; кнопки подробных проверок — компактные, по группам.
                        if (ui.groups != null) {
                            actions.forEach { a ->
                                Button(
                                    onClick = { App.scope.launch { a.run(session) } },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Tone.action),
                                ) { Text(a.label, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                            }
                        } else {
                            actions.groupBy { it.group }.forEach { (group, list) ->
                                Text(groupTitle(group), fontSize = 12.sp, color = Tone.muted)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    list.forEach { a ->
                                        FilledTonalButton(onClick = { App.scope.launch { a.run(session) } }) { Text(a.label, fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                        if (debug || running == "П3" || running == "П3ч") {
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
    }
}

private fun groupTitle(g: String) = when (g) {
    "assist" -> "Процесс"
    "session" -> "Микрофон, блокировка"
    "camera" -> "Камера"
    "pick" -> "Фото и файлы"
    "hide" -> "Обход: сначала спрятать лист"
    "upload" -> "Загрузка"
    "expand" -> "Развернуть"
    "net" -> "Сеть"
    "perm" -> "Разрешения"
    "actions" -> "Действия"
    else -> g
}
