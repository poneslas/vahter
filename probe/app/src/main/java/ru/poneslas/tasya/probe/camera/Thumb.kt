package ru.poneslas.tasya.probe.camera

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Миниатюра вложения в листе: картинка — превью, остальное — имя и размер. Общая для П6 и П8. */
@Composable
fun AttachmentThumb(path: String, caption: String = "") {
    val f = File(path)
    val bmp = remember(path) {
        runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 8 }) }.getOrNull()
    }
    Column {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.height(120.dp)) }
        Text("Вложение: ${f.name}, ${f.length() / 1024} КБ" + if (caption.isNotEmpty()) " · $caption" else "", fontSize = 12.sp)
    }
}
