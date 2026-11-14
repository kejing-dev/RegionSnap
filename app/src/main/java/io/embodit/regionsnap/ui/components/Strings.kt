package io.embodit.regionsnap.ui.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.regionsnap.ui.Lang
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中 / EN), switched live (Rehearsal Hall Strings.kt): the
 * screen provides a Resources object for that locale and every panel reads its strings from it.
 */
val LocalRsResources = staticCompositionLocalOf<Resources> { error("LocalRsResources not provided") }

fun localizedResources(context: Context, lang: Lang): Resources {
    val tag =
        when (lang) {
            Lang.SYSTEM -> return context.resources
            Lang.ZH -> "zh-CN"
            Lang.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalRsResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalRsResources.current.getString(id, *args)

/**
 * Sizes, dp. All 2D content is ONE system Planar window (design windowModel, D8 / D21): 600 x 680 (design 560 x 520, grown
 * so the settings page holds every row on its first screen and the preview fits without scrolling; deviation logged).
 * On the Stage: the shutter bar under the frame, the pin bar over a note, the hint plate (S00 / S22), all at 1.25 scale (1 mm per dp: 16 sp text is about 1.6 cm, design typography 3D text >= 1.4 cm).
 */
object PanelSizes {
    const val WINDOW_W = 600f
    const val WINDOW_H = 680f
    const val SCALE = 1.25f
    const val BAR_W = 720f
    const val BAR_H = 72f
    const val PINBAR_W = 540f
    const val PINBAR_H = 72f
    const val PLATE_W = 600f
    const val PLATE_H = 110f
}
