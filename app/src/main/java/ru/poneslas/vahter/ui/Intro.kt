package ru.poneslas.vahter.ui

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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.poneslas.vahter.R
import ru.poneslas.vahter.app.PrimaryButton
import ru.poneslas.vahter.app.TextButton
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
    when (i) { 0 -> Logo(); 1 -> SceneQueue(); 2 -> SceneFace(); 3 -> SceneWho(); else -> SceneDone() }
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
    // табличка поста: лампа мигает — вахтёр на месте
    val lamp = loop(1600)
    val lp = .5f + .5f * sin(lamp * 2 * Math.PI.toFloat())
    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).graphicsLayer { shadowElevation = 8f; shape = RoundedCornerShape(10.dp); clip = true }
        .background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).drawBehind { drawCircle(Color(0xFFF5C35A).copy(alpha = .7f * (1 - lp)), radius = size.minDimension / 2 + 7.dp.toPx() * lp) }
            .clip(CircleShape).background(Color(0xFFF5C35A).copy(alpha = .75f + .25f * (1 - lp))))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.intro_post), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .02.em, color = Ink.ink)
    }
}

// ── 1. своих — без очереди: гонка. Обычное приложение — талончики №1–4; Вахтёр — зажал, и вопрос уже задан ──

/** Пунктирная рамка — талончик очереди. */
private fun Modifier.dashed(color: Color, r: Dp) = drawBehind {
    drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r.toPx()),
        style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
}

/** Секундомер «⏱ 0:NN»: от 0 до [n] секунд к доле петли [until]. */
@Composable
private fun Clock(t: Float, n: Int, until: Float, color: Color) {
    val s = (n * (t / until).coerceIn(0f, 1f)).toInt()
    Text("⏱ 0:%02d".format(s), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
}

/** Эквалайзер диктовки в цветах Вахтёра: янтарь → синий. */
@Composable
private fun DictEq(alpha: Float, bars: Int = 9, maxH: Float = 16f, w: Dp = 3.dp) {
    if (alpha <= 0f) return
    val ph = loop(380 * 2)
    Row(Modifier.height(maxH.dp).graphicsLayer { this.alpha = alpha }, horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until bars) {
            val k = i / (bars - 1f)
            val h = 4f + (maxH - 4f) * (0.5f + 0.5f * sin((ph + i * .37f) * 2 * Math.PI.toFloat()))
            Box(Modifier.size(w, h.dp).clip(RoundedCornerShape(2.dp)).background(lerp(Ink.local, Ink.server, k)))
        }
    }
}

@Composable
private fun BoxScope.SceneQueue() {
    val t = loop(5000)
    val out = 1f - ramp(t, .97f, 1f)
    val steps = stringArrayResource(R.array.intro_steps)
    Column(Modifier.fillMaxSize().graphicsLayer { alpha = out }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Обычное ИИ-приложение: шаги-талончики загораются по одному, секундомер бежит до 0:14.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFE2E5EB)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) { Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(2) { Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFA9B0BE))) } } }
                }
                Spacer(Modifier.width(9.dp))
                Text(stringResource(R.string.intro_usual), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5B6478))
                Spacer(Modifier.weight(1f))
                Clock(t, 14, .70f, Color(0xFF6B7487))
            }
            Spacer(Modifier.height(9.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                steps.forEachIndexed { i, st ->
                    val on = .3f + .7f * ramp(t, .06f + i * .14f, .09f + i * .14f)
                    Row(Modifier.graphicsLayer { alpha = on }.clip(RoundedCornerShape(9.dp)).background(Color.White).dashed(Ink.line, 9.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.intro_ticket, i + 1), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A93A5))
                        Spacer(Modifier.width(5.dp))
                        Text(st, fontSize = 12.sp, color = Ink.ink)
                    }
                    Text("→", fontSize = 13.sp, color = Ink.soft, modifier = Modifier.graphicsLayer { alpha = .2f + .8f * ramp(t, .06f + i * .14f, .09f + i * .14f) })
                }
                Text(stringResource(R.string.intro_question), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink,
                    modifier = Modifier.graphicsLayer { alpha = .3f + .7f * ramp(t, .64f, .67f) }.clip(RoundedCornerShape(9.dp)).background(Color.White)
                        .border(1.dp, Ink.line, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        // Вахтёр: зажал — вопрос надиктован — ответ; секундомер встаёт на 0:02.
        Column(Modifier.fillMaxWidth().weight(1f).graphicsLayer { shadowElevation = 14f; shape = RoundedCornerShape(18.dp); clip = true }
            .background(Ink.localT).border(2.dp, Ink.local, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val press = window(t, 0f, .02f, .05f, .09f)
                Box(Modifier.size(28.dp).graphicsLayer { scaleX = 1 - .12f * press; scaleY = 1 - .12f * press }.clip(CircleShape).background(Ink.ink), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(Ic.power), null, Modifier.size(15.dp), tint = Ink.local)
                }
                Spacer(Modifier.width(9.dp))
                Text(stringResource(R.string.intro_ours), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.ink)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.intro_free), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Ink.localD,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).border(1.dp, Color(0xFFEBCB86), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.weight(1f))
                Clock(t, 2, .38f, Ink.localD)
                Spacer(Modifier.width(6.dp))
                val ok = ramp(t, .38f, .42f)
                val s = if (ok < .5f) ok * 2 * 1.3f else 1.3f - (ok - .5f) * 2 * .3f
                Box(Modifier.size(22.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(Ink.ok), contentAlignment = Alignment.Center) {
                    Text("✓", fontSize = 13.sp, color = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.graphicsLayer { alpha = .3f + .7f * ramp(t, .06f, .09f) }.clip(RoundedCornerShape(9.dp)).background(Color.White)
                    .border(1.dp, Color(0xFFEBCB86), RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(Ic.power), null, Modifier.size(12.dp), tint = Ink.ink)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.intro_hold), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                }
                Text(" → ", fontSize = 13.sp, color = Ink.soft)
                Box(Modifier.graphicsLayer { alpha = .3f + .7f * ramp(t, .08f, .10f) }.clip(RoundedCornerShape(9.dp)).background(Color.White)
                    .border(1.dp, Color(0xFFEBCB86), RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    DictEq(window(t, .09f, .11f, .29f, .31f))
                    val tx = ramp(t, .29f, .31f)
                    if (tx > 0f) Text(stringResource(R.string.intro_s1_q), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.graphicsLayer { alpha = tx })
                }
            }
            Text(stringResource(R.string.intro_loc), fontSize = 11.sp, color = Ink.localD, maxLines = 1, modifier = Modifier.padding(top = 6.dp).graphicsLayer { alpha = ramp(t, .11f, .14f) })
            Ai(stringResource(R.string.intro_s1_a), ramp(t, .33f, .37f), Modifier.padding(top = 4.dp))
        }
    }
}

// ── 2. своих знает в лицо: карточка правил Гастрогида → штамп «всё запомнил» → через неделю одно нажатие, ответ по правилам ──

@Composable
private fun BoxScope.SceneFace() {
    val t = loop(6000)
    val rules = stringArrayResource(R.array.intro_rules)
    // A. карточка режима
    val a = 1f - ramp(t, .50f, .56f)
    if (a > 0f) Box(Modifier.fillMaxSize().padding(vertical = 6.dp).graphicsLayer { alpha = a; translationY = -60f * (1 - a); scaleX = 1 - .08f * (1 - a); scaleY = 1 - .08f * (1 - a) }
        .graphicsLayer { shadowElevation = 12f; shape = RoundedCornerShape(20.dp); clip = true }.background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(20.dp)).padding(16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Ink.localT), contentAlignment = Alignment.Center) { Text("🍽", fontSize = 19.sp) }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.intro_tab_gastro), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink.ink)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.intro_my_mode), fontSize = 12.sp, color = Ink.soft)
            }
            rules.forEachIndexed { i, r ->
                val on = ramp(t, .03f + i * .08f, .07f + i * .08f)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFECEEE8)))
                Row(Modifier.padding(vertical = 7.dp).graphicsLayer { alpha = on; translationX = -30f * (1 - on) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(Ink.localT), contentAlignment = Alignment.Center) { Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.localD) }
                    Spacer(Modifier.width(9.dp))
                    Text(r, fontSize = 14.sp, color = Ink.ink)
                }
            }
            val st = ramp(t, .27f, .31f)
            Text(buildAnnotatedString { append(stringResource(R.string.intro_start_with)); withStyle(SpanStyle(color = Ink.ink)) { append(stringResource(R.string.intro_start_cam)) } },
                fontSize = 13.sp, color = Ink.soft, modifier = Modifier.padding(top = 8.dp).graphicsLayer { alpha = st; translationX = -30f * (1 - st) }
                    .clip(RoundedCornerShape(50)).background(Color(0xFFECEEE8)).padding(horizontal = 12.dp, vertical = 5.dp))
        }
        // штамп: падает сверху крупным и встаёт на место
        val sp = ramp(t, .35f, .38f)
        if (sp > 0f) {
            val sc = 1.8f - .8f * sp
            Text(stringResource(R.string.intro_stamp).uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = .04.em, color = Ink.ok,
                modifier = Modifier.align(Alignment.BottomEnd).graphicsLayer { alpha = sp; rotationZ = -8f; scaleX = sc; scaleY = sc }
                    .border(2.5.dp, Ink.ok, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
    // B. через неделю: одно нажатие, снимок меню, ответ и метки правил
    val b = ramp(t, .54f, .58f) * (1 - ramp(t, .97f, 1f))
    if (b > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = b }) {
        Text(stringResource(R.string.intro_later), fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Ink.soft,
            modifier = Modifier.align(Alignment.TopCenter))
        MiniSheet(Modifier.fillMaxSize().padding(top = 26.dp), round = true) {
            Column {
                Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(26.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Ink.grab)) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Ink.server)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.intro_tab_gastro), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                    Text(stringResource(R.string.intro_one_press), fontSize = 11.5.sp, color = Ink.soft)
                }
                val th = ramp(t, .61f, .65f)
                Box(Modifier.graphicsLayer { alpha = th; scaleX = .8f + .2f * th; scaleY = .8f + .2f * th }) { Thumb(menu = true) }
                Ai(stringResource(R.string.intro_s2_answer), ramp(t, .67f, .71f), Modifier.padding(top = 4.dp))
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("✓ не остро", "✓ без свинины", "✓ без орехов", "✓ до 1500 ₽").forEachIndexed { i, c ->
                        val on = ramp(t, .73f + i * .03f, .76f + i * .03f)
                        Text(c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.localD, modifier = Modifier.graphicsLayer { alpha = on; scaleX = .6f + .4f * on; scaleY = .6f + .4f * on }
                            .clip(RoundedCornerShape(50)).background(Ink.localT).padding(horizontal = 9.dp, vertical = 3.dp))
                    }
                }
            }
        }
    }
}

// ── 3. знает, кто зачем пришёл: два телефона, одна и та же боковая кнопка — открывается разное ──

@Composable
private fun SceneWho() {
    val t = loop(3600)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Top) {
        TinyPhone(t, stringResource(R.string.intro_tab_tr), stringResource(R.string.intro_cap_voice), camera = false)
        TinyPhone(t, stringResource(R.string.intro_tab_what), stringResource(R.string.intro_cap_cam), camera = true)
    }
}

@Composable
private fun TinyPhone(t: Float, label: String, cap: String, camera: Boolean) {
    Column(Modifier.width(136.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink.ink, modifier = Modifier.padding(bottom = 8.dp))
        Box(Modifier.size(131.dp, 200.dp)) {
            // боковая кнопка вспыхивает янтарём
            val k = window(t, .09f, .12f, .17f, .22f)
            Box(Modifier.align(Alignment.TopEnd).offset(y = 62.dp).size(5.dp, 34.dp).graphicsLayer { translationX = 3f * k }
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)).background(lerp(Color(0xFF3A4258), Ink.local, k)))
            Box(Modifier.size(126.dp, 200.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1A2236)).padding(5.dp)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(19.dp)).background(Brush.linearGradient(listOf(Color(0xFF5C7391), Color(0xFF2B3B55))))) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(4) { Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { repeat(3) { Box(Modifier.size(22.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x47FFFFFF))) } } }
                    }
                    val up = ramp(t, .16f, .24f) * (1 - ramp(t, .94f, 1f))
                    val sheetH = if (camera) 190.dp else 92.dp
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetH).graphicsLayer { translationY = (1 - up) * size.height }
                        .clip(RoundedCornerShape(16.dp, 16.dp, if (camera) 16.dp else 0.dp, if (camera) 16.dp else 0.dp)).background(Ink.paper).padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.padding(bottom = 10.dp).size(22.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Ink.grab))
                            if (!camera) {
                                Wave(1f)
                                Text(stringResource(R.string.intro_say), fontSize = 11.5.sp, color = Ink.soft, modifier = Modifier.padding(top = 6.dp))
                            } else {
                                Canvas(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp))) {
                                    drawRect(Brush.verticalGradient(listOf(Color(0xFF3B3F48), Color(0xFF22252C))))
                                    val shelfY = size.height - 22.dp.toPx()
                                    drawRect(Color(0xFF6B5A44), Offset(0f, shelfY), Size(size.width, 5.dp.toPx()))
                                    listOf(20.dp to Color(0xFF6A9E3E), 62.dp to Color(0xFFE8823E)).forEach { (x, c) ->
                                        val jw = 30.dp.toPx(); val jh = 50.dp.toPx()
                                        drawRoundRect(c, Offset(x.toPx(), shelfY - jh), Size(jw, jh), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                                        drawRect(Color(0xFF2E2E2E), Offset(x.toPx() + 4.dp.toPx(), shelfY - jh - 8.dp.toPx()), Size(jw - 8.dp.toPx(), 10.dp.toPx()))
                                        drawRect(Color(0xFFF3E6C8), Offset(x.toPx() + 4.dp.toPx(), shelfY - jh + 16.dp.toPx()), Size(jw - 8.dp.toPx(), 17.dp.toPx()))
                                    }
                                }
                                Box(Modifier.padding(top = 8.dp).size(34.dp).clip(CircleShape).background(Color.White).border(3.dp, Ink.ink, CircleShape))
                            }
                        }
                    }
                }
            }
        }
        val c = ramp(t, .24f, .30f) * (1 - ramp(t, .94f, 1f))
        Text(cap, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, maxLines = 1,
            modifier = Modifier.padding(top = 10.dp).graphicsLayer { alpha = c; translationY = 14f * (1 - c) }.clip(RoundedCornerShape(50)).background(Ink.localT).padding(horizontal = 12.dp, vertical = 5.dp))
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

// ── 4. сказал — сделано: волна → реплика → две строки с галочками → «без интернета»

@Composable
private fun BoxScope.SceneDone() {
    val t = loop(4000)
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
            val up = ramp(t, .80f, .86f)
            Text(stringResource(R.string.intro_s4_up), fontSize = 11.sp, color = Ink.server, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 28.dp)
                .graphicsLayer { alpha = up; scaleX = .9f + .1f * up; scaleY = .9f + .1f * up }.clip(RoundedCornerShape(50)).background(Ink.serverT).padding(horizontal = 10.dp, vertical = 4.dp))
            val off = ramp(t, .72f, .78f)
            Text(stringResource(R.string.intro_s4_off), fontSize = 11.sp, color = Ink.soft, modifier = Modifier.align(Alignment.BottomStart)
                .graphicsLayer { alpha = off; scaleX = .9f + .1f * off; scaleY = .9f + .1f * off }.clip(RoundedCornerShape(50)).background(Ink.chipBg).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

private fun lerp(a: Color, b: Color, f: Float) = Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, a.alpha + (b.alpha - a.alpha) * f)

// ── первый запуск, «Голос на телефоне»: речь → GigaAM на телефоне → текст; облако зачёркнуто (intro.css, .scv) ──

@Composable
fun VoiceArt() {
    val t = loop(5000)
    Column(Modifier.height(280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
            Text("☁", fontSize = 40.sp, lineHeight = 40.sp, color = Color(0xFFB9C0CC))
            val x = ramp(t, .46f, .54f) * (1 - ramp(t, .96f, 1f))
            Canvas(Modifier.size(52.dp, 44.dp)) {
                if (x > 0f) rotate(-20f) {
                    val y = size.height / 2
                    drawLine(Color(0xFFC9483B), Offset(0f, y), Offset(size.width * x, y), 3.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
        Canvas(Modifier.size(2.dp, 30.dp)) {
            var y = 0f
            while (y < size.height) { drawRect(Color(0xB3B9C0CC), Offset(0f, y), Size(size.width, 4.dp.toPx())); y += 8.dp.toPx() }
        }
        Column(Modifier.width(240.dp).weight(1f).graphicsLayer { shadowElevation = 16f; shape = RoundedCornerShape(24.dp); clip = true }
            .background(Ink.surf).border(3.dp, Ink.ink, RoundedCornerShape(24.dp)).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.localT).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                val glow = window(t, .14f, .24f, .40f, .50f)
                Box(Modifier.size(30.dp).drawBehind { drawRoundRect(Ink.local.copy(alpha = .35f * glow), Offset(-6.dp.toPx(), -6.dp.toPx()), Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())) }
                    .clip(RoundedCornerShape(8.dp)).background(Ink.ink).padding(8.dp)) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).background(Ink.local))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.onb_voice_model), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Ink.ink)
                    Text(stringResource(R.string.onb_voice_model_sub), fontSize = 11.sp, color = Ink.localD)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val eq = ramp(t, .06f, .10f) * (1 - ramp(t, .38f, .44f))
                DictEq(eq, bars = 9, maxH = 38f, w = 5.dp)
                val q = stringResource(R.string.intro_s1_q)
                val n = (q.length * ramp(t, .42f, .62f)).toInt()
                if (n > 0) Text(q.take(n), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = 1 - ramp(t, .96f, 1f) })
            }
        }
        val l = ramp(t, .52f, .58f) * (1 - ramp(t, .96f, 1f))
        Text(stringResource(R.string.onb_voice_lock), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink.localD, maxLines = 1,
            modifier = Modifier.padding(top = 10.dp).graphicsLayer { alpha = l; translationY = 12f * (1 - l) }.clip(RoundedCornerShape(50)).background(Ink.localT).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}
