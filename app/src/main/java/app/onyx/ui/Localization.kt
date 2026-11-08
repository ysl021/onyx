package app.onyx.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.onyx.core.AppLanguage

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.RU }

/**
 * A process-wide mirror of the Compose language state. UI text is also needed
 * inside click lambdas and coroutines, where a @Composable getter cannot run.
 */
object AppLanguageState {
    @Volatile
    var current: AppLanguage = AppLanguage.RU
}

fun tr(ru: String, en: String): String =
    if (AppLanguageState.current == AppLanguage.EN) en else ru
