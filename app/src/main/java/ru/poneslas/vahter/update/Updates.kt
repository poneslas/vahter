package ru.poneslas.vahter.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Обновления как в Docsy: свой манифест `update.json` в репозитории (версия, ссылка на APK в Releases, sha256, что нового),
 * проверка при возврате в приложение — не чаще раза в сутки, и всегда — при открытии «О программе». Скачивание — своим HTTP в кэш
 * с процентом и проверкой sha256; установка — системным установщиком (ACTION_VIEW + FileProvider), подпись проверяет сам Android
 * (наш выпускной ключ). Разрешение «Установка неизвестных приложений» — по ходу, после возврата установка продолжается сама.
 */
object Updates {
    /** Манифест и откуда разрешено качать: публичный репозиторий Vahter (Бэклог 22.10). */
    const val MANIFEST = "https://raw.githubusercontent.com/poneslas/vahter/main/update.json"
    private const val APK_PREFIX = "https://github.com/poneslas/vahter/releases/download/"
    private const val CHECK_EVERY_MS = 24 * 60 * 60_000L

    data class Release(val version: String, val code: Int, val url: String, val sha256: String, val size: Long, val notes: String)

    sealed interface State {
        data object Unknown : State
        data object Checking : State
        data object UpToDate : State
        data object CheckFailed : State
        data class Available(val r: Release) : State
        data class Downloading(val r: Release, val pct: Int) : State
        data class ReadyToInstall(val r: Release, val needsPermission: Boolean) : State
        data class DownloadFailed(val r: Release, val badFile: Boolean) : State
    }

    val state = MutableStateFlow<State>(State.Unknown)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private fun installedCode(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** Вернулись в приложение: проверить, если с прошлого раза прошли сутки. */
    fun onAppResume(ctx: Context) {
        val last = prefs(ctx).getLong("checked", 0)
        if (System.currentTimeMillis() - last > CHECK_EVERY_MS && state.value !is State.Downloading) checkNow(ctx)
        // вернулись из «Установка неизвестных приложений» — продолжить установку
        (state.value as? State.ReadyToInstall)?.let { if (it.needsPermission && ctx.packageManager.canRequestPackageInstalls()) install(ctx) }
    }

    fun checkNow(ctx: Context) {
        if (state.value is State.Downloading || state.value is State.Checking) return
        val app = ctx.applicationContext
        state.value = State.Checking
        job = scope.launch {
            state.value = runCatching { fetch(app) }.fold(
                { r -> prefs(app).edit().putLong("checked", System.currentTimeMillis()).apply(); if (r != null && r.code > installedCode(app)) State.Available(r) else State.UpToDate },
                { State.CheckFailed })
        }
    }

    private fun fetch(ctx: Context): Release? {
        val c = (URL(MANIFEST).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 15_000; useCaches = false }
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val o = json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
            val apk = o["apk"]!!.jsonObject
            val url = apk["url"]!!.jsonPrimitive.content
            require(url.startsWith(APK_PREFIX)) { "чужая ссылка" }
            val lang = if (Locale.getDefault().language == "en") "en" else "ru"
            val notes = o["notes"]?.jsonObject?.let { n -> (n[lang] ?: n["ru"])?.jsonPrimitive?.content }.orEmpty()
            return Release(o["version"]!!.jsonPrimitive.content, o["versionCode"]!!.jsonPrimitive.int, url,
                apk["sha256"]!!.jsonPrimitive.content.lowercase(), apk["size"]?.jsonPrimitive?.long ?: 0, notes)
        } finally { c.disconnect() }
    }

    private fun file(ctx: Context, r: Release) = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "vahter-${r.version}.apk")

    /** Скачать (с процентом, sha256 сверяется) и сразу предложить установку. Ушли с экрана — качается дальше. */
    fun download(ctx: Context) {
        val r = when (val s = state.value) { is State.Available -> s.r; is State.DownloadFailed -> s.r; else -> return }
        val app = ctx.applicationContext
        state.value = State.Downloading(r, 0)
        job = scope.launch {
            val out = file(app, r)
            val ok = runCatching {
                val c = (URL(r.url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true }
                val md = MessageDigest.getInstance("SHA-256")
                // «Отменить» — рвём соединение, чтобы чтение не висело до таймаута.
                val h = coroutineContext[Job]?.invokeOnCompletion { c.disconnect() }
                try {
                    if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                    var got = 0L
                    c.inputStream.use { i -> out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { ensureActive(); val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); md.update(buf, 0, n); got += n
                            ensureActive(); if (total > 0) state.value = State.Downloading(r, (got * 100 / total).toInt().coerceIn(0, 100)) }
                    } }
                } finally { h?.dispose(); c.disconnect() }
                md.digest().joinToString("") { "%02x".format(it) } == r.sha256
            }
            // Отменили — файл в корзину, без «Не скачалось» и без установщика.
            if (!isActive) { out.delete(); (state.value as? State.Downloading)?.let { state.value = State.Available(it.r) }; return@launch }
            when {
                ok.isFailure -> { out.delete(); state.value = State.DownloadFailed(r, badFile = false) }
                ok.getOrNull() == false -> { out.delete(); state.value = State.DownloadFailed(r, badFile = true) }
                else -> { state.value = State.ReadyToInstall(r, needsPermission = false); install(app) }
            }
        }
    }

    fun cancel() { job?.cancel(); (state.value as? State.Downloading)?.let { state.value = State.Available(it.r) } }

    /** Системный установщик; нет разрешения на установку — экран разрешения, после возврата — сами продолжим (onAppResume). */
    fun install(ctx: Context) {
        val s = state.value as? State.ReadyToInstall ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            state.value = s.copy(needsPermission = true)
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        state.value = s.copy(needsPermission = false)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file(ctx, s.r))
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

}
