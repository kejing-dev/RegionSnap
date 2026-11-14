package io.embodit.regionsnap.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/**
 * SpatialUI scheme (design palette: PicoTheme default roles, primary = the frame colour): all 16 public roles written out,
 * interaction = the app's primary, the other 15 = the system's own. Noto Sans / Noto Sans SC.
 */
@Composable
fun RsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors =
        remember(system) {
            system.copy(
                fillPrimary = system.fillPrimary,
                fillSecondary = system.fillSecondary,
                fillTertiary = system.fillTertiary,
                fillLight = system.fillLight,
                labelPrimary = system.labelPrimary,
                labelPrimaryLight = system.labelPrimaryLight,
                labelSecondary = system.labelSecondary,
                labelTertiary = system.labelTertiary,
                labelQuaternary = system.labelQuaternary,
                lightenHover = system.lightenHover,
                lightenPressed = system.lightenPressed,
                dividerLine = system.dividerLine,
                interaction = RsColors.Primary,
                passable = system.passable,
                alert = system.alert,
                error = system.error,
            )
        }
    val family = rememberDsFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
