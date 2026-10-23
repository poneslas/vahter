package ru.poneslas.vahter.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import ru.poneslas.vahter.app.MainActivity
import ru.poneslas.vahter.App
import ru.poneslas.vahter.core.LockScope
import ru.poneslas.vahter.core.ReturnToAssistant
import ru.poneslas.vahter.core.Modes
import ru.poneslas.vahter.core.Primary
import ru.poneslas.vahter.tr
import ru.poneslas.vahter.R
import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import java.util.concurrent.CopyOnWriteArrayList

/** Сервис помощника. Живёт, пока Вахтёр выбран цифровым помощником. */
class AssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Sheet.service = this
    }

    override fun onShutdown() {
        Sheet.service = null
        super.onShutdown()
    }

    /** Самая ранняя точка открытия (на 10–60 мс раньше onShow): здесь — ранний старт микрофона (веха 2). */
    override fun onPrepareToShowSession(args: Bundle, showFlags: Int) {
        Sheet.prepareHooks.forEach { runCatching(it) }
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle().apply { putString("src", "keyguard") }, 0)
    }
}

class SessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** Связь сервиса, сессии и приложения — всё в одном процессе. */
object Sheet {
    @Volatile var service: AssistService? = null
    val visible = MutableStateFlow(false)
    @Volatile var session: AssistSession? = null
    /** Экран листа, пока он жив (на экране или в своей задаче за другим приложением). */
    @Volatile var activity: SheetActivity? = null
    val prepareHooks = CopyOnWriteArrayList<() -> Unit>()
    /** Счётчик «начать запись без рук» — меняется при вызове кнопкой. */
    val autoListen = MutableStateFlow(0)
    /** Камера в листе открыта. */
    val camera = MutableStateFlow(false)
    /** Что делает «назад», пока открыта камера (ставит экран камеры). */
    @Volatile var cameraBack: (() -> Unit)? = null

    /** Свернуть приложение обратно в лист (Архитектура 3.1: finish + showSession). */
    fun show(args: Bundle = Bundle()) = service?.showSession(args, 0)

    /** Убрать лист сразу, без анимации (поверх уже нарисовано приложение). */
    fun close() { activity?.finishNow(); session?.hide() }

    /** Один раз — подсказка «Так открывается по боковой кнопке». */
    val sideCue = MutableStateFlow(false)
    /** Свернули из приложения: лист появляется во весь экран и садится на свою высоту. */
    @Volatile var shrinkOnShow = false
    /** При первом закрытии листа после мастера — «Вахтёр на месте». */
    @Volatile var firstClose = false
    /** Камеру разрешили из листа (системное окно — в приложении): вернувшийся лист сразу открывает её, второй раз жать не нужно. */
    @Volatile var cameraAfterShow = false

    /** Экран мозга в приложении: из листа — с возвратом в лист, иначе — обычной страницей. */
    fun openBrain(ctx: Context) {
        val a = activity
        if (visible.value && a != null) a.openApp(settings = false, brain = true)
        else ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, MainActivity.brainPage()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Сессия помощника — только вход: принимает вызов, решает режим, камеру и запись (как раньше) и открывает экран листа
 * [SheetActivity] через startAssistantActivity. Своего окна не рисует: окно сессии не задача — жесты листали приложение
 * под ним, а системные окна разрешений уходили под него (Архитектура 3).
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {

    override fun onCreate() {
        super.onCreate()
        Sheet.session = this
    }

    /** Своего окна у сессии нет: иначе оно лежит выше всех приложений — и листа, и системного окна разрешения (документация setUiEnabled). */
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onCreateContentView(): View? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val app = App.instance
        val conv = app.conversation
        val settings = conv.state.value.settings
        val src = args?.getString("src")
        // На заблокированном экране по умолчанию ничего не открывается: сначала разблокировка (Продукт 5.7).
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (locked && settings.lockScope == LockScope.OFF) {
            runCatching { startAssistantActivity(Intent(context, UnlockActivity::class.java)) }
            hide()
            return
        }
        // Вызов помощника: боковая кнопка, жест, экран блокировки, плитка в шторке. Уведомление, приложение, камера, выбор фото — не вызов.
        val call = src == null || src == "unlock" || src == "keyguard" || src == "tile" || src == "assist"
        // Режим запоминается; после 30 минут без дела кнопка снова открывает Ассистента (Продукт 5.17).
        if (call && src != "unlock") {
            val idle = System.currentTimeMillis() - conv.state.value.saved.lastUse
            val back = when (settings.returnToAssistant) {
                ReturnToAssistant.ALWAYS -> true
                ReturnToAssistant.NEVER -> false
                ReturnToAssistant.MIN30 -> idle > conv.IDLE_MS
            }
            // Чистый лист после 30 минут без дела — у всех режимов (Conversation.freshen; та же проверка — при каждом показе листа и приложения).
            conv.freshen()
            if (back) conv.selectMode(Modes.MAIN)
        }
        // Вызов кнопкой (не из уведомления и не из приложения): голосовой режим сразу слушает без рук.
        val cur = conv.state.value.current
        if (call) {
            // Повторный вызов при открытой камере закрывает её; фото-режим откроет камеру заново ниже.
            Sheet.camera.value = false
            // Главное действие режима при вызове: фото — сразу камера; голос — сразу слушает, если включено «Слушать сразу при вызове».
            if (cur.primary == Primary.PHOTO && cur.accepts.photo &&
                context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) Sheet.camera.value = true
            // Голос не скачан — не слушаем и не пишем об этом: строка над лентой уже говорит «Голос не скачан · Скачать».
            else if (cur.primary == Primary.VOICE && settings.listenOnCall && app.voice.installed.value) Sheet.autoListen.value++
        }
        if (Sheet.cameraAfterShow) {
            Sheet.cameraAfterShow = false
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) Sheet.camera.value = true
        }
        // Лист — экран своей задачи; уже открыт (за другим приложением) — тот же экран выходит вперёд (onNewIntent).
        // Обычный запуск, не startAssistantActivity: задача «помощника» (ACTIVITY_TYPE_ASSISTANT) лаунчер пропускает при
        // переключении свайпом — с листа уходили и не возвращались (эмулятор, 21.10). Пока сессия показана, запуск из фона разрешён.
        val sheet = Intent(context, SheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(sheet) }.isFailure && runCatching { startAssistantActivity(sheet) }.isFailure) hide()
    }

    override fun onDestroy() {
        if (Sheet.session === this) Sheet.session = null
        super.onDestroy()
    }

    /** Блокировка: лист над ней не оставляем (разрешённое на блокировке открывается заново по кнопке). */
    override fun onLockscreenShown() { Sheet.activity?.finishNow(); hide() }
}

object SheetEvents {
    @Volatile var onHide: (() -> Unit)? = null
}
