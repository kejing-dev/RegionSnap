package io.embodit.regionsnap.ui.window

import io.embodit.regionsnap.domain.OutRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import io.embodit.regionsnap.BuildConfig
import io.embodit.regionsnap.R
import io.embodit.regionsnap.domain.Px
import io.embodit.regionsnap.domain.Snapper
import io.embodit.regionsnap.domain.Surface
import io.embodit.regionsnap.platform.RsLog
import io.embodit.regionsnap.ui.Card
import io.embodit.regionsnap.ui.Hint
import io.embodit.regionsnap.ui.InputMode
import io.embodit.regionsnap.ui.Lang
import io.embodit.regionsnap.ui.Phase
import io.embodit.regionsnap.ui.RsController
import io.embodit.regionsnap.ui.RsUiState
import io.embodit.regionsnap.ui.Tab
import io.embodit.regionsnap.ui.Ui
import io.embodit.regionsnap.ui.components.Body
import io.embodit.regionsnap.ui.components.HintStrip
import io.embodit.regionsnap.ui.components.Key
import io.embodit.regionsnap.ui.components.KeyKind
import io.embodit.regionsnap.ui.components.LocalRsResources
import io.embodit.regionsnap.ui.components.OptRow
import io.embodit.regionsnap.ui.components.Options
import io.embodit.regionsnap.ui.components.PanelSizes
import io.embodit.regionsnap.ui.components.Small
import io.embodit.regionsnap.ui.components.SwitchBox
import io.embodit.regionsnap.ui.components.Title
import io.embodit.regionsnap.ui.components.WarnCard
import io.embodit.regionsnap.ui.components.fits
import io.embodit.regionsnap.ui.components.localizedResources
import io.embodit.regionsnap.ui.components.rs
import io.embodit.regionsnap.ui.debug.LocalProbePanel
import io.embodit.regionsnap.ui.debug.probe
import io.embodit.regionsnap.ui.theme.RsColors
import io.embodit.regionsnap.ui.theme.RsTheme
import io.embodit.regionsnap.ui.theme.RsType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The main window (design windowModel single_system_window): TabBar 取景 / 已截 / 设置; preview, detail and every
 * confirmation replace the page content in place (no second window). Labels come from the app's language table
 * (factory gate tabbar-lang).
 */
@Composable
fun RsWindow() {
    val context = LocalContext.current
    val state by RsController.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> WindowCtl.onLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val converter = LocalPhysicalLengthConverter.current
    LaunchedEffect(converter) {
        runCatching { converter.dpToLength(1000.dp, LengthUnit.Meters) / 1000f }.getOrNull()?.takeIf { it in 0.0002f..0.005f }?.let {
            WindowCtl.dpM = it
            RsLog.i(String.format(Locale.US, "PANEL dp=%.5f m (%.2f x %.2f m)", it, PanelSizes.WINDOW_W * it, PanelSizes.WINDOW_H * it))
        }
    }
    val resources = remember(state.prefs.lang, context) { localizedResources(context, state.prefs.lang) }
    val onEvent: (Ui) -> Unit = RsController::onEvent
    // pressPriority ①: any pointer on the window marks "on the UI" for 150 ms, so a pinch there never starts a stroke
    val watcher = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                WindowCtl.lastPointerMs = android.os.SystemClock.elapsedRealtime()
            }
        }
    }.pointerInput(Unit) {
        // q2-1: a short tap that no control took (empty glass, a label) while there is no frame yet means "I want the
        // spot behind the window": the window steps aside. Controls consume their release, so they never count.
        awaitPointerEventScope {
            var downAt: Offset? = null
            var downMs = 0L
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Final)
                val c = e.changes.firstOrNull() ?: continue
                if (c.pressed && !c.previousPressed) {
                    downAt = c.position
                    downMs = c.uptimeMillis
                } else if (!c.pressed && c.previousPressed) {
                    val start = downAt
                    downAt = null
                    val s = RsController.state.value
                    val idle = s.tab == Tab.SNAP && !s.hasFrame && (s.phase == Phase.IDLE || s.phase == Phase.AIMING) && s.card == Card.NONE && !s.onboarding
                    if (idle && !c.isConsumed && start != null && (c.position - start).getDistance() < 24.dp.toPx() && c.uptimeMillis - downMs < 500) {
                        RsController.onEvent(Ui.MoveAside("blank tap"))
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalRsResources provides resources, LocalProbePanel provides "window") {
        RsTheme {
            io.embodit.regionsnap.ui.components.Haptics.Bind()
            Box(watcher) {
                MainWindow(
                    pages = listOf(
                        MainWindowPage("snap", rs(R.string.tab_snap), R.drawable.ic_crop_free) { SnapPage(state, onEvent) },
                        MainWindowPage("shots", rs(R.string.tab_shots), R.drawable.ic_photo_library) { ShotsPage(state, onEvent) },
                    ),
                    settings = MainWindowSettings(rs(R.string.tab_settings), R.drawable.ic_settings) { SettingsPage(state, onEvent) },
                    current = when (state.tab) { Tab.SNAP -> "snap"; Tab.SHOTS -> "shots"; Tab.SETTINGS -> SETTINGS_PAGE },
                    refreshKey = listOf(state.tab, state.phase, state.card, state.detail, state.deleteConfirm, state.exitConfirm, state.aboutOpen, state.howToOpen, state.calib, state.onboarding, state.prefs.lang),
                    onSelect = { id -> onEvent(Ui.SelectTab(when (id) { "shots" -> Tab.SHOTS; SETTINGS_PAGE -> Tab.SETTINGS; else -> Tab.SNAP })) },
                )
            }
        }
    }
}

// ================================================================================================= copy helpers

@Composable
private fun hintText(s: RsUiState): String? = when (s.hint) {
    Hint.NONE, Hint.LOOK_LEVEL -> null
    Hint.ONBOARD -> rs(R.string.onboard_1)
    Hint.TOO_SMALL -> rs(R.string.h_too_small)
    Hint.EYE_FALLBACK -> rs(R.string.h_eye_fallback)
    Hint.HAND_LOST -> rs(R.string.h_hand_lost)
    Hint.HEAD_FALLBACK -> rs(R.string.h_head)
    Hint.CAMERA_STARTING -> rs(R.string.h_cam_starting)
    Hint.LOW_RES -> rs(R.string.h_low_res)
    Hint.ANCHORS_LOST -> rs(R.string.h_anchors_lost, s.hintN)
    Hint.PIN_NOT_KEPT -> rs(R.string.h_pin_not_kept)
    Hint.PINS_BEHIND -> rs(R.string.h_pins_behind, s.hintN)
    Hint.PIN_LIMIT -> rs(R.string.h_pin_limit)
    Hint.CALIB_PRE -> rs(when (s.hintN) { 0 -> R.string.h_calib_float; 1 -> R.string.h_calib_far; else -> R.string.h_calib_small })
    Hint.CALIB_RETRY -> rs(R.string.calib_retry)
    Hint.CALIB_FAR -> rs(R.string.h_calib_off)
    Hint.SAVED -> rs(R.string.h_saved)
    Hint.PINNED -> rs(R.string.h_pinned)
}

@Composable
internal fun surfaceText(surface: Surface?, depth: Float): String = when (surface) {
    null -> rs(R.string.sf_none)
    Surface.WALL -> rs(R.string.sf_wall)
    Surface.TABLE -> rs(R.string.sf_table)
    Surface.FLOOR -> rs(R.string.sf_floor)
    Surface.PLANE -> rs(R.string.sf_plane)
    Surface.FLOATING -> rs(R.string.sf_float, m1(depth))
}

private fun m1(v: Float) = String.format(Locale.US, "%.1f", v)

private fun m2(v: Float) = String.format(Locale.US, "%.2f", v)

@Composable
private fun inputText(i: InputMode): String = rs(when (i) {
    InputMode.CONTROLLER -> R.string.in_controller
    InputMode.EYE_HAND -> R.string.in_eye
    InputMode.FINGER -> R.string.in_finger
    InputMode.EYE_NO_HAND -> R.string.in_eye_nohand
    InputMode.HEAD -> R.string.in_head
})

/** The status line, computed from the state machine (rules: no hard-coded status copy, D38). */
@Composable
private fun statusText(s: RsUiState): String {
    val ctrl = s.input == InputMode.CONTROLLER
    return when (s.phase) {
        Phase.DRAWING, Phase.SHRINK -> rs(R.string.st_drawing)
        Phase.ADJUSTING -> rs(R.string.st_adjusting)
        Phase.CAPTURING -> rs(R.string.st_capturing)
        Phase.SAVED -> rs(R.string.st_saved)
        Phase.PINNED -> rs(R.string.st_pinned)
        else -> if (s.hasFrame) rs(if (ctrl) R.string.st_framed_ctrl else R.string.st_framed) else rs(if (ctrl) R.string.st_idle_ctrl else R.string.st_idle)
    }
}

@Composable
internal fun sizeText(s: RsUiState): String =
    if (!s.hasFrame) rs(R.string.size_none)
    else if (s.frameSurface == Surface.FLOATING) rs(R.string.size_float, m1(s.frameDepth))
    else String.format(Locale.US, "%s × %s m", m2(s.frameW), m2(s.frameH))

// ================================================================================================= Snap

@Composable
private fun SnapPage(s: RsUiState, onEvent: (Ui) -> Unit) {
    if (s.phase == Phase.PREVIEW && s.preview != null) return PreviewPage(s, onEvent, calibrating = false)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = statusText(s), style = RsType.titleLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, modifier = Modifier.weight(1f).padding(end = 12.dp).probe("status"), onTextLayout = fits("snap/status"))
            Key(rs(if (s.prefs.muted) R.string.unmute else R.string.mute), "mute", if (s.prefs.muted) R.drawable.ic_volume_up else R.drawable.ic_volume_off) {
                onEvent(Ui.SetPrefs("mute") { it.copy(muted = !it.muted) })
            }
        }
        HintStrip(hintText(s))
        Body(inputText(s.input) + "  ·  " + surfaceText(s.aimSurface, s.aimDepth), "input_line", maxLines = 1)
        Text(text = sizeText(s), style = RsType.title, color = if (s.hasFrame) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.probe("size"), onTextLayout = fits("snap/size"))
        Spacer(Modifier.weight(1f))
        when (s.card) {
            Card.CAMERA_PERMISSION -> WarnCard(rs(R.string.cam_perm), "card_perm") {
                Key(rs(R.string.cam_allow), "cam_allow", R.drawable.ic_photo_camera, KeyKind.PRIMARY, modifier = Modifier.weight(1f)) { onEvent(Ui.CamAllow) }
                Key(rs(R.string.cam_notnow), "cam_notnow", modifier = Modifier.weight(1f)) { onEvent(Ui.CamNotNow) }
            }
            Card.CAMERA_DENIED_FOREVER -> WarnCard(rs(R.string.cam_perm) + "\n" + rs(R.string.cam_forever), "card_forever") {
                Key(rs(R.string.cam_settings), "cam_settings", R.drawable.ic_tune, KeyKind.PRIMARY, modifier = Modifier.weight(1f)) { onEvent(Ui.OpenSettings) }
                Key(rs(R.string.cam_notnow), "cam_notnow", modifier = Modifier.weight(1f)) { onEvent(Ui.CamNotNow) }
            }
            Card.CAMERA_FAILED, Card.CAMERA_STARTING -> WarnCard(rs(if (s.card == Card.CAMERA_STARTING) R.string.cam_starting else R.string.cam_failed), "card_failed") {
                Key(rs(R.string.cam_retry), "cam_retry", R.drawable.ic_replay, KeyKind.PRIMARY, modifier = Modifier.weight(1f)) { onEvent(Ui.CamRetry) }
                Key(rs(R.string.cam_ok), "cam_ok", modifier = Modifier.weight(1f)) { onEvent(Ui.CamDismiss) }
            }
            Card.NONE -> if (!s.onboarding) {
                // the three steps stay on the page as a quiet reminder (keeps the page from a big empty band, D44)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RsColors.KeyFill).padding(14.dp).probe("steps"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Small(rs(R.string.onboard_1), "steps_1", color = RsColors.KeyInk, maxLines = 1)
                    Small(rs(R.string.onboard_2), "steps_2", color = RsColors.KeyInk, maxLines = 2)
                    Small(rs(R.string.onboard_3), "steps_3", color = RsColors.KeyInk, maxLines = 1)
                    Small(rs(R.string.onboard_ctrl), "steps_ctrl", color = RsColors.Dim, maxLines = 2)
                    // q2-1: no frame yet and the window may sit on the very spot to frame: let it step aside
                    if (!s.hasFrame) MoveAsideRow(onEvent)
                }
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RsColors.KeyFill).padding(14.dp).probe("onboarding"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Body(rs(R.string.onboard_1), "onboard_1", color = RsColors.KeyInk, maxLines = 1)
                    Body(rs(R.string.onboard_2), "onboard_2", color = RsColors.KeyInk, maxLines = 2)
                    Body(rs(R.string.onboard_3), "onboard_3", color = RsColors.KeyInk, maxLines = 1)
                    Small(rs(R.string.onboard_ctrl), "onboard_ctrl", color = RsColors.Dim)
                    Key(rs(R.string.onboard_start), "onboard_start", R.drawable.ic_center_focus_strong, KeyKind.PRIMARY, modifier = Modifier.fillMaxWidth()) { onEvent(Ui.OnboardStart) }
                    MoveAsideRow(onEvent)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.btn_same), "same", R.drawable.ic_open_in_full, enabled = RsController.snapper.lastSize != null && s.phase != Phase.CAPTURING, modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.SameAsLast) }
            Key(rs(R.string.btn_redraw), "redraw", R.drawable.ic_replay, enabled = s.hasFrame, modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.Redraw) }
            Key(rs(R.string.btn_shoot), "shoot", R.drawable.ic_photo_camera, KeyKind.PRIMARY, enabled = s.canShoot, modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.Shoot) }
        }
        // v0.1.4: the same turn / face-me keys as the shutter bar (eye + hand users turn the frame here)
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.rot_left), "rot_left", R.drawable.ic_rotate_left, enabled = s.hasFrame, modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.Rotate(1)) }
            Key(rs(R.string.rot_right), "rot_right", R.drawable.ic_rotate_right, enabled = s.hasFrame, modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.Rotate(-1)) }
            Key(rs(R.string.face_me), "face_me", R.drawable.ic_my_location, enabled = s.hasFrame, modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.FaceMe) }
        }
        OptRow(rs(R.string.btn_stick), "stick") { SwitchBox(s.prefs.stick, "stick_switch") { on -> onEvent(Ui.SetPrefs("stick") { it.copy(stick = on) }) } }
        Small(rs(R.string.stick_note), "stick_note", maxLines = 1)
    }
}

/** "面板挡住要框的地方？ [让开]" (q2-1): the window steps aside (tucked) until [Open panel] / a frame needs it. */
@Composable
private fun MoveAsideRow(onEvent: (Ui) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Small(rs(R.string.move_aside_q), "move_aside_q", Modifier.weight(1f), maxLines = 2, color = RsColors.KeyInk)
        Key(rs(R.string.move_aside), "move_aside", R.drawable.ic_visibility, KeyKind.PRIMARY, 48.dp) { onEvent(Ui.MoveAside("key")) }
    }
}

/** S09 (and S20's capture): source region + the draggable quad, the result below, the info line and the keys. */
@Composable
private fun PreviewPage(s: RsUiState, onEvent: (Ui) -> Unit, calibrating: Boolean) {
    val pv = s.preview ?: return
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HintStrip(hintText(s) ?: rs(R.string.pv_drag))
        QuadEditor(pv.seq, Modifier.fillMaxWidth().height(232.dp), onEvent)
        if (!calibrating) {
            val out = RsController.previewOut
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33000000)).probe("pv_result"), contentAlignment = Alignment.Center) {
                @Suppress("UNUSED_EXPRESSION") pv.seq
                if (out != null) Image(bitmap = out.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            val info = if (pv.widthM != null && pv.heightM != null) rs(R.string.pv_info_m, m2(pv.widthM), m2(pv.heightM), pv.pxW, pv.pxH, surfaceText(pv.surface, pv.depthM))
            else rs(R.string.pv_info_float, m1(pv.depthM), pv.pxW, pv.pxH)
            // v0.1.5: the real output size; short of the tier it says so (the camera pixels are not enough, nothing upscaled)
            val res = if (pv.belowTier) rs(R.string.pv_res_below, pv.tier.label, pv.camW, pv.camH) else rs(R.string.pv_res_full, pv.tier.label)
            Small(info + "  ·  " + res + if (pv.sample != null) "  ·  " + rs(R.string.pv_sample) else "", "pv_info", maxLines = 2)
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Body(rs(R.string.pv_rectify), "pv_rectify", Modifier.weight(1f), maxLines = 1)
                SwitchBox(pv.rectified, "pv_rectify_switch") { onEvent(Ui.PreviewRectify(it)) }
                Options(OutRes.entries.map { it to it.label }, s.prefs.outRes, "pv_res", 72) { v -> onEvent(Ui.SetPrefs("res") { it.copy(outRes = v) }) }
            }
            Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Key(rs(R.string.pv_save), "pv_save", R.drawable.ic_save_alt, KeyKind.PRIMARY, modifier = Modifier.weight(0.8f), height = 52.dp) { onEvent(Ui.PreviewSave(false)) }
                Key(rs(R.string.pv_save_pin), "pv_save_pin", R.drawable.ic_push_pin, modifier = Modifier.weight(1.4f), height = 52.dp) { onEvent(Ui.PreviewSave(true)) }
                Key(rs(R.string.pv_retake), "pv_retake", R.drawable.ic_replay, modifier = Modifier.weight(0.9f), height = 52.dp) { onEvent(Ui.PreviewRetake) }
            }
        } else {
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Key(rs(R.string.calib_ok), "calib_ok", R.drawable.ic_check, KeyKind.PRIMARY, modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.CalibOk) }
                Key(rs(R.string.pv_retake), "calib_retake", R.drawable.ic_replay, modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.CalibRetake) }
                Key(rs(R.string.cancel), "calib_cancel", modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.CalibCancel) }
            }
            if ((s.calib?.tries ?: 0) >= 2) Key(rs(R.string.calib_anyway), "calib_anyway", modifier = Modifier.fillMaxWidth(), height = 52.dp) { onEvent(Ui.CalibDegraded) }
        }
    }
}

/**
 * The source picture's preview region with the quad: 4 corner dots (≥ 48 dp hit areas) dragged with the system pointer;
 * the result is re-rendered on every move (T7). A plain pointer loop, not detectDragGestures (D21 gate: no Compose drag
 * gestures anywhere in the app).
 */
@Composable
private fun QuadEditor(seq: Int, modifier: Modifier, onEvent: (Ui) -> Unit) {
    val src = RsController.previewSrc ?: return
    val quad = RsController.previewQuad
    @Suppress("UNUSED_EXPRESSION") seq
    // the shown region (R1-11 padding) in source pixels
    val region = remember(seq, src) { if (quad.size == 4) io.embodit.regionsnap.domain.Rectify.previewRegion(quad, src.width, src.height) else intArrayOf(0, 0, src.width, src.height) }
    val crop = remember(region[0], region[1], region[2], region[3], src) {
        android.graphics.Bitmap.createBitmap(src, region[0], region[1], (region[2] - region[0]).coerceAtLeast(1), (region[3] - region[1]).coerceAtLeast(1)).asImageBitmap()
    }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x33000000)).probe("pv_source"), contentAlignment = Alignment.Center) {
        val rw = (region[2] - region[0]).toFloat()
        val rh = (region[3] - region[1]).toFloat()
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val k = minOf(boxW / rw, boxH / rh)
        val ox = (boxW - rw * k) / 2f
        val oy = (boxH - rh * k) / 2f
        fun toView(p: Px) = Offset(ox + (p.x - region[0]) * k, oy + (p.y - region[1]) * k)
        fun toSrc(o: Offset) = Px((o.x - ox) / k + region[0], (o.y - oy) / k + region[1])
        var dragging by remember { mutableStateOf(-1) }
        Box(Modifier.fillMaxSize().pointerInput(seq, k, ox, oy) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: continue
                    if (c.pressed && dragging < 0) {
                        val hit = RsController.previewQuad.map { toView(it) }.withIndex().minByOrNull { (it.value - c.position).getDistance() }
                        if (hit != null && (hit.value - c.position).getDistance() < 36.dp.toPx()) dragging = hit.index
                    } else if (c.pressed && dragging >= 0) {
                        onEvent(Ui.PreviewCorner(dragging, toSrc(c.position)))
                        c.consume()
                    } else if (!c.pressed) dragging = -1
                }
            }
        }) {
            Image(bitmap = crop, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val pts = RsController.previewQuad.map { toView(it) }
                if (pts.size == 4) for (i in 0 until 4) drawLine(RsColors.Primary, pts[i], pts[(i + 1) % 4], strokeWidth = 3.dp.toPx())
                pts.forEachIndexed { i, p ->
                    drawCircle(Color.White, radius = 11.dp.toPx(), center = p)
                    drawCircle(if (i == dragging) RsColors.Level else RsColors.Primary, radius = 8.dp.toPx(), center = p)
                }
            }
        }
    }
}

// ================================================================================================= Shots

@Composable
private fun ShotsPage(s: RsUiState, onEvent: (Ui) -> Unit) {
    val id = s.detail
    if (id != null) return DetailPage(s, id, onEvent)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Title(rs(R.string.shots_count, s.shots.size, s.pinned.size), "shots_count", Modifier.weight(1f), maxLines = 1)
        }
        HintStrip(hintText(s))
        if (s.shots.isEmpty()) {
            // the empty page is one dashed "frame" filling the window, the way to Snap at its foot (short page blank, D44 / q1-2)
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).dashedFrame().padding(20.dp).probe("shots_empty_box"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                Icon(painter = painterResource(R.drawable.ic_photo_library), contentDescription = null, modifier = Modifier.size(120.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Body(rs(R.string.shots_empty), "shots_empty", maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Key(rs(R.string.tab_snap), "shots_to_snap", R.drawable.ic_crop_free, KeyKind.PRIMARY, 52.dp, modifier = Modifier.fillMaxWidth()) { onEvent(Ui.SelectTab(Tab.SNAP)) }
            return@Column
        }
        // q1-2: few shots get big tiles that fill the page (1 → one, 2-4 → 2 columns, more → 3); past 3 rows the grid scrolls
        val n = s.shots.size
        val cols = when { n <= 1 -> 1; n <= 4 -> 2; else -> 3 }
        val rows = (n + cols - 1) / cols
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val gap = 10.dp
            val tileH = if (rows <= 3) (maxHeight - gap * (rows - 1)) / rows else (maxWidth - gap * (cols - 1)) / cols
            LazyVerticalGrid(columns = GridCells.Fixed(cols), modifier = Modifier.fillMaxSize().probe("shots_grid"), horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            items(s.shots, key = { it.id }) { shot ->
                Box(Modifier.fillMaxWidth().height(tileH).clip(RoundedCornerShape(12.dp)).background(RsColors.KeyFill).clickable { onEvent(Ui.OpenShot(shot.id)) }.probe("cell_${shot.id}")) {
                    Thumb(shot.id, 320, Modifier.fillMaxSize())
                    if (shot.id in s.pinned) {
                        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp).clip(CircleShape).background(RsColors.Primary), contentAlignment = Alignment.Center) {
                            Icon(painter = painterResource(R.drawable.ic_push_pin), contentDescription = rs(R.string.badge_pinned), modifier = Modifier.size(18.dp), tint = RsColors.OnFill)
                        }
                    }
                }
            }
            }
        }
    }
}

/** A dashed outline like the frame drawn in the room (empty Shots page, calibration sketch). */
private fun Modifier.dashedFrame(): Modifier = this.drawBehind {
    val w = 2.dp.toPx()
    drawRoundRect(
        color = RsColors.Primary,
        topLeft = Offset(w / 2f, w / 2f),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 8.dp.toPx()))),
    )
}

/** A shot's picture, decoded off the main thread from files/shots/<id>.jpg. */
@Composable
private fun Thumb(id: String, maxSide: Int, modifier: Modifier) {
    val bmp by produceState<android.graphics.Bitmap?>(null, id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { RsController.gallery?.load(id, maxSide) }
    }
    bmp?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier) }
}

@Composable
private fun DetailPage(s: RsUiState, id: String, onEvent: (Ui) -> Unit) {
    val shot = s.shots.firstOrNull { it.id == id } ?: return
    val pinned = id in s.pinned
    val lost = id in s.lostPins
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.shots_back), "detail_back", R.drawable.ic_chevron_left) { onEvent(Ui.CloseShot) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0x33000000)).probe("detail_image"), contentAlignment = Alignment.Center) {
            Thumb(id, 1200, Modifier.fillMaxSize())
        }
        val size = if (shot.widthM != null && shot.heightM != null) String.format(Locale.US, "%s × %s m", m2(shot.widthM), m2(shot.heightM)) else rs(R.string.size_float, m1(shot.assumedDepthM ?: 0f))
        val date = SimpleDateFormat(if (LocalRsResources.current.configuration.locales[0].language == "zh") "M 月 d 日 HH:mm" else "MMM d, HH:mm", LocalRsResources.current.configuration.locales[0]).format(Date(shot.createdAt))
        Small("$size · ${shot.pxW} × ${shot.pxH} · $date · ${surfaceText(shot.surface, shot.assumedDepthM ?: 0f)}" + (if (shot.mediaStoreMissing) " · " + rs(R.string.album_gone) else "") + (if (lost) " · " + rs(R.string.pin_lost) else ""), "detail_info", maxLines = 2)
        if (s.deleteConfirm) {
            Row(Modifier.fillMaxWidth().height(64.dp).probe("delete_confirm"), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Body(rs(R.string.delete_q), "delete_q", Modifier.weight(1f), maxLines = 3)
                Key(rs(R.string.cancel), "delete_cancel") { onEvent(Ui.DeleteCancel) }
                Key(rs(R.string.delete_yes), "delete_yes", R.drawable.ic_delete, KeyKind.DANGER) { onEvent(Ui.DeleteYes) }
            }
        } else Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // 钉回原地 needs a pose that is still good: a pin that was lost (or never anchored) offers 钉到面前 (S19)
            if (!pinned || lost) {
                if (!lost) Key(rs(R.string.pin_back), "pin_back", R.drawable.ic_push_pin, KeyKind.PRIMARY, modifier = Modifier.weight(1f)) { onEvent(Ui.PinBack(id)) }
                Key(rs(R.string.pin_front), "pin_front", R.drawable.ic_my_location, if (lost) KeyKind.PRIMARY else KeyKind.NORMAL, modifier = Modifier.weight(1f)) { onEvent(Ui.PinFront(id)) }
            }
            if (pinned) Key(rs(R.string.unpin), "unpin", R.drawable.ic_keep_off, modifier = Modifier.weight(1f)) { onEvent(Ui.Unpin(id)) }
            Key(rs(R.string.delete), "delete", R.drawable.ic_delete, modifier = Modifier.weight(0.8f)) { onEvent(Ui.DeleteAsk) }
        }
    }
}

// ================================================================================================= Settings

/** v0.1.5 怎么操作 (profile B19): one row per main action, its icon, the hand way and the controller way. */
@Composable
private fun HowToPage(onEvent: (Ui) -> Unit) {
    val rows = listOf(
        Triple(R.drawable.ic_crop_free, R.string.how_to_draw, R.string.how_to_draw_hand to R.string.how_to_draw_ctrl),
        Triple(R.drawable.ic_open_in_full, R.string.how_to_adjust, R.string.how_to_adjust_hand to R.string.how_to_adjust_ctrl),
        Triple(R.drawable.ic_photo_camera, R.string.how_to_shoot, R.string.how_to_shoot_hand to R.string.how_to_shoot_ctrl),
        Triple(R.drawable.ic_rotate_right, R.string.how_to_turn, R.string.how_to_turn_hand to R.string.how_to_turn_ctrl),
        Triple(R.drawable.ic_push_pin, R.string.how_to_pin, R.string.how_to_pin_hand to R.string.how_to_pin_ctrl),
        Triple(R.drawable.ic_tune, R.string.how_to_res, R.string.how_to_res_hand to R.string.how_to_res_ctrl),
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Title(rs(R.string.how_to), "how_to_title", maxLines = 1)
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            for ((i, r) in rows.withIndex()) {
                val (icon, title, ways) = r
                Row(Modifier.fillMaxWidth().probe("how_to_row$i"), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(30.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    Column(Modifier.weight(1f)) {
                        Body(rs(title), "how_to_t$i", maxLines = 1)
                        Small(rs(ways.first), "how_to_h$i", maxLines = 1)
                        Small(rs(ways.second), "how_to_c$i", maxLines = 1)
                    }
                }
            }
        }
        Key(rs(R.string.about_close), "how_to_close", R.drawable.ic_chevron_left, modifier = Modifier.fillMaxWidth()) { onEvent(Ui.HowTo) }
    }
}

/** Settings (design panels.main.pages.settings; D26): every row on the first screen, 退出应用 last, confirmations in place. */
@Composable
private fun SettingsPage(s: RsUiState, onEvent: (Ui) -> Unit) {
    val p = s.prefs
    fun pr(why: String, f: (io.embodit.regionsnap.ui.Prefs) -> io.embodit.regionsnap.ui.Prefs) = onEvent(Ui.SetPrefs(why, f))
    if (s.calib != null) return CalibPage(s, onEvent)
    if (s.howToOpen) {
        HowToPage(onEvent)
        return
    }
    if (s.aboutOpen) {
        // short page: the facts sit in one card that takes the height, so no band opens between the lines (D44)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title(rs(R.string.about_ver, BuildConfig.VERSION_NAME), "about_title")
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(RsColors.KeyFill).padding(20.dp).probe("about_card"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                Icon(painter = painterResource(R.drawable.ic_crop_free), contentDescription = null, modifier = Modifier.size(88.dp), tint = RsColors.KeyInk)
                Body(rs(R.string.privacy), "about_privacy", maxLines = 3, align = androidx.compose.ui.text.style.TextAlign.Center, color = RsColors.KeyInk)
                Body(rs(R.string.save_where), "about_where", maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center, color = RsColors.KeyInk)
                Small(rs(R.string.about_icons), "about_icons", color = RsColors.KeyInk)
            }
            Key(rs(R.string.about_close), "about_close", R.drawable.ic_chevron_left, modifier = Modifier.fillMaxWidth()) { onEvent(Ui.About) }
        }
        return
    }
    // q1-6: the rows share the page height evenly (SpaceBetween), so no single band opens up between About and Quit
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        OptRow(rs(R.string.set_lang), "lang") {
            Options(listOf(Lang.SYSTEM to rs(R.string.lang_auto), Lang.ZH to rs(R.string.lang_zh), Lang.EN to rs(R.string.lang_en)), p.lang, "set_lang", 72) { l -> pr("lang") { it.copy(lang = l) } }
        }
        OptRow(rs(R.string.set_res), "res") {
            Options(OutRes.entries.map { it to it.label }, p.outRes, "set_res", 80) { v -> pr("res") { it.copy(outRes = v) } }
        }
        OptRow(rs(R.string.set_pinch), "pinch") {
            Options(listOf(Snapper.PinchMode.AUTO to rs(R.string.pinch_auto), Snapper.PinchMode.BOX to rs(R.string.pinch_box), Snapper.PinchMode.LOOP to rs(R.string.pinch_loop)), p.pinchMode, "set_pinch", 64) { v -> pr("pinch") { it.copy(pinchMode = v) } }
        }
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Body(rs(R.string.set_shutter), "set_shutter", Modifier.weight(1f), maxLines = 1)
            SwitchBox(p.shutterSound, "shutter_switch") { on -> pr("shutter") { it.copy(shutterSound = on) } }
            Body(rs(R.string.set_mute), "set_mute", Modifier.weight(1f).padding(start = 12.dp), maxLines = 1)
            SwitchBox(p.muted, "mute_switch") { on -> pr("mute") { it.copy(muted = on) } }
        }
        OptRow(rs(R.string.set_stick), "set_stick") { SwitchBox(p.stick, "set_stick_switch") { on -> pr("stick") { it.copy(stick = on) } } }
        if (!s.eyePermission) OptRow(rs(R.string.set_eye), "set_eye") { Key(rs(R.string.eye_go), "eye_go", R.drawable.ic_tune) { onEvent(Ui.EyeSettings) } }
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val c = p.calibration
            if (c == null) Key(rs(R.string.set_calib), "set_calib", R.drawable.ic_straighten, modifier = Modifier.weight(1f)) { onEvent(Ui.CalibStart) }
            else {
                Body(rs(if (c.degraded) R.string.calib_rough else R.string.calib_done), "calib_state", Modifier.weight(1f), maxLines = 1)
                Key(rs(R.string.calib_clear), "calib_clear") { onEvent(Ui.CalibClear) }
            }
            Key(rs(R.string.recall), "set_recall", R.drawable.ic_my_location, modifier = Modifier.weight(1f)) { onEvent(Ui.Recall) }
        }
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Small(rs(R.string.save_where), "save_where", Modifier.weight(1f), maxLines = 2)
            Key(rs(R.string.how_to), "set_how_to", R.drawable.ic_tune) { onEvent(Ui.HowTo) }
            Key(rs(R.string.about), "set_about", R.drawable.ic_info) { onEvent(Ui.About) }
        }
        if (s.exitConfirm) {
            Row(Modifier.fillMaxWidth().height(60.dp).probe("exit_confirm"), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Title(rs(R.string.exit_q), "exit_q", Modifier.weight(1f))
                Key(rs(R.string.cancel), "quit_cancel", height = 52.dp) { onEvent(Ui.QuitCancel) }
                Key(rs(R.string.exit_yes), "quit_yes", R.drawable.ic_exit_to_app, KeyKind.DANGER, 52.dp) { onEvent(Ui.QuitYes) }
            }
        } else Key(rs(R.string.quit), "quit", R.drawable.ic_exit_to_app, KeyKind.DANGER, 52.dp, modifier = Modifier.fillMaxWidth()) { onEvent(Ui.QuitAsk) }
    }
}

/** S20: the steps, then the captured paper with its quad (the same editor as S09). */
@Composable
private fun CalibPage(s: RsUiState, onEvent: (Ui) -> Unit) {
    if (s.preview != null && s.phase == Phase.PREVIEW) return PreviewPage(s, onEvent, calibrating = true)
    // q1-3: the steps and a sketch of the paper with its four corners fill the page instead of a short block mid-window
    // gate:allow button-gap a vertical stack of text, sketch and card; the side-by-side keys below are 16 dp apart
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Title(rs(R.string.calib_title), "calib_title")
        HintStrip(hintText(s))
        Small(rs(R.string.calib_s1), "calib_s1", maxLines = 1)
        Small(rs(R.string.calib_s2), "calib_s2", maxLines = 1)
        Small(rs(R.string.calib_s3), "calib_s3", maxLines = 2)
        CalibSketch(Modifier.fillMaxWidth().weight(1f))
        if (s.card != Card.NONE) WarnCard(rs(if (s.card == Card.CAMERA_FAILED || s.card == Card.CAMERA_STARTING) R.string.cam_failed else R.string.cam_perm), "calib_card") {
            if (s.card == Card.CAMERA_PERMISSION) Key(rs(R.string.cam_allow), "calib_allow", KeyKind.PRIMARY.let { R.drawable.ic_photo_camera }, KeyKind.PRIMARY, modifier = Modifier.weight(1f)) { onEvent(Ui.CamAllow) }
            Key(rs(R.string.cam_ok), "calib_card_ok", modifier = Modifier.weight(1f)) { onEvent(Ui.CalibCancel) }
        }
        Body(sizeText(s) + "  ·  " + surfaceText(if (s.hasFrame) s.frameSurface else null, s.frameDepth), "calib_frame", maxLines = 1)
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.btn_shoot), "calib_shoot", R.drawable.ic_photo_camera, KeyKind.PRIMARY, enabled = s.canShoot, modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.Shoot) }
            Key(rs(R.string.cancel), "calib_cancel", modifier = Modifier.weight(1f), height = 52.dp) { onEvent(Ui.CalibCancel) }
        }
    }
}

/** S20's sketch: a sheet of paper seen a little from the side, the dashed frame round it and its four corner dots. */
@Composable
private fun CalibSketch(modifier: Modifier) {
    val paper = Color(0xFFF4F1EA)
    val ink = Color(0xFF8A8F98)
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x33000000)).probe("calib_sketch")) {
        val h = size.height * 0.78f
        val w = h * 0.72f
        val cx = size.width / 2f
        val cy = size.height / 2f
        // a slight perspective: the far (top) edge a bit narrower
        val tl = Offset(cx - w * 0.44f, cy - h / 2f)
        val tr = Offset(cx + w * 0.44f, cy - h / 2f)
        val br = Offset(cx + w / 2f, cy + h / 2f)
        val bl = Offset(cx - w / 2f, cy + h / 2f)
        val sheet = androidx.compose.ui.graphics.Path().apply { moveTo(tl.x, tl.y); lineTo(tr.x, tr.y); lineTo(br.x, br.y); lineTo(bl.x, bl.y); close() }
        drawPath(sheet, paper)
        // a few lines of "text" on the paper
        for (i in 1..5) {
            val t = i / 7f
            val y = tl.y + (bl.y - tl.y) * t
            val x0 = tl.x + (bl.x - tl.x) * t + w * 0.1f
            val x1 = tr.x + (br.x - tr.x) * t - w * (if (i % 2 == 0) 0.3f else 0.12f)
            drawLine(ink, Offset(x0, y), Offset(x1, y), strokeWidth = 3.dp.toPx())
        }
        val pad = 14.dp.toPx()
        drawRoundRect(
            color = RsColors.Primary,
            topLeft = Offset(bl.x - pad, tl.y - pad),
            size = androidx.compose.ui.geometry.Size(br.x - bl.x + pad * 2f, bl.y - tl.y + pad * 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx()))),
        )
        drawPath(sheet, RsColors.Primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        for (p in listOf(tl, tr, br, bl)) {
            drawCircle(Color.White, radius = 11.dp.toPx(), center = p)
            drawCircle(RsColors.Primary, radius = 8.dp.toPx(), center = p)
        }
    }
}
