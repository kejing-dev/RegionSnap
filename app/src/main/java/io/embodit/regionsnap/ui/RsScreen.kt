package io.embodit.regionsnap.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.regionsnap.R
import io.embodit.regionsnap.platform.RsLog
import io.embodit.regionsnap.ui.components.Key
import io.embodit.regionsnap.ui.components.KeyKind
import io.embodit.regionsnap.ui.components.LocalRsResources
import io.embodit.regionsnap.ui.components.PanelSizes
import io.embodit.regionsnap.ui.components.fits
import io.embodit.regionsnap.ui.components.localizedResources
import io.embodit.regionsnap.ui.components.rs
import io.embodit.regionsnap.ui.debug.LocalProbePanel
import io.embodit.regionsnap.ui.debug.Probe
import io.embodit.regionsnap.ui.debug.probe
import io.embodit.regionsnap.ui.scene.PanelIds
import io.embodit.regionsnap.ui.scene.RsFrameSystem
import io.embodit.regionsnap.ui.scene.RsScene
import io.embodit.regionsnap.ui.theme.RsColors
import io.embodit.regionsnap.ui.theme.RsTheme
import io.embodit.regionsnap.ui.theme.RsType
import io.embodit.regionsnap.ui.window.WindowCtl

/**
 * The Stage screen: wires [RsController] (state), [RsScene] (entities, per-frame input) and the three small Stage panels,
 * and opens the main window once the head is level (S00).
 */
@Composable
fun RsScreen() {
    val context = LocalContext.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        RsController.start(context)
        true
    }
    val state by RsController.state.collectAsStateWithLifecycle()
    val scene = remember { RsScene() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        registerSystem<RsFrameSystem>()
        Probe.host = object : Probe.Host { override fun scene(): RsScene = scene }
        // S23: background stops shooting and the providers; back: providers restart, the frame is still there
        val observer =
            LifecycleEventObserver { _, event ->
                RsLog.i("LIFECYCLE event=$event")
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        RsController.onEvent(Ui.Background(true))
                        scene.pause()
                    }
                    Lifecycle.Event.ON_START -> {
                        scene.resume()
                        RsController.onEvent(Ui.Background(false))
                    }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        RsLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<RsFrameSystem>()
            scene.detach()
            RsLog.i("STAGE disposed")
        }
    }

    val navigator = LocalSpatialNavigator.current
    DisposableEffect(navigator) {
        WindowCtl.navigator = navigator
        onDispose { if (WindowCtl.navigator === navigator) WindowCtl.navigator = null }
    }
    LaunchedEffect(Unit) { WindowCtl.openWindow("stage") }

    val resources = remember(state.prefs.lang, context) { localizedResources(context, state.prefs.lang) }
    CompositionLocalProvider(LocalRsResources provides resources) {
        RsTheme {
            val bindTick = scene.bindTick.intValue
            SpatialView(
                modifier = Modifier.fillMaxSize(),
                attachments = {
                    AttachmentPanel(PanelIds.BAR) { Pool(PanelIds.BAR, scene) { ShutterBar(state) } }
                    AttachmentPanel(PanelIds.PINBAR) { Pool(PanelIds.PINBAR, scene) { PinBar(state) } }
                    AttachmentPanel(PanelIds.PLATE) { Pool(PanelIds.PLATE, scene) { HintPlate(state) } }
                },
                update = { _, attachments ->
                    @Suppress("UNUSED_EXPRESSION") bindTick
                    scene.bindPanels(attachments)
                },
                initial = { content, attachments -> scene.attach(content, attachments, context) },
            )
        }
    }
}

/** Root of a pooled panel: content composes once the panel is in view (Spatial Ruler finding 3). */
@Composable
private fun Pool(id: String, scene: RsScene, content: @Composable () -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(Modifier.size(sizeOf(id).first.dp, sizeOf(id).second.dp), contentAlignment = Alignment.Center) { if (inView) content() }
    }
}

private fun sizeOf(id: String): Pair<Float, Float> = when (id) {
    PanelIds.BAR -> PanelSizes.BAR_W to PanelSizes.BAR_H
    PanelIds.PINBAR -> PanelSizes.PINBAR_W to PanelSizes.PINBAR_H
    else -> PanelSizes.PLATE_W to PanelSizes.PLATE_H
}

private val plate = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(RsColors.PlateInk)

/** The shutter bar under the frame (design panels.shutterBar): 拍 · 重画 · 转 (· 远 近 floating) (· 面板 in S22) · size. */
@Composable
private fun ShutterBar(s: RsUiState) {
    val onEvent: (Ui) -> Unit = RsController::onEvent
    Row(plate.padding(horizontal = 10.dp).probe("bar"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val busy = s.phase == Phase.CAPTURING
        Key(if (busy) "…" else rs(R.string.bar_shoot), "bar_shoot", R.drawable.ic_photo_camera, KeyKind.PRIMARY, 52.dp, enabled = !busy) { onEvent(Ui.Shoot) }
        Key(rs(R.string.bar_redraw), "bar_redraw", null, height = 52.dp) { onEvent(Ui.Redraw) }
        // v0.1.4: turn about the vertical axis 15° a press, and back to facing you
        Key(rs(R.string.bar_left), "bar_left", R.drawable.ic_rotate_left, height = 52.dp) { onEvent(Ui.Rotate(1)) }
        Key(rs(R.string.bar_right), "bar_right", R.drawable.ic_rotate_right, height = 52.dp) { onEvent(Ui.Rotate(-1)) }
        Key(rs(R.string.bar_face), "bar_face", R.drawable.ic_my_location, height = 52.dp) { onEvent(Ui.FaceMe) }
        if (s.frameSurface == io.embodit.regionsnap.domain.Surface.FLOATING) {
            Key(rs(R.string.bar_far), "bar_far", null, height = 52.dp) { onEvent(Ui.Push(0.1f)) }
            Key(rs(R.string.bar_near), "bar_near", null, height = 52.dp) { onEvent(Ui.Push(-0.1f)) }
        } else if (!s.panelOpen && s.panelEverOpened) {
            Key(rs(R.string.bar_panel), "bar_panel", R.drawable.ic_home, height = 52.dp) { onEvent(Ui.OpenPanel) }
        } else {
            Text(text = io.embodit.regionsnap.ui.window.sizeText(s), style = RsType.label, color = RsColors.PlateText, maxLines = 1, modifier = Modifier.width(140.dp).probe("bar_size"), textAlign = TextAlign.Center, onTextLayout = fits("bar/size"))
        }
    }
}

/** The pinned note's bar (design pin.interact): 打开 · 取下 · 移到面前 · 关. */
@Composable
private fun PinBar(s: RsUiState) {
    val id = s.pinBar ?: return
    val onEvent: (Ui) -> Unit = RsController::onEvent
    Row(plate.padding(horizontal = 10.dp).probe("pinbar"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Key(rs(R.string.pin_open), "pin_open", R.drawable.ic_open_in_full, KeyKind.PRIMARY, 52.dp) { onEvent(Ui.OpenShot(id)); onEvent(Ui.OpenPanel) }
        Key(rs(R.string.pin_take), "pin_take", R.drawable.ic_keep_off, height = 52.dp) { onEvent(Ui.Unpin(id)) }
        Key(rs(R.string.pin_here), "pin_here", R.drawable.ic_my_location, height = 52.dp) { onEvent(Ui.PinFront(id)) }
        Key(rs(R.string.pin_close), "pin_close", null, height = 52.dp) { onEvent(Ui.PinBarClose) }
    }
}

/** Up to this the plate says "a little"; past it, the angle. */
private const val TURN_LITTLE_DEG = 20

/** The hint plate (S00 平视前方 / 往左看一点 while the window waits for a spot clear of the frame / S22 面板关了 or 收起 + 打开面板). */
@Composable
private fun HintPlate(s: RsUiState) {
    val level = WindowCtl.levelHint
    val turn = WindowCtl.turnHint
    Column(plate.padding(horizontal = 16.dp, vertical = 6.dp).probe("plate"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (turn != 0 && !level) {
            // a small turn: "look a little left"; a big one: the arrow and about how far (q2-2)
            val need = WindowCtl.turnNeedDeg
            val turnText = if (need <= TURN_LITTLE_DEG) rs(if (turn > 0) R.string.look_left else R.string.look_right)
            else rs(if (turn > 0) R.string.turn_left_deg else R.string.turn_right_deg, need)
            Text(text = turnText, style = RsType.title, color = RsColors.PlateText, maxLines = 1, onTextLayout = fits("plate/turn"))
            Text(text = rs(R.string.look_turn_2), style = RsType.label, color = RsColors.Dim, maxLines = 1, onTextLayout = fits("plate/turn2"))
        } else if (level || !s.panelEverOpened) {
            Text(text = rs(R.string.look_level), style = RsType.title, color = RsColors.PlateText, maxLines = 1, onTextLayout = fits("plate/level"))
            Text(text = rs(R.string.look_level_2), style = RsType.label, color = RsColors.Dim, maxLines = 1, onTextLayout = fits("plate/level2"))
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(if (s.panelTucked) R.string.panel_tucked else R.string.panel_closed), style = RsType.body, color = RsColors.PlateText, maxLines = 3, modifier = Modifier.width(380.dp).height(96.dp).padding(top = 4.dp), onTextLayout = fits("plate/closed"))
            Key(rs(R.string.open_panel), "plate_open", R.drawable.ic_home, KeyKind.PRIMARY, 52.dp) { RsController.onEvent(Ui.OpenPanel) }
        }
    }
}
