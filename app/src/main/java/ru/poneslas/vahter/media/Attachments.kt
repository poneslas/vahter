package ru.poneslas.vahter.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Вложения (Архитектура 2.2): подготовка общая для всех мозгов — обрезка, пометки впечатываются,
 * длинная сторона 1600 px, JPEG 80. DeepSeek: ~1000 токенов на картинку при любом размере (замер 18.10).
 */
object Attachments {
    fun dir(ctx: Context) = File(ctx.filesDir, "attachments").apply { mkdirs() }

    /** Пометка в долях кадра 0..1. */
    sealed interface Mark {
        data class Circle(val points: List<Pair<Float, Float>>) : Mark
        data class Arrow(val from: Pair<Float, Float>, val to: Pair<Float, Float>) : Mark
    }

    data class Crop(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Кадр с пометками и обрезкой → JPEG-файл приложения. */
    suspend fun render(ctx: Context, src: Bitmap, marks: List<Mark>, crop: Crop?): File = withContext(Dispatchers.Default) {
        val w = src.width; val h = src.height
        val drawn = src.copy(Bitmap.Config.ARGB_8888, true)
        if (marks.isNotEmpty()) {
            val c = Canvas(drawn)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFF3B30.toInt(); style = Paint.Style.STROKE; strokeWidth = max(w, h) / 160f
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            for (m in marks) when (m) {
                is Mark.Circle -> {
                    val path = Path()
                    m.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h) }
                    c.drawPath(path, p)
                }
                is Mark.Arrow -> {
                    val (x1, y1) = m.from.first * w to m.from.second * h
                    val (x2, y2) = m.to.first * w to m.to.second * h
                    c.drawLine(x1, y1, x2, y2, p)
                    val a = atan2(y2 - y1, x2 - x1); val len = max(w, h) / 30f
                    c.drawLine(x2, y2, x2 - len * cos(a - 0.5f), y2 - len * sin(a - 0.5f), p)
                    c.drawLine(x2, y2, x2 - len * cos(a + 0.5f), y2 - len * sin(a + 0.5f), p)
                }
            }
        }
        val cropped = if (crop != null) Bitmap.createBitmap(drawn,
            (crop.x * w).toInt().coerceIn(0, w - 1), (crop.y * h).toInt().coerceIn(0, h - 1),
            (crop.w * w).toInt().coerceIn(1, w - (crop.x * w).toInt()), (crop.h * h).toInt().coerceIn(1, h - (crop.y * h).toInt())) else drawn
        val out = File(dir(ctx), "img_${System.currentTimeMillis()}_${(Math.random() * 1e6).toInt()}.jpg")
        scaled(cropped, 1600).let { b -> out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        out
    }

    /** Файл из галереи → наш JPEG 1600 px. */
    suspend fun fromUri(ctx: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        val out = File(dir(ctx), "pick_${System.currentTimeMillis()}_${(Math.random() * 1e6).toInt()}.jpg")
        scaled(bmp, 1600).let { b -> out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        out
    }

    private fun scaled(b: Bitmap, side: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= side) return b
        val k = side.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    /** data:image/jpeg;base64,… — так картинка уходит прямому API. */
    fun dataUrl(f: File): String = "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)

    /** Миниатюра для ленты. */
    fun thumb(path: String, side: Int = 360): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= side) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

}
