package io.embodit.regionsnap

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.regionsnap.ui.RsScreen
import io.embodit.regionsnap.ui.components.PanelSizes
import io.embodit.regionsnap.ui.window.PANEL_ID
import io.embodit.regionsnap.ui.window.RsWindow

/**
 * App entry (design container STAGE_MIXED, skeleton D): the whole app is Full Space, one Mixed Stage (passthrough, the
 * frame, the notes) and one system Planar window opened from the Stage (D8 / D21: the system moves / closes it).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { RsScreen() }
        WindowContainer(
            PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = PanelSizes.WINDOW_W.dp, height = PanelSizes.WINDOW_H.dp),
        ) { RsWindow() }
    }
