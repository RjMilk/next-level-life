package com.nextlevellife.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nextlevellife.app.data.Store

/** Простая навигация без библиотек: текущий экран хранится строкой. */
class MainActivity : ComponentActivity() {

    /** Увеличивается в onResume, чтобы главный экран перепроверил разрешения. */
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        setContent {
            NextLevelTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var screen by rememberSaveable { mutableStateOf(HOME) }
                    val goHome = { screen = HOME }
                    if (screen != HOME) BackHandler(onBack = goHome)

                    Box(Modifier.safeDrawingPadding()) {
                        when {
                            screen == HOME -> HomeScreen(
                                resumeCount = resumeCount,
                                onExercise = { screen = "$EXERCISE$it" },
                                onMinotaur = { screen = MINOTAUR },
                                onApps = { screen = APPS },
                            )
                            screen == APPS -> AppsScreen(onBack = goHome)
                            screen == MINOTAUR -> MinotaurScreen(onDone = goHome)
                            screen.startsWith(EXERCISE) -> ExerciseScreen(
                                exerciseId = screen.removePrefix(EXERCISE),
                                onDone = goHome,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    private companion object {
        const val HOME = "home"
        const val APPS = "apps"
        const val MINOTAUR = "minotaur"
        const val EXERCISE = "exercise:"
    }
}
