package com.nextlevellife.app.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Вибрация как подтверждение касания: во время подхода на экран смотреть некогда. */
class Haptics(private val view: View) {
    /** Короткий отклик: +1 повторение, нажатие клавиши. */
    fun tap() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Заметный отклик: подход записан, шаг комплекса выполнен, отмена. */
    fun strong() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
