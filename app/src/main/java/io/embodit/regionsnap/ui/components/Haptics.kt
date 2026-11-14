package io.embodit.regionsnap.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.pico.spatial.ui.platform.HandController
import com.pico.spatial.ui.platform.HandControllerHapticType
import com.pico.spatial.ui.platform.LocalHandControllerHaptic
import com.pico.spatial.ui.platform.SpatialHandControllerHaptic
import io.embodit.regionsnap.platform.RsLog
import io.embodit.regionsnap.ui.RsController

/**
 * Controller haptics (design input.controller.haptics: press edge, level snap, shot): the instance only exists inside a
 * PicoTheme composable, so the window hands it over here; events call it on the main thread, on the aiming hand.
 */
object Haptics {
    private var haptic: SpatialHandControllerHaptic? = null
    private val main = Handler(Looper.getMainLooper())

    /** The aiming controller (the scene sets it from the last trigger). */
    @Volatile var right = true

    @Composable
    fun Bind() {
        val h = LocalHandControllerHaptic.current
        DisposableEffect(h) {
            haptic = h
            RsController.haptic = { kind -> main.post { play(kind) } }
            onDispose { if (haptic === h) haptic = null }
        }
    }

    private fun play(kind: String) {
        val h = haptic ?: return
        val type = when (kind) {
            "shoot" -> HandControllerHapticType.custom(180, 160, 40)
            "level" -> HandControllerHapticType.custom(90, 120, 20)
            else -> HandControllerHapticType.Press
        }
        runCatching { h.feedback(type, if (right) HandController.Right else HandController.Left) }.onFailure { RsLog.w("HAPTIC $kind failed: $it") }
    }
}
