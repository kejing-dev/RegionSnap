package io.embodit.regionsnap.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The two hands of the app (design input.principle) plus the last-resort head aim and the debug stand-in. */
enum class Source { CONTROLLER, EYE, FINGER, HEAD }

/** A 2D point in a surface basis (metres along right / up). */
data class P2(val x: Float, val y: Float) {
    fun dist(o: P2): Float = hypot(x - o.x, y - o.y)
}

/**
 * How a released stroke becomes a frame (design input.eyeHand.draw, R1-9 + R2-6; same rules for the controller):
 *  ① path / chord < 1.3 and the start-end box's short side ≥ 4 cm → BOX (start and end are the diagonal);
 *  ② path / chord < 1.3, short side < 4 cm and path ≥ 8 cm → LINE: a rect along the stroke, short side max(6 cm, 0.2 × long);
 *  ③ path / chord ≥ 1.3 → LOOP: the stroke's bounding box in the plane.
 * A result under 4 × 4 cm is TOO_SMALL.
 */
object StrokeJudge {
    enum class Kind { BOX, LINE, LOOP, TOO_SMALL }

    /** [cx], [cy] centre, [w] x [h], [angle] radians of the rect's long axis (LINE only; 0 otherwise). */
    data class Result(val kind: Kind, val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float = 0f)

    const val STRAIGHT = 1.3f
    const val MIN_SIDE = 0.04f
    const val LINE_MIN_PATH = 0.08f
    const val LINE_MIN_SHORT = 0.06f

    fun pathLength(pts: List<P2>): Float {
        var s = 0f
        for (i in 1 until pts.size) s += pts[i].dist(pts[i - 1])
        return s
    }

    fun judge(pts: List<P2>): Result {
        if (pts.size < 2) return Result(Kind.TOO_SMALL, 0f, 0f, 0f, 0f)
        val a = pts.first()
        val b = pts.last()
        val chord = a.dist(b)
        val path = pathLength(pts)
        val ratio = if (chord < 1e-4f) Float.MAX_VALUE else path / chord
        if (ratio < STRAIGHT) {
            val w = abs(b.x - a.x)
            val h = abs(b.y - a.y)
            if (min(w, h) >= MIN_SIDE) return Result(Kind.BOX, (a.x + b.x) / 2f, (a.y + b.y) / 2f, w, h)
            if (path >= LINE_MIN_PATH) {
                val long = chord
                val short = max(LINE_MIN_SHORT, 0.2f * long)
                var ang = kotlin.math.atan2(b.y - a.y, b.x - a.x)
                // keep the long axis within ±90° of "right" so the text stays upright
                if (ang > Math.PI / 2) ang -= Math.PI.toFloat()
                if (ang < -Math.PI / 2) ang += Math.PI.toFloat()
                return Result(Kind.LINE, (a.x + b.x) / 2f, (a.y + b.y) / 2f, long, short, ang)
            }
            return Result(Kind.TOO_SMALL, (a.x + b.x) / 2f, (a.y + b.y) / 2f, w, h)
        }
        val x0 = pts.minOf { it.x }
        val x1 = pts.maxOf { it.x }
        val y0 = pts.minOf { it.y }
        val y1 = pts.maxOf { it.y }
        val w = x1 - x0
        val h = y1 - y0
        val kind = if (w >= MIN_SIDE && h >= MIN_SIDE) Kind.LOOP else Kind.TOO_SMALL
        return Result(kind, (x0 + x1) / 2f, (y0 + y1) / 2f, w, h)
    }
}

/**
 * The press priority (design input.pressPriority, R1-1 / R2-2 / R2-3), shared by the controller and the eye + hand:
 *  ① the aim is on the app's UI → the system click handles it (nothing here);
 *  ② on a handle → ADJUST (S07);
 *  ③ elsewhere: if the aim moves past the threshold before release → DRAW (S04, old frame kept until the new one passes
 *    4 × 4 cm); if it never does → on release SHOOT when there is a frame, otherwise nothing;
 *  ④ A / X shoots at once (handled by the caller).
 * Threshold: controller 2 cm or 1°; eye + hand (and finger / head) 3°.
 */
class PressJudge {
    enum class Target { UI, HANDLE, PIN, ELSEWHERE }

    enum class Phase { IDLE, SYSTEM, ADJUST, PIN, PENDING, DRAW }

    sealed class Outcome {
        object None : Outcome()
        object Shoot : Outcome()
        /** A press and release on a pin without moving: open its small bar. */
        data class PinClick(val index: Int) : Outcome()
        object EndAdjust : Outcome()
        object EndPinDrag : Outcome()
        object EndDraw : Outcome()
    }

    var phase = Phase.IDLE
        private set
    var handle = -1
        private set
    var pin = -1
        private set
    private var source = Source.CONTROLLER
    private var downPoint = Vec3.ZERO
    private var downDir = Vec3.AHEAD

    /** Where the stroke starts (the aim at press time). */
    val startPoint: Vec3
        get() = downPoint

    /** Indirect pinch-drag: the press counts from the grabbed point as seen from the latched eyes. */
    fun rebase(aimPoint: Vec3, aimDir: Vec3) {
        downPoint = aimPoint
        downDir = aimDir.normalized()
    }

    fun down(src: Source, target: Target, aimPoint: Vec3, aimDir: Vec3, handleIndex: Int = -1, pinIndex: Int = -1) {
        source = src
        downPoint = aimPoint
        downDir = aimDir.normalized()
        handle = -1
        pin = -1
        phase = when (target) {
            Target.UI -> Phase.SYSTEM
            Target.HANDLE -> { handle = handleIndex; Phase.ADJUST }
            Target.PIN -> { pin = pinIndex; Phase.PIN }
            Target.ELSEWHERE -> Phase.PENDING
        }
    }

    /** True when the aim has moved past the source's threshold since the press. */
    fun moved(aimPoint: Vec3, aimDir: Vec3): Boolean {
        val ang = Num.angleDeg(downDir, aimDir)
        return when (source) {
            Source.CONTROLLER -> aimPoint.distanceTo(downPoint) > CTRL_MOVE_M || ang > CTRL_MOVE_DEG
            else -> ang > EYE_MOVE_DEG
        }
    }

    /** One frame while pressed; returns true when this frame turned a pending press into a stroke. */
    fun move(aimPoint: Vec3, aimDir: Vec3): Boolean {
        if (phase == Phase.PENDING && moved(aimPoint, aimDir)) {
            phase = Phase.DRAW
            return true
        }
        if (phase == Phase.PIN && moved(aimPoint, aimDir)) {
            // a pin press that moves becomes a drag (design pin.interact): keep PIN, the caller drags
            pinDragging = true
        }
        return false
    }

    var pinDragging = false
        private set

    fun up(hasFrame: Boolean): Outcome {
        val p = phase
        phase = Phase.IDLE
        val dragged = pinDragging
        pinDragging = false
        return when (p) {
            Phase.PENDING -> if (hasFrame) Outcome.Shoot else Outcome.None
            Phase.DRAW -> Outcome.EndDraw
            Phase.ADJUST -> Outcome.EndAdjust
            Phase.PIN -> if (dragged) Outcome.EndPinDrag else Outcome.PinClick(pin)
            else -> Outcome.None
        }
    }

    fun cancel() {
        phase = Phase.IDLE
        pinDragging = false
    }

    companion object {
        const val CTRL_MOVE_M = 0.02f
        const val CTRL_MOVE_DEG = 1f
        const val EYE_MOVE_DEG = 3f
    }
}

/**
 * Pinch from the hand joints (design input.eyeHand.pinch): thumb tip – index tip < 2.0 cm closes, > 3.0 cm opens
 * (hysteresis), each confirmed over 2 frames. Pure: fed one distance per frame.
 */
class PinchDetector(val closeM: Float = 0.020f, val openM: Float = 0.030f, val confirm: Int = 2) {
    var pinched = false
        private set
    private var streak = 0

    /** Returns +1 on a pinch edge, -1 on a release edge, 0 otherwise. Null distance = hand lost (releases). */
    fun step(distance: Float?): Int {
        if (distance == null) {
            streak = 0
            if (pinched) {
                pinched = false
                return -1
            }
            return 0
        }
        val wants = if (pinched) distance <= openM else distance < closeM
        if (wants == pinched) {
            streak = 0
            return 0
        }
        streak++
        if (streak < confirm) return 0
        streak = 0
        pinched = wants
        return if (wants) 1 else -1
    }
}

/**
 * Which input is in charge (design input.activeSource, R1-8): only discrete actions switch it (trigger / button / pinch
 * edges, a controller moving > 2 cm); gaze moving and a controller resting do not.
 */
class InputArbiter {
    var active: Source = Source.EYE
        private set
    var lastActionMs = 0L
        private set
    private var ctrlRest: Vec3? = null

    fun action(src: Source, now: Long): Boolean {
        lastActionMs = now
        if (src == active) return false
        active = src
        return true
    }

    /** Controller pose this frame; a move > 2 cm from where it rested counts as an action. */
    fun controllerPose(p: Vec3?, now: Long): Boolean {
        if (p == null) return false
        val rest = ctrlRest
        if (rest == null) {
            ctrlRest = p
            return false
        }
        if (p.distanceTo(rest) > 0.02f) {
            ctrlRest = p
            return action(Source.CONTROLLER, now)
        }
        return false
    }

    fun force(src: Source) {
        active = src
    }
}

/** One-euro filter on a 3D point (design input.eyeHand.draw: the gaze trail is smoothed). */
class OneEuro3(private val minCutoff: Float = 1.2f, private val beta: Float = 0.6f, private val dCutoff: Float = 1f) {
    private var x: Vec3? = null
    private var dx = Vec3.ZERO

    fun reset() {
        x = null
        dx = Vec3.ZERO
    }

    fun step(v: Vec3, dt: Float): Vec3 {
        val prev = x ?: return v.also { x = it }
        if (dt <= 0f) return prev
        val rawD = (v - prev) / dt
        val ad = alpha(dCutoff, dt)
        dx = dx + (rawD - dx) * ad
        val cutoff = minCutoff + beta * dx.length
        val a = alpha(cutoff, dt)
        val out = prev + (v - prev) * a
        x = out
        return out
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
