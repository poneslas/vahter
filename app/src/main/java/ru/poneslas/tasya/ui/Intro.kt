package ru.poneslas.tasya.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.poneslas.tasya.R
import ru.poneslas.tasya.app.PrimaryButton
import ru.poneslas.tasya.app.TextButton
import kotlin.math.sin

/**
 * Приветствие: девиз и 4 карточки с живыми сценами (сценарий — docs/design/prototype/intro.css, те же фазы).
 * Только встроенная анимация Compose: ни библиотек, ни файлов анимации — 0 КБ, 60 к/с, тексты из ресурсов.
 * Каждая сцена — петля 6 с: [t] от 0 до 1, фазы — доли петли.
 */
@Composable
fun Intro(onDone: () -> Unit) {
    val pages = 5
    val pager = rememberPagerState { pages }
    val scope = rememberCoroutineScope()
    val titles = listOf(R.string.intro_0_h, R.string.intro_1_h, R.string.intro_2_h, R.string.intro_3_h, R.string.intro_4_h)
    val texts = listOf(R.string.intro_0_p, R.string.intro_1_p, R.string.intro_2_p, R.string.intro_3_p, R.string.intro_4_p)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 22.dp)) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 300.dp), contentAlignment = Alignment.Center) {
                    // Сцена играет только на видимой странице.
                    if (pager.currentPage == i) Scene(i)
                }
                Text(stringResource(titles[i]), fontSize = 24.sp, lineHeight = 1.15.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(bottom = 8.dp))
                Text(stringResource(texts[i]), fontSize = 15.sp, color = Ink.soft, modifier = Modifier.heightIn(min = 66.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            val last = pager.currentPage == pages - 1
            Box(Modifier.weight(1f)) { if (!last) TextButton(stringResource(R.string.intro_skip), onClick = onDone) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages) { j -> Box(Modifier.size(if (j == pager.currentPage) 20.dp else 7.dp, 7.dp).clip(RoundedCornerShape(4.dp)).background(if (j == pager.currentPage) Ink.ink else Ink.swOff)) }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                PrimaryButton(stringResource(if (last) R.string.intro_setup else R.string.intro_next)) {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }
            }
        }
    }
}

// ── время ──────────────────────────────────────────────────────────────

/** 0…1 между [a] и [b], с мягким разгоном и торможением. */
private fun ramp(t: Float, a: Float, b: Float) = FastOutSlowInEasing.transform(((t - a) / (b - a)).coerceIn(0f, 1f))
/** Видно между [inA..inB] (появление) и [outA..outB] (исчезновение). */
private fun window(t: Float, inA: Float, inB: Float, outA: Float = 2f, outB: Float = 2f) = ramp(t, inA, inB) * (1f - ramp(t, outA, outB))

@Composable
private fun loop(ms: Int = 6000): Float {
    val tr = rememberInfiniteTransition(label = "intro")
    val t by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "t")
    return t
}

@Composable
private fun Scene(i: Int) = Box(Modifier.widthIn(max = 340.dp).fillMaxWidth().height(300.dp)) {
    when (i) { 0 -> Logo(); 1 -> SceneButton(); 2 -> SceneModes(); 3 -> SceneCamera(); else -> SceneDone() }
}

// ── общие кусочки: мини-лист, реплики, волна ───────────────────────────

@Composable
private fun MiniHeader(title: String, sub: String) = Column {
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(26.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Ink.grab))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Ink.server))
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
        Text(" · $sub", fontSize = 11.5.sp, color = Ink.soft)
    }
}

@Composable
private fun MiniSheet(modifier: Modifier, round: Boolean = false, content: @Composable () -> Unit) {
    val shape = if (round) RoundedCornerShape(22.dp) else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Box(modifier.graphicsLayer { shadowElevation = 18f; this.shape = shape; clip = true }.background(Ink.paper).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) { content() }
}

@Composable
private fun Me(text: String, alpha: Float, modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha; translationY = (1 - alpha) * 16f }, contentAlignment = Alignment.CenterEnd) {
    Text(text, fontSize = 12.sp, color = Ink.ink, modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(14.dp, 14.dp, 3.dp, 14.dp)).background(Ink.me).padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun Ai(text: String, alpha: Float, modifier: Modifier = Modifier) = Row(modifier.padding(top = 6.dp).graphicsLayer { this.alpha = alpha; translationY = (1 - alpha) * 16f }) {
    Box(Modifier.padding(top = 5.dp, end = 6.dp).size(6.dp).clip(CircleShape).background(Ink.server))
    Text(text, fontSize = 12.sp, lineHeight = 1.35.em, color = Ink.ink)
}

@Composable
private fun Wave(alpha: Float, color: Color = Ink.local) {
    if (alpha <= 0f) return
    val w = loop(900)
    Row(Modifier.fillMaxWidth().height(22.dp).graphicsLayer { this.alpha = alpha }, horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        listOf(0f, .11f, .28f, .05f, .33f, .17f, .22f).forEach { d ->
            val h = 5f + 15f * (0.5f + 0.5f * sin((w + d) * 2 * Math.PI.toFloat()))
            Box(Modifier.size(3.dp, h.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
private fun ActRow(icon: String, title: String, sub: String, alpha: Float, check: Float) = Row(
    Modifier.fillMaxWidth().padding(top = 6.dp).graphicsLayer { this.alpha = alpha; translationX = (1 - alpha) * -28f }
        .clip(RoundedCornerShape(11.dp)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(11.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Ink.localT), contentAlignment = Alignment.Center) { Text(icon, fontSize = 12.sp) }
        // галочка «пружинит»: 0 → 1.35 → 1
        val s = if (check < .5f) check * 2 * 1.35f else 1.35f - (check - .5f) * 2 * .35f
        Box(Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(12.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(Ink.ok)
            .border(1.5.dp, Ink.surf, CircleShape), contentAlignment = Alignment.Center) { Text("✓", fontSize = 7.sp, lineHeight = 7.sp, color = Color.White, fontWeight = FontWeight.Bold) }
    }
    Column(Modifier.padding(start = 8.dp)) {
        Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, maxLines = 1)
        Text(sub, fontSize = 10.sp, color = Ink.soft)
    }
}

// ── 0. девиз: логотип, столбики которого — волна голоса ───────────────

@Composable
private fun BoxScope.Logo() {
    val t = loop(2400)
    val w = loop(1200)
    Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
        listOf(0f to Ink.local, .5f to Ink.server).forEach { (d, c) ->
            val p = (t + d) % 1f
            Box(Modifier.size(132.dp).graphicsLayer { scaleX = 1 + .6f * p; scaleY = 1 + .6f * p; alpha = .6f * (1 - p) }.border(2.dp, c, RoundedCornerShape(40.dp)))
        }
        Box(Modifier.size(132.dp).graphicsLayer { shadowElevation = 30f; shape = RoundedCornerShape(40.dp); clip = true }.drawBehind {
            rotate(110f) { drawRect(Brush.sweepGradient(listOf(Ink.local, Color(0xFFF5C35A), Ink.server, Color(0xFF6F86FF), Ink.local), center), Offset(-size.width, -size.height), Size(size.width * 3, size.height * 3)) }
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().padding(24.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF10182B)))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0f, .15f, .3f, .1f).forEachIndexed { i, d ->
                    val h = 14f + 34f * (0.5f + 0.5f * sin((w + d) * 2 * Math.PI.toFloat()))
                    Box(Modifier.size(7.dp, h.dp).clip(RoundedCornerShape(4.dp)).background(if (i < 2) Color(0xFFF5C35A) else Color(0xFF7C93FF)))
                }
            }
        }
    }
}

// ── 1. не чат, а кнопка: сверху обычный чат — пять шагов; снизу Тася — одно нажатие ──

@Composable
private fun BoxScope.SceneButton() {
    val t = loop(7000)
    val steps = stringArrayResource(R.array.intro_steps)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Обычный чат: шаги отмечаются по одному, долго; потом блекнет.
        val dim = 1f - .45f * ramp(t, .50f, .56f)
        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = dim }.clip(RoundedCornerShape(18.dp)).background(Color(0xFFE9EBE5)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.intro_usual), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.soft)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.intro_usual_time), fontSize = 11.sp, color = Ink.soft, modifier = Modifier.graphicsLayer { alpha = ramp(t, .44f, .50f) })
            }
            Spacer(Modifier.height(8.dp))
            // Путь в обычном ИИ-приложении: шаг за шагом, через стрелки.
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                steps.forEachIndexed { i, st ->
                    val d = .03f + i * .085f
                    val on = ramp(t, d, d + .04f)
                    if (i > 0) Text("→", fontSize = 12.sp, color = Ink.soft, modifier = Modifier.graphicsLayer { alpha = on })
                    Text(st, fontSize = 11.5.sp, color = Ink.ink, modifier = Modifier.graphicsLayer { alpha = .25f + .75f * on; scaleX = .9f + .1f * on; scaleY = .9f + .1f * on }
                        .clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, Ink.line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
        // Тася: нажатие — лист — ответ.
        val glow = window(t, .56f, .58f, .62f, .66f)
        Column(Modifier.fillMaxWidth().weight(1f).graphicsLayer { shadowElevation = 16f; shape = RoundedCornerShape(18.dp); clip = true }
            .background(Ink.paper).border(2.dp, lerp(Color.Transparent, Ink.local, glow), RoundedCornerShape(18.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.intro_ours), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.ink).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.intro_key), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = lerp(Ink.soft, Ink.ink, glow),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(lerp(Ink.chipBg, Ink.local, glow)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.intro_ours_time), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Ink.localD, modifier = Modifier.graphicsLayer { alpha = ramp(t, .80f, .86f) })
            }
            Wave(window(t, .60f, .62f, .68f, .70f))
            Me(stringResource(R.string.intro_s1_q), ramp(t, .68f, .71f) * (1 - ramp(t, .97f, 1f)))
            Ai(stringResource(R.string.intro_s1_a), ramp(t, .74f, .78f) * (1 - ramp(t, .97f, 1f)))
        }
    }
}

// ── 2. настроил один раз: сначала свои правила в режиме, потом — одна кнопка и ответ по ним ──

@Composable
private fun BoxScope.SceneModes() {
    val t = loop(8000)
    val rules = stringArrayResource(R.array.intro_rules)
    MiniSheet(Modifier.fillMaxSize(), round = true) {
        Box(Modifier.fillMaxSize()) {
            // A. настройка режима: правила появляются по одному
            val a = 1f - ramp(t, .44f, .50f)
            if (a > 0f) Column(Modifier.graphicsLayer { alpha = a; translationY = -20f * (1 - a) }) {
                Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(26.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Ink.grab)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🍽", fontSize = 14.sp); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.intro_setup_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.5.dp, Ink.ink, RoundedCornerShape(12.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rules.forEachIndexed { i, r ->
                        val d = .06f + i * .09f
                        val pop = ramp(t, d, d + .05f)
                        Text("★ $r", fontSize = 12.5.sp, color = Ink.localD, modifier = Modifier.graphicsLayer { alpha = pop; scaleX = .8f + .2f * pop; scaleY = .8f + .2f * pop; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f) }
                            .clip(RoundedCornerShape(50)).background(Ink.localT).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("✓ " + stringResource(R.string.intro_setup_once), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.ok, modifier = Modifier.graphicsLayer { alpha = ramp(t, .36f, .40f) })
            }
            // B. пользуемся: вкладка режима, снимок меню, ответ по своим правилам
            val b = ramp(t, .48f, .54f) * (1 - ramp(t, .96f, 1f))
            if (b > 0f) Column(Modifier.fillMaxSize().graphicsLayer { alpha = b; translationY = 20f * (1 - b) }) {
                MiniHeader(stringResource(R.string.intro_tab_gastro), stringResource(R.string.intro_online))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rules.forEach { r -> Text(r, fontSize = 9.5.sp, color = Ink.localD, modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.localT).padding(horizontal = 6.dp, vertical = 2.dp)) }
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.graphicsLayer { alpha = ramp(t, .58f, .63f) }) { Thumb(menu = true) }
                Ai(stringResource(R.string.intro_rules_answer), ramp(t, .70f, .76f))
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                    listOf(R.string.intro_assistant, R.string.intro_tab_tr, R.string.intro_tab_gastro).forEach { r ->
                        val on = r == R.string.intro_tab_gastro
                        Text(stringResource(r), fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = if (on) Color.White else Ink.soft,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Ink.ink else Color.Transparent).padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(menu: Boolean) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    Canvas(Modifier.size(76.dp, 56.dp).clip(RoundedCornerShape(12.dp))) {
        if (menu) {
            drawRect(Brush.linearGradient(listOf(Color(0xFFF7F1E3), Color(0xFFE6D8B8))))
            for (i in 0..4) drawLine(Color(0x8C8E6E3E), Offset(10.dp.toPx(), (10 + i * 9).dp.toPx()), Offset(size.width - 10.dp.toPx(), (10 + i * 9).dp.toPx()), 2.dp.toPx())
        } else {
            drawRect(Color(0xFFE8DCC4))
            drawCircle(Color(0xFFA8743F), size.minDimension * .42f, Offset(size.width / 2, size.height * .58f))
            drawCircle(Color(0xFF6B3E20), size.minDimension * .32f, Offset(size.width / 2, size.height * .58f))
        }
    }
}

// ── 3. сразу камера или голос: режим сам выбирает, с чего начать ──

@Composable
private fun BoxScope.SceneCamera() {
    val t = loop(9000)
    val photo = t >= .30f
    Column(Modifier.fillMaxSize()) {
        // Два режима: у каждого своё главное действие. Нажатие кнопки — и оно уже включено.
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            listOf(Triple(R.string.intro_tab_tr, "🎙", R.string.intro_mode_voice), Triple(R.string.intro_tab_what, "📷", R.string.intro_mode_photo)).forEachIndexed { i, (n, ic, kind) ->
                val on = (i == 1) == photo
                Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) Ink.ink else Ink.surf).border(1.dp, if (on) Ink.ink else Ink.line, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(n), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else Ink.ink)
                    Text("  $ic " + stringResource(kind), fontSize = 10.5.sp, color = if (on) Color(0xFFF5C35A) else Ink.soft)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!photo) {
                val up = ramp(t, .03f, .08f) * (1 - ramp(t, .26f, .30f))
                MiniSheet(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(110.dp).graphicsLayer { translationY = (1 - up) * size.height }) {
                    Column { MiniHeader(stringResource(R.string.intro_tab_tr), stringResource(R.string.intro_online)); Wave(1f) }
                }
            } else CameraPart(((t - .30f) / .70f).coerceIn(0f, 1f))
        }
    }
}

@Composable
private fun BoxScope.CameraPart(t: Float) {
    val grow = ramp(t, .08f, .20f) * (1 - ramp(t, .62f, .74f))
    val h: Dp = (96 + (250 - 96) * grow).dp.let { if (t > .62f) (150 + (250 - 150) * (1 - ramp(t, .62f, .74f))).dp else it }
    MiniSheet(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(h), round = t > .1f && t < .7f) {
        Box(Modifier.fillMaxSize()) {
            Column { MiniHeader(stringResource(R.string.intro_tab_what), stringResource(R.string.intro_online)) }
            val vf = window(t, .14f, .20f, .60f, .66f)
            if (vf > 0f) Box(Modifier.padding(top = 30.dp, bottom = 48.dp).fillMaxSize().graphicsLayer { alpha = vf }.clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF3B3F48), Color(0xFF22252C))))) {
                val ring = ramp(t, .26f, .42f)
                val flash = window(t, .48f, .50f, .50f, .56f)
                Canvas(Modifier.fillMaxSize()) {
                    val shelfY = size.height - 30.dp.toPx()
                    drawRect(Color(0xFF6B5A44), Offset(0f, shelfY), Size(size.width, 6.dp.toPx()))
                    val jw = 34.dp.toPx(); val jh = 58.dp.toPx()
                    listOf(Color(0xFF5E9E4A), Color(0xFFE8823E), Color(0xFF6F8FD8)).forEachIndexed { i, c ->
                        val x = size.width / 2 - jw / 2 + (i - 1) * 62.dp.toPx()
                        drawRoundRect(c, Offset(x, shelfY - jh), Size(jw, jh), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                        drawRect(Color(0xFF2E2E2E), Offset(x + 5.dp.toPx(), shelfY - jh - 9.dp.toPx()), Size(jw - 10.dp.toPx(), 11.dp.toPx()))
                        drawRect(Color(0xFFF3E6C8), Offset(x + 4.dp.toPx(), shelfY - jh + 18.dp.toPx()), Size(jw - 8.dp.toPx(), 20.dp.toPx()))
                    }
                    // обводка рисуется «пальцем» по средней банке
                    val cx = size.width / 2; val cy = shelfY - jh / 2 - 4.dp.toPx()
                    val p = Path().apply {
                        moveTo(cx, cy - 48.dp.toPx())
                        cubicTo(cx + 30.dp.toPx(), cy - 48.dp.toPx(), cx + 34.dp.toPx(), cy - 10.dp.toPx(), cx + 30.dp.toPx(), cy + 14.dp.toPx())
                        cubicTo(cx + 26.dp.toPx(), cy + 44.dp.toPx(), cx + 10.dp.toPx(), cy + 48.dp.toPx(), cx - 4.dp.toPx(), cy + 46.dp.toPx())
                        cubicTo(cx - 28.dp.toPx(), cy + 44.dp.toPx(), cx - 34.dp.toPx(), cy + 18.dp.toPx(), cx - 32.dp.toPx(), cy - 8.dp.toPx())
                        cubicTo(cx - 30.dp.toPx(), cy - 34.dp.toPx(), cx - 18.dp.toPx(), cy - 46.dp.toPx(), cx + 6.dp.toPx(), cy - 46.dp.toPx())
                    }
                    if (ring > 0f) {
                        val m = PathMeasure().apply { setPath(p, false) }
                        val seg = Path(); m.getSegment(0f, m.length * ring, seg, true)
                        drawPath(seg, Color(0xFFFF3B30), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                    }
                    if (flash > 0f) drawRect(Color.White.copy(alpha = .85f * flash))
                }
            }
            if (vf > 0f) {
                val press = window(t, .46f, .49f, .49f, .53f)
                Box(Modifier.align(Alignment.BottomCenter).size(40.dp).graphicsLayer { alpha = vf; scaleX = 1 - .14f * press; scaleY = 1 - .14f * press }
                    .clip(CircleShape).background(Ink.ink), contentAlignment = Alignment.Center) { Text("↑", color = Color.White, fontSize = 15.sp) }
            }
            val res = window(t, .68f, .74f, .96f, 1f)
            if (res > 0f) Column(Modifier.padding(top = 30.dp).graphicsLayer { alpha = res; translationY = (1 - res) * 16f }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Canvas(Modifier.size(44.dp, 52.dp).clip(RoundedCornerShape(9.dp))) {
                        drawRect(Brush.verticalGradient(listOf(Color(0xFF3B3F48), Color(0xFF22252C))))
                        drawRoundRect(Color(0xFFE8823E), Offset(12.dp.toPx(), size.height - 38.dp.toPx()), Size(20.dp.toPx(), 32.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                        drawRoundRect(Color(0xFFFF3B30), Offset(10.dp.toPx(), size.height - 40.dp.toPx()), Size(24.dp.toPx(), 36.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                    }
                }
                Ai(stringResource(R.string.intro_s3_a), ramp(t, .78f, .84f))
            }
        }
    }
}

// ── 4. сказал — сделано: волна → реплика → две строки с галочками → «без интернета»

@Composable
private fun BoxScope.SceneDone() {
    val t = loop()
    val q = stringResource(R.string.intro_s4_q)
    MiniSheet(Modifier.fillMaxSize().padding(top = 20.dp), round = true) {
        Box(Modifier.fillMaxSize()) {
            Column {
                MiniHeader(stringResource(R.string.intro_assistant), stringResource(R.string.intro_online))
                val typing = window(t, 0f, .01f, .32f, .34f)
                Wave(typing)
                if (typing > 0f) {
                    val n = (q.length * ramp(t, .04f, .26f)).toInt()
                    Text(q.take(n).lowercase(), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Ink.ink, maxLines = 1, overflow = TextOverflow.Clip,
                        modifier = Modifier.graphicsLayer { alpha = typing }.padding(top = 4.dp))
                }
            }
            Column(Modifier.padding(top = 46.dp)) {
                Me(q, ramp(t, .33f, .38f))
                Spacer(Modifier.height(4.dp))
                ActRow("⏰", stringResource(R.string.intro_s4_r1), stringResource(R.string.intro_s4_r1s), ramp(t, .44f, .50f), ramp(t, .50f, .60f))
                ActRow("⏱", stringResource(R.string.intro_s4_r2), stringResource(R.string.intro_s4_r2s), ramp(t, .56f, .62f), ramp(t, .62f, .72f))
            }
            val off = ramp(t, .72f, .78f)
            Text(stringResource(R.string.intro_s4_off), fontSize = 11.sp, color = Ink.soft, modifier = Modifier.align(Alignment.BottomStart)
                .graphicsLayer { alpha = off; scaleX = .9f + .1f * off; scaleY = .9f + .1f * off }.clip(RoundedCornerShape(50)).background(Ink.chipBg).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

private fun lerp(a: Color, b: Color, f: Float) = Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, a.alpha + (b.alpha - a.alpha) * f)
