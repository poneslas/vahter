// Перенесено из Гига Писаря для Android 0.2.11 (MIT, (c) 2026 moznoazachem), github.com/moznoazachem/giga-pisar-android.
// Правки — только пакет, строки и то, что помечено «Вахтёр:».
package ru.poneslas.vahter.pisar.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator

/**
 * The lock above the floating button while it records: an open lock with an arrow up
 * ("slide here for hands-free"), and a closed green lock once the recording is locked.
 */
class LockBadge(
    context: Context,
) : View(context) {
    companion object {
        const val WIDTH_DP = 44
        const val HEIGHT_DP = 76
        const val SHADOW_DP = 4
    }

    private val density = resources.displayMetrics.density

    var locked = false
        set(value) {
            val was = field
            field = value
            // Вахтёр: замок не «перещёлкивается» мгновенно — таблетка оседает в кружок, дужка защёлкивается, цвет зеленеет.
            anim?.cancel()
            if (value && !was) {
                morph = 0f
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 220
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { morph = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else morph = if (value) 1f else 0f
            invalidate()
        }

    /** Вахтёр: 0 — открытая таблетка, 1 — закрытый кружок. */
    private var morph = 0f
    private var anim: ValueAnimator? = null

    /** Below the button (no room above): the arrow points down and the lock is at the bottom. */
    var pointsDown = false
        set(value) {
            field = value
            invalidate()
        }

    /** How far the finger went toward the lock, 0..1: the arrow climbs as it does. */
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = SHADOW_DP * density
        val w = width.toFloat()
        val cx = w / 2f
        val color = if (locked) ArgbEvaluator().evaluate(morph, 0xFF5C6358.toInt(), 0xFF2E6B30.toInt()) as Int else 0xFF5C6358.toInt()
        icon.color = color
        stroke.color = color

        if (locked) {
            // A round badge at the end nearest the button. Вахтёр: таблетка сжимается к кнопке, дужка опускается — защёлкнулся.
            val r = (w - 2 * pad) / 2f
            val full = RectF(pad, pad, w - pad, height - pad)
            val end = if (pointsDown) RectF(pad, pad, w - pad, pad + 2 * r) else RectF(pad, height - pad - 2 * r, w - pad, height - pad)
            val box = RectF(full.left, full.top + (end.top - full.top) * morph, full.right, full.bottom + (end.bottom - full.bottom) * morph)
            canvas.drawRoundRect(RectF(box.left, box.top + 1.5f * density, box.right, box.bottom + 1.5f * density), r, r, shadow)
            canvas.drawRoundRect(box, r, r, fill)
            val cyOpen = if (pointsDown) full.bottom - 24 * density else full.top + 24 * density
            val cyEnd = end.centerY()
            drawLock(canvas, cx, cyOpen + (cyEnd - cyOpen) * morph, open = false, lift = 4 * density * (1f - morph))
            return
        }

        val box = RectF(pad, pad, w - pad, height - pad)
        val r = box.width() / 2f
        canvas.drawRoundRect(RectF(box.left, box.top + 1.5f * density, box.right, box.bottom + 1.5f * density), r, r, shadow)
        canvas.drawRoundRect(box, r, r, fill)
        val dir = if (pointsDown) 1f else -1f
        drawLock(canvas, cx, if (pointsDown) box.bottom - 24 * density else box.top + 24 * density, open = true)

        // The arrow moves toward the lock as the finger does.
        val start = if (pointsDown) box.top + 18 * density else box.bottom - 18 * density
        val ay = start + dir * progress * 10 * density
        stroke.strokeWidth = 2.2f * density
        stroke.alpha = (140 + 115 * progress).toInt()
        val a = 5 * density
        canvas.drawLine(cx - a, ay - dir * a * 0.6f, cx, ay + dir * a * 0.4f, stroke)
        canvas.drawLine(cx, ay + dir * a * 0.4f, cx + a, ay - dir * a * 0.6f, stroke)
        stroke.alpha = 255
    }

    private fun drawLock(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        open: Boolean,
        lift: Float = if (open) 4 * density else 0f,
    ) {
        val d = density
        val body = RectF(cx - 7 * d, cy - 2 * d, cx + 7 * d, cy + 9 * d)
        canvas.drawRoundRect(body, 2.5f * d, 2.5f * d, icon)
        stroke.strokeWidth = 2.4f * d
        val arc = RectF(cx - 4.5f * d, cy - 10 * d - lift, cx + 4.5f * d, cy - 1 * d - lift)
        canvas.drawArc(arc, 180f, 180f, false, stroke)
        // Left leg always; the right one only when closed (the open shackle swings free).
        canvas.drawLine(arc.left, arc.centerY(), arc.left, body.top, stroke)
        if (!open) canvas.drawLine(arc.right, arc.centerY(), arc.right, body.top, stroke)
        // Keyhole.
        icon.color = Color.WHITE
        canvas.drawCircle(cx, cy + 3.5f * d, 1.6f * d, icon)
        icon.color = stroke.color
    }
}
