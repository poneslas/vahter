package ru.poneslas.vahter.probe.session

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import ru.poneslas.vahter.probe.assist.ProbeSession

/**
 * Панель П3 в листе: поле и список из 50 строк. Отдаёт наружу состояние списка и scope композиции —
 * анимированному скроллу нужны часы кадров, которых нет в App.scope.
 */
internal object P3Panel {
    @Volatile var listState: LazyListState? = null
    @Volatile var scope: CoroutineScope? = null
    @Volatile var focus: FocusRequester? = null
    @Volatile var keyboard: SoftwareKeyboardController? = null
    /** Низ поля ввода в координатах окна, px. */
    @Volatile var fieldBottom = 0f

    fun reset() {
        listState = null; scope = null; focus = null; keyboard = null; fieldBottom = 0f
    }

    val content: @Composable (ProbeSession) -> Unit = { _ ->
        val state = rememberLazyListState()
        val sc = rememberCoroutineScope()
        val fr = remember { FocusRequester() }
        val kb = LocalSoftwareKeyboardController.current
        SideEffect { listState = state; scope = sc; focus = fr; keyboard = kb }
        var text by remember { mutableStateOf("") }
        OutlinedTextField(
            text, { text = it },
            Modifier.fillMaxWidth().focusRequester(fr).onGloballyPositioned { fieldBottom = it.boundsInWindow().bottom },
            placeholder = { Text("П3: поле для клавиатуры") },
        )
        LazyColumn(Modifier.fillMaxWidth().height(220.dp), state) {
            items(50) { Text("Строка ${it + 1} — прокрутка для подсчёта кадров", Modifier.padding(8.dp)) }
        }
    }
}
