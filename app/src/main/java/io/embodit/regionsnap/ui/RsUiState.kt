package io.embodit.regionsnap.ui

import io.embodit.regionsnap.domain.Calibration
import io.embodit.regionsnap.domain.Shot
import io.embodit.regionsnap.domain.Snapper
import io.embodit.regionsnap.domain.Source
import io.embodit.regionsnap.domain.Surface

enum class Lang { SYSTEM, ZH, EN }

enum class Tab { SNAP, SHOTS, SETTINGS }

/** The Snap page's phase (design states S02-S12). */
enum class Phase { IDLE, AIMING, DRAWING, SHRINK, FRAMED, ADJUSTING, CAPTURING, PREVIEW, SAVED, PINNED }

/** The in-page cards (design S16 / S18). */
enum class Card { NONE, CAMERA_PERMISSION, CAMERA_DENIED_FOREVER, CAMERA_FAILED, CAMERA_STARTING }

/** One-line hints in the strip under the page title (design panels.main.hintStrip, R2-1). */
enum class Hint {
    NONE, ONBOARD, TOO_SMALL, EYE_FALLBACK, HAND_LOST, HEAD_FALLBACK, CAMERA_STARTING, LOW_RES, ANCHORS_LOST, PIN_NOT_KEPT,
    PINS_BEHIND, PIN_LIMIT, CALIB_PRE, CALIB_RETRY, CALIB_FAR, SAVED, PINNED, LOOK_LEVEL
}

/** Which input drives the aim, as the panel's second line says it (design T12). */
enum class InputMode { CONTROLLER, EYE_HAND, FINGER, EYE_NO_HAND, HEAD }

data class Prefs(
    val lang: Lang = Lang.SYSTEM,
    /** 1280 × 960 instead of 640 × 480 (labels are the pixel counts, N2). */
    val hiRes: Boolean = false,
    val shutterSound: Boolean = true,
    val muted: Boolean = false,
    val pinchMode: Snapper.PinchMode = Snapper.PinchMode.AUTO,
    val stick: Boolean = true,
    val onboarded: Boolean = false,
    val calibration: Calibration? = null,
    val lastW: Float = 0f,
    val lastH: Float = 0f,
    val lastFloating: Boolean = false,
)

/** The S09 preview: which picture (bitmaps live in the controller), the info line's facts and the four corners. */
data class PreviewUi(
    val seq: Int,
    val rectified: Boolean,
    val pxW: Int,
    val pxH: Int,
    val widthM: Float?,
    val heightM: Float?,
    val surface: Surface,
    val depthM: Float,
    val lowRes: Boolean,
    val sample: String?,
    val renderMs: Long,
)

/** S20 calibrate once. */
data class CalibUi(val tries: Int = 0, val shot: Boolean = false, val unstable: Boolean = false)

data class RsUiState(
    val prefs: Prefs = Prefs(),
    val tab: Tab = Tab.SNAP,
    val phase: Phase = Phase.IDLE,
    val card: Card = Card.NONE,
    val onboarding: Boolean = false,
    val hasFrame: Boolean = false,
    val frameW: Float = 0f,
    val frameH: Float = 0f,
    val frameSurface: Surface = Surface.FLOATING,
    val frameDepth: Float = 0f,
    val aimSurface: Surface? = null,
    val aimDepth: Float = 0f,
    val input: InputMode = InputMode.EYE_HAND,
    val source: Source = Source.EYE,
    val preview: PreviewUi? = null,
    val shots: List<Shot> = emptyList(),
    val pinned: Set<String> = emptySet(),
    val lostPins: Set<String> = emptySet(),
    val detail: String? = null,
    val deleteConfirm: Boolean = false,
    val hint: Hint = Hint.NONE,
    val hintN: Int = 0,
    val hintSeq: Int = 0,
    val exitConfirm: Boolean = false,
    val aboutOpen: Boolean = false,
    val calib: CalibUi? = null,
    val panelOpen: Boolean = false,
    val panelEverOpened: Boolean = false,
    /** The app put the window away because it covered the frame (q1-1); the framing state goes on without it. */
    val panelTucked: Boolean = false,
    val pinBar: String? = null,
    val background: Boolean = false,
    val eyePermission: Boolean = true,
    val camState: String = "none",
    val flashSeq: Int = 0,
) {
    val canShoot: Boolean
        get() = hasFrame && phase != Phase.CAPTURING && phase != Phase.DRAWING

    /**
     * A hint says something about the state; once that is no longer true it goes, whatever its timer (q1-4, D38): 已存到相册
     * only while S10, 便签没找到 only while a note is lost, calibration hints only on S20, 相机还在启动 only with its card.
     */
    fun hintStillTrue(): Boolean = when (hint) {
        Hint.SAVED -> phase == Phase.SAVED
        Hint.PINNED, Hint.PIN_NOT_KEPT -> phase == Phase.SAVED || phase == Phase.PINNED
        Hint.ANCHORS_LOST -> lostPins.isNotEmpty()
        Hint.CALIB_PRE, Hint.CALIB_RETRY -> calib != null
        Hint.CAMERA_STARTING -> card == Card.CAMERA_STARTING
        Hint.ONBOARD -> onboarding
        Hint.TOO_SMALL -> tab == Tab.SNAP
        else -> true
    }

    /** The design state this is (logs, dump, debug `goto`). */
    val designState: String
        get() = when {
            background -> "S23"
            !panelOpen && !panelEverOpened -> "S00"
            !panelOpen && phase != Phase.PREVIEW && !(panelTucked && (hasFrame || phase == Phase.DRAWING || phase == Phase.SHRINK)) -> "S22"
            tab == Tab.SETTINGS && exitConfirm -> "S21"
            tab == Tab.SETTINGS && calib != null -> "S20"
            tab == Tab.SETTINGS -> "S15"
            tab == Tab.SHOTS && detail != null -> "S14"
            tab == Tab.SHOTS -> "S13"
            card == Card.CAMERA_PERMISSION || card == Card.CAMERA_DENIED_FOREVER -> "S16"
            card == Card.CAMERA_FAILED || card == Card.CAMERA_STARTING -> "S18"
            onboarding -> "S01"
            hint == Hint.ANCHORS_LOST -> "S19"
            phase == Phase.PREVIEW -> "S09"
            phase == Phase.CAPTURING -> "S08"
            phase == Phase.SAVED -> "S10"
            phase == Phase.PINNED -> "S12"
            phase == Phase.ADJUSTING -> "S07"
            phase == Phase.SHRINK -> "S05"
            phase == Phase.DRAWING -> "S04"
            hasFrame -> "S06"
            input == InputMode.EYE_NO_HAND -> "S17b"
            input == InputMode.FINGER -> "S17"
            phase == Phase.AIMING -> "S03"
            else -> "S02"
        }
}
