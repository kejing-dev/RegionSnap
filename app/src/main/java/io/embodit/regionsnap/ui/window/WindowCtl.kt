package io.embodit.regionsnap.ui.window

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.regionsnap.domain.LevelGate
import io.embodit.regionsnap.platform.RsLog
import io.embodit.regionsnap.ui.RsController

/** Id and tag of the main window (tag != null: "reuse" mode, one instance). */
const val PANEL_ID = "MainPanel"
const val PANEL_TAG = "main"

/**
 * The main window's state shared by the Stage and the window (one process, native-window-move §3.4): the window reports
 * its lifecycle, the Stage opens / recalls it with its navigator. Every open waits for a level head (D9 / design S00:
 * the system opens a Planar window square to the line of sight, pitch included, and SDK 6.1 cannot re-level it).
 * Main thread only.
 */
object WindowCtl {
    var panelState by mutableStateOf("never")
        private set

    /** Bumped on every ON_CREATE: the scene re-estimates where the system put the window. */
    var createdEpoch by mutableIntStateOf(0)
        private set

    /** Metres per window dp, from the window's LocalPhysicalLengthConverter. */
    @Volatile var dpM = 0.0008f

    /** Last pointer event on the window (pressPriority ①: a pinch within 150 ms of touching the UI never starts a stroke). */
    @Volatile var lastPointerMs = 0L

    var navigator: SpatialNavigator? = null

    /** The scene: does the window's last spot (its ON_CREATE estimate) still keep the frame clear? */
    var oldSpotClear: () -> Boolean = { true }

    private val main = Handler(Looper.getMainLooper())
    private var recallPending = false
    private val gate = LevelGate()
    private var lastStepMs = 0L

    /** The Stage's "平视前方" hint: an open waits and the head is not level. */
    var levelHint by mutableStateOf(false)
        private set

    /** The Stage's "往左看一点 / 往右看一点" hint (q1-1): an open waits for a spot clear of the frame; +1 left, -1 right, 0 none. */
    var turnHint by mutableIntStateOf(0)
        private set

    /** How far the gaze still has to turn, degrees rounded to 5 (the plate says "turn about 40°" when it is a lot, q2-2). */
    var turnNeedDeg by mutableIntStateOf(0)
        private set

    /** The window was closed by the app because it covered the frame (q1-1); cleared when it opens again. */
    var tucked by mutableStateOf(false)
        private set

    val waiting: Boolean
        get() = gate.waiting

    val open: Boolean
        get() = panelState != "never" && panelState != "ON_DESTROY" && panelState != "ON_STOP"

    fun openWindow(why: String) {
        RsLog.i("PANEL ask why=$why state=$panelState (opens when the head is level)")
        gate.ask(why, SystemClock.elapsedRealtime())
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    /**
     * One Stage frame: [pitchDeg] head pitch (+ up), null while the head pose is not valid yet; [turnDeg] how far the gaze
     * still has to turn for a window spot clear of the frame (WindowSpot.turnDeg, 0 = clear).
     */
    fun stepGate(now: Long, pitchDeg: Float?, turnDeg: Float = 0f) {
        lastStepMs = now
        if (!gate.waiting) {
            if (levelHint) levelHint = false
            if (turnHint != 0) turnHint = 0
            return
        }
        val hint = gate.hintWanted(pitchDeg)
        if (hint != levelHint) {
            levelHint = hint
            RsLog.i(String.format(java.util.Locale.US, "GATE level hint=%s pitch=%.1f", hint, pitchDeg ?: Float.NaN))
        }
        val turn = if (gate.turnWanted(pitchDeg, turnDeg)) (if (turnDeg > 0f) 1 else -1) else 0
        val need = if (turn == 0) 0 else (kotlin.math.round(kotlin.math.abs(turnDeg) / 5f) * 5f).toInt().coerceAtLeast(5)
        if (need != turnNeedDeg) turnNeedDeg = need
        if (turn != turnHint) {
            turnHint = turn
            RsLog.i(String.format(java.util.Locale.US, "TURN hint=%d need=%.0f", turn, turnDeg))
        }
        val why = gate.step(now, pitchDeg, turnDeg) ?: return
        levelHint = false
        turnHint = 0
        RsLog.i(String.format(java.util.Locale.US, "GATE level ok pitch=%.1f turn=%.0f -> open", pitchDeg ?: Float.NaN, turnDeg))
        openNow(why)
    }

    /**
     * q1-1: the open window covers the frame (or its shutter bar), so the app puts it away; [Panel] / A·X / long press, or
     * a step that needs it (the preview), opens it again through the gate, beside the frame. Also with no frame yet
     * (q2-1): [Move aside] or a tap on the window's empty area in S02 / S03 clears the spot behind it. [why] for the log.
     */
    fun tuck(why: String) {
        if (!open || recallPending || tucked) return
        val nav = navigator ?: return
        tucked = true
        RsLog.i("PANEL tuck why=$why")
        runCatching { nav.closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { RsLog.w("PANEL tuck failed: $it"); tucked = false }
        RsController.onTucked(tucked)
    }

    private val watchdog =
        object : Runnable {
            override fun run() {
                if (!gate.waiting) return
                val now = SystemClock.elapsedRealtime()
                val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > FIRST_FRAME_MS else now - lastStepMs > WATCHDOG_MS
                if (stalled) {
                    val why = gate.pending ?: return
                    gate.cancel()
                    levelHint = false
                    turnHint = 0
                    openNow("$why/noframes")
                } else main.postDelayed(this, WATCHDOG_MS)
            }
        }

    private fun openNow(why: String) {
        val nav = navigator ?: return RsLog.w("PANEL open why=$why: no navigator")
        RsLog.i("PANEL open why=$why state=$panelState")
        runCatching { nav.openWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { RsLog.w("PANEL open failed: $it") }
    }

    /** ⌂ / long press / A·X with the window closed (S22): a minimised window is restored, a closed one reopened level. */
    fun reopen(why: String) {
        if (open) return
        if (panelState == "ON_STOP") {
            // restore after − only when the old spot still keeps the frame clear (q1-1); else close and reopen through the gate
            if (oldSpotClear() && navigator?.let { runCatching { it.restoreWindowContainer(PANEL_ID, PANEL_TAG) }.getOrDefault(false) } == true) {
                RsLog.i("PANEL restore why=$why")
                return
            }
            return recall("$why/spot")
        }
        openWindow(why)
    }

    /**
     * 叫回面前 (D29 / design S15): the system cannot move a window and a second open only focuses it, so recall closes it and
     * opens it again (level, in front) once it reported ON_DESTROY.
     */
    fun recall(why: String) {
        tucked = false
        if (panelState == "never" || panelState == "ON_DESTROY") return openWindow(why)
        val nav = navigator ?: return
        recallPending = true
        RsLog.i("PANEL recall why=$why: close, then open after ON_DESTROY")
        runCatching { nav.closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { RsLog.w("PANEL close failed: $it"); recallPending = false }
    }

    fun onLifecycle(event: String) {
        panelState = event
        RsLog.i("PANEL lifecycle $event")
        if (event == "ON_CREATE") {
            createdEpoch++
            if (tucked) {
                tucked = false
                RsController.onTucked(false)
            }
        }
        RsController.onPanel(open)
        if (event == "ON_DESTROY" && recallPending) {
            recallPending = false
            main.postDelayed({ openWindow("recall") }, 300)
        }
    }

    /** Quit: close the window before the Stage (it would otherwise outlive it, native-window-move §7.7). */
    fun closeForQuit() {
        recallPending = false
        gate.cancel()
        levelHint = false
        turnHint = 0
        runCatching { navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
    }

    private const val WATCHDOG_MS = 3000L
    private const val FIRST_FRAME_MS = 15000L
}
