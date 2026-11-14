package io.embodit.regionsnap.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One frame of input for [Snapper], the same shape for every source (design input.principle: one set of actions, two
 * hands). [rayOrigin] / [rayDir] is the aim (controller aim ray, gaze, finger, head fallback, or the debug stand-in);
 * [pressed] the trigger ≥ 0.6 / pinch state (the caller applies the hysteresis); [onUi] the aim is on the app's UI
 * (main window estimate, shutter bar, pin bar) or the system pointer touched one within 150 ms (pressPriority ①).
 */
data class InputFrame(
    val source: Source,
    val rayOrigin: Vec3?,
    val rayDir: Vec3?,
    val pressed: Boolean,
    val onUi: Boolean = false,
    val stickX: Float = 0f,
    val stickY: Float = 0f,
    /** A / X this frame (shoot), B / Y (redraw). */
    val shootKey: Boolean = false,
    val clearKey: Boolean = false,
    /**
     * Eye + hand only (user, headset, 2026-11-11: "pinch and pull the frame with the hand, not with the head"): the
     * pinching hand's thumb-index midpoint, Stage metres. While a pinch is held the hand, not the gaze, moves the point.
     */
    val pinchPoint: Vec3? = null,
    /** The eyes' position (head), latched at the pinch: the indirect drag's lever. */
    val eye: Vec3? = null,
    /** False for the head-direction fallback: it may aim and tap, never drag (D1). */
    val canDrag: Boolean = true,
    /** A controller grip is held: with the stick left / right it turns the frame about the vertical axis (v0.1.4). */
    val grip: Boolean = false,
)

/** What the engine asks the app to do (sound, haptics, state). */
sealed class SnapEvent {
    object DrawStart : SnapEvent()
    data class Framed(val kind: StrokeJudge.Kind) : SnapEvent()
    object TooSmall : SnapEvent()
    object Shoot : SnapEvent()
    object Cleared : SnapEvent()
    data class Grab(val handle: Int) : SnapEvent()
    object Released : SnapEvent()
    object Leveled : SnapEvent()
    object Rotated : SnapEvent()
    data class PinClick(val index: Int) : SnapEvent()
    data class PinMoved(val index: Int) : SnapEvent()
    /** A pin drag ended: [frame] is where it now hangs. */
    data class PinDropped(val index: Int, val frame: SnapFrame) : SnapEvent()
    object PressEdge : SnapEvent()
}

/**
 * The drawing engine (design states S02-S07, input.*): aim → press → drag → release → press again to shoot. Pure and
 * framework-free so the controller and the eye + hand run through the exact same judgement in unit tests and the debug
 * hooks feed it the same [InputFrame]s as the real devices (factory rule: debug commands take the user's path).
 */
class Snapper {
    var frame: SnapFrame? = null
        private set
    var aim: AimHit? = null
        private set
    /** The stroke being drawn, Stage points (trail entity). */
    val trail = ArrayList<Vec3>()
    var drawing: SnapFrame? = null
        private set
    var hoverHandle: Int? = null
        private set
    var hoverPin: Int? = null
        private set
    val judge = PressJudge()
    var stick = true
    var floatDepth = Frames.FLOAT_DEPTH
    /** Pinch mode setting (design settings: 自动 / 拉框 / 画圈). */
    var pinchMode = PinchMode.AUTO
    /** The pinned notes' rectangles (index = pin slot) for hover / click / drag. */
    var pins: List<SnapFrame> = emptyList()
    /** Last frame size for [同上次] (design input.lastSelection). */
    var lastSize: Triple<Float, Float, Boolean>? = null
        private set

    private var wasPressed = false
    private var planeOrigin = Vec3.ZERO
    private var planeNormal = Vec3.UP
    private var planeUp = Vec3.AHEAD
    private var planeSurface = Surface.FLOATING
    private var planeDepth = 0f
    private var lastOrigin: Vec3? = null
    private var lastEye: Vec3? = null
    private var strokeEye = Vec3.ZERO
    private var surfaceNormal = Vec3.UP
    private var yawHoldUntil = 0L
    private var pinDrag: SnapFrame? = null
    private var pinStart: SnapFrame? = null
    private var pinGrab = Vec3.ZERO

    // indirect pinch-drag (eye + hand): latched at the pinch, then only the hand moves the point
    private var indirect = false
    private var eye0 = Vec3.ZERO
    private var pinch0: Vec3? = null
    private var anchor = Vec3.ZERO
    private var anchorNormal = Vec3.UP
    private var gain = 1f
    private var dragFrozen = false
    // hand jitter is mm-scale and a pull is up to ~1 m/s: a steep speed term keeps the lag to a frame or two
    private val pinchFilter = OneEuro3(minCutoff = 1.5f, beta = 20f)

    /** Indirect drag gain for the last press (debug / tests). */
    val dragGain: Float
        get() = gain

    enum class PinchMode { AUTO, BOX, LOOP }

    fun step(input: InputFrame, planes: Collection<PlaneSurface>, now: Long, dt: Float): List<SnapEvent> {
        val ev = ArrayList<SnapEvent>(2)
        val o = input.rayOrigin
        val d = input.rayDir?.normalized()
        lastOrigin = o
        lastEye = input.eye ?: o ?: lastEye
        aim = if (o != null && d != null) Rays.aim(o, d, planes, stick, floatDepth) else null
        hoverHandle = if (o != null && d != null && judge.phase == PressJudge.Phase.IDLE) frame?.let { Frames.pickHandle(it, o, d) } else hoverHandle
        hoverPin = if (o != null && d != null && judge.phase == PressJudge.Phase.IDLE && hoverHandle == null) pins.indexOfFirst { Frames.rayInside(it, o, d, 0.01f) }.takeIf { it >= 0 } else hoverPin

        // ④ A / X: shoot whatever the aim; B / Y: redraw
        if (input.shootKey && frame != null && judge.phase != PressJudge.Phase.DRAW) ev += SnapEvent.Shoot
        if (input.clearKey && frame != null) {
            clear()
            ev += SnapEvent.Cleared
        }
        stepStick(input, now, ev)

        val pressed = input.pressed && o != null && d != null
        if (pressed && !wasPressed) {
            ev += SnapEvent.PressEdge
            val a = aim!!
            val target = when {
                input.onUi -> PressJudge.Target.UI
                hoverHandle != null -> PressJudge.Target.HANDLE
                hoverPin != null -> PressJudge.Target.PIN
                else -> PressJudge.Target.ELSEWHERE
            }
            judge.down(input.source, target, a.point, d!!, hoverHandle ?: -1, hoverPin ?: -1)
            if (target == PressJudge.Target.HANDLE) ev += SnapEvent.Grab(hoverHandle!!)
            if (target == PressJudge.Target.PIN) {
                pinDrag = pins.getOrNull(hoverPin!!)
                pinStart = pinDrag
                pinGrab = pinDrag?.let { Frames.onPlane(it, o!!, d) } ?: a.point
            }
            val facing = Frames.facingNormal(a.point, input.eye ?: o!!)
            startIndirect(input, target, a, o!!, d, facing)
            // the stroke's plane: fixed at press time (a floating stroke stays at the depth it began); it stands upright
            // and faces the user at the aim point, on a wall, a table or in the air (v0.1.4: never skewed)
            planeOrigin = a.point
            planeNormal = facing
            planeUp = Vec3.UP
            strokeEye = input.eye ?: o!!
            surfaceNormal = a.normal
            planeSurface = a.surface
            planeDepth = a.distance
            trail.clear()
            trail += a.point
        } else if (pressed && wasPressed) {
            // eye + hand: the gaze picked the start at the pinch; from now on the hand drives a virtual ray from the
            // latched eyes, so turning the head or moving the gaze changes nothing (indirect pinch-drag)
            var o = o
            var d = d
            if (indirect) {
                val p = indirectPoint(input, dt)
                o = eye0
                d = (p - eye0).normalized()
                aim = AimHit(p, anchorNormal, aim?.surface ?: planeSurface, p.distanceTo(eye0))
            }
            val turned = judge.move(aim!!.point, d!!)
            when (judge.phase) {
                PressJudge.Phase.DRAW -> {
                    if (turned) ev += SnapEvent.DrawStart
                    Rays.plane(o!!, d, planeOrigin, planeNormal)?.let { p ->
                        if (trail.isEmpty() || trail.last().distanceTo(p) > 0.003f) {
                            trail += p
                            if (trail.size > MAX_TRAIL) trail.removeAt(1)
                        }
                    }
                    drawing = preview()
                }
                PressJudge.Phase.ADJUST -> frame?.let { f -> Frames.onPlane(f, o!!, d)?.let { frame = Frames.dragHandle(f, judge.handle, it) } }
                PressJudge.Phase.PIN -> if (judge.pinDragging) {
                    // the note moves by how far the grab point moved (no jump to the aim at the start)
                    val f = pinDrag
                    val start = pinStart
                    val grab = pinGrab
                    if (f != null && start != null) Frames.onPlane(start, o!!, d)?.let { p ->
                        pinDrag = f.copy(center = start.center + (p - grab))
                        ev += SnapEvent.PinMoved(judge.pin)
                    }
                }
                else -> Unit
            }
        } else if (!pressed && wasPressed) {
            when (val out = judge.up(frame != null)) {
                PressJudge.Outcome.Shoot -> ev += SnapEvent.Shoot
                PressJudge.Outcome.EndDraw -> finishStroke(ev)
                PressJudge.Outcome.EndAdjust -> ev += SnapEvent.Released
                is PressJudge.Outcome.PinClick -> if (out.index >= 0) ev += SnapEvent.PinClick(out.index)
                PressJudge.Outcome.EndPinDrag -> { pinDrag?.let { ev += SnapEvent.PinDropped(judge.pin, it) }; pinDrag = null }
                else -> Unit
            }
            drawing = null
            trail.clear()
            indirect = false
            pinStart = null
        }
        wasPressed = pressed
        return ev
    }

    /**
     * At the pinch (eye + hand only): latch the eyes, the hand and the grabbed point (stroke start, the hit on the frame
     * for a handle, on the note for a pin) and the plane it moves in; gain = target distance ÷ hand distance, 1-6x.
     * The controller keeps its direct ray.
     */
    private fun startIndirect(input: InputFrame, target: PressJudge.Target, a: AimHit, o: Vec3, d: Vec3, facing: Vec3) {
        indirect = input.source != Source.CONTROLLER
        if (!indirect) return
        eye0 = input.eye ?: o
        val h = input.pinchPoint
        pinch0 = h
        pinchFilter.reset()
        dragFrozen = h == null || !input.canDrag
        val f = frame
        val pin = pinStart
        when {
            target == PressJudge.Target.HANDLE && f != null -> { anchor = Frames.onPlane(f, o, d) ?: f.handles()[judge.handle]; anchorNormal = f.normal }
            target == PressJudge.Target.PIN && pin != null -> { anchor = pinGrab; anchorNormal = pin.normal }
            else -> { anchor = a.point; anchorNormal = facing }
        }
        judge.rebase(anchor, (anchor - eye0).normalized())
        val hand = h?.distanceTo(eye0) ?: 0f
        gain = if (hand > 0.05f) (anchor.distanceTo(eye0) / hand).coerceIn(MIN_GAIN, MAX_GAIN) else MIN_GAIN
    }

    /** The dragged point now: the grab point + the hand's smoothed move × gain, kept in the grabbed plane. */
    private fun indirectPoint(input: InputFrame, dt: Float): Vec3 {
        val h0 = pinch0
        val h = input.pinchPoint
        if (dragFrozen || h0 == null || h == null || !input.canDrag) return anchor
        val moved = (pinchFilter.step(h, dt) - h0) * gain
        val n = anchorNormal.normalized()
        return anchor + (moved - n * moved.dot(n))
    }

    /** The pin rectangle while it is dragged (null otherwise). */
    val draggedPin: SnapFrame?
        get() = if (judge.pinDragging) pinDrag else null

    private fun basis(): SnapFrame = SnapFrame(planeOrigin, planeNormal, planeUp, 0f, 0f, 0f, planeSurface, planeDepth)

    private fun local(p: Vec3): P2 {
        val b = basis()
        val (x, y) = b.local(p)
        return P2(x, y)
    }

    /** The live rectangle while drawing (start fixed, diagonal follows the aim; a loop shows its bounding box). */
    private fun preview(): SnapFrame? {
        if (trail.size < 2) return null
        val r = judgeTrail()
        if (r.w <= 0f || r.h <= 0f) return null
        return toFrame(r)
    }

    private fun judgeTrail(): StrokeJudge.Result {
        val pts = trail.map { local(it) }
        return when (pinchMode) {
            PinchMode.AUTO -> StrokeJudge.judge(pts)
            PinchMode.BOX -> {
                val a = pts.first()
                val b = pts.last()
                val w = abs(b.x - a.x)
                val h = abs(b.y - a.y)
                StrokeJudge.Result(if (w >= StrokeJudge.MIN_SIDE && h >= StrokeJudge.MIN_SIDE) StrokeJudge.Kind.BOX else StrokeJudge.Kind.TOO_SMALL, (a.x + b.x) / 2f, (a.y + b.y) / 2f, w, h)
            }
            PinchMode.LOOP -> {
                val x0 = pts.minOf { it.x }; val x1 = pts.maxOf { it.x }; val y0 = pts.minOf { it.y }; val y1 = pts.maxOf { it.y }
                val ok = x1 - x0 >= StrokeJudge.MIN_SIDE && y1 - y0 >= StrokeJudge.MIN_SIDE
                StrokeJudge.Result(if (ok) StrokeJudge.Kind.LOOP else StrokeJudge.Kind.TOO_SMALL, (x0 + x1) / 2f, (y0 + y1) / 2f, x1 - x0, y1 - y0)
            }
        }
    }

    private fun toFrame(r: StrokeJudge.Result): SnapFrame {
        val b = basis()
        // on a wall / table the centre goes back onto the surface along the line of sight (the stroke plane is tilted
        // to face the user, so its middle can sit a few cm off the surface); floating frames keep the stroke plane
        val c0 = b.world(r.cx, r.cy)
        val c = if (planeSurface == Surface.FLOATING) c0 else Rays.plane(strokeEye, (c0 - strokeEye).normalized(), planeOrigin, surfaceNormal) ?: c0
        // upright (a slanted line stroke still gives a level rectangle, roll 0) and facing the user from its own centre
        return SnapFrame(c, Frames.facingNormal(c, strokeEye), Vec3.UP, 0f, r.w, r.h, planeSurface, planeDepth)
    }

    private fun finishStroke(ev: MutableList<SnapEvent>) {
        val r = judgeTrail()
        if (r.kind == StrokeJudge.Kind.TOO_SMALL || r.w < Frames.MIN_SIDE || r.h < Frames.MIN_SIDE) {
            // the old frame stays (R2-2)
            ev += SnapEvent.TooSmall
            return
        }
        val f = toFrame(r)
        frame = f
        lastSize = Triple(f.w, f.h, f.floating)
        ev += SnapEvent.Framed(r.kind)
    }

    private fun stepStick(input: InputFrame, now: Long, ev: MutableList<SnapEvent>) {
        val f = frame ?: return
        // grip held + stick left / right: turn about the vertical axis, ~60°/s, catching on "faces you" as it passes
        val eye = lastEye
        if (input.grip && abs(input.stickX) > STICK_DEAD && eye != null && now >= yawHoldUntil) {
            val before = Frames.yawOffsetDeg(f, eye)
            // stick right = clockwise seen from above = - yaw
            val turned = Frames.yaw(f, -input.stickX * YAW_RATE_DEG * STICK_DT)
            val after = Frames.yawOffsetDeg(turned, eye)
            if (abs(before) > 0.5f && (before > 0f) != (after > 0f) && abs(before - after) < 90f) {
                frame = Frames.faceUser(f, eye)
                yawHoldUntil = now + YAW_SNAP_HOLD_MS
                ev += SnapEvent.Leveled
            } else {
                frame = turned
                ev += SnapEvent.Rotated
            }
        }
        // stick up / down: a floating frame moves away / closer, 0.6 m/s
        val o = lastOrigin
        if (f.floating && abs(input.stickY) > 0.3f && o != null) frame = Frames.push(frame!!, o, input.stickY * 0.6f * STICK_DT)
    }

    /**
     * [左转] / [右转] (+1 / -1): 15° about the vertical axis; a step that passes "faces you" stops there. Returns true when
     * it landed on facing (tick). Same frame maths as the grip + stick path.
     */
    fun yawStep(dir: Int, eye: Vec3? = lastEye): Boolean {
        val f = frame ?: return false
        val e = eye ?: return false.also { frame = Frames.yaw(f, dir * YAW_STEP_DEG) }
        val before = Frames.yawOffsetDeg(f, e)
        val after = before + dir * YAW_STEP_DEG
        val crosses = abs(before) > 0.5f && (before > 0f) != (after > 0f) && abs(after) > 0.5f
        frame = if (crosses || abs(after) < 0.5f) Frames.faceUser(f, e) else Frames.yaw(f, dir * YAW_STEP_DEG)
        return crosses || abs(after) < 0.5f
    }

    /** [回正]: face the user again (the current eyes), upright. */
    fun faceUser(eye: Vec3? = lastEye): Boolean {
        val f = frame ?: return false
        val e = eye ?: return false
        frame = Frames.faceUser(f, e)
        return true
    }

    /** [远] / [近]: ±10 cm. */
    fun pushStep(delta: Float) {
        val f = frame ?: return
        val o = lastOrigin ?: return
        frame = Frames.push(f, o, delta)
    }

    /** [同上次]: a frame of the last size at the current aim (S06). */
    fun sameAsLast(): Boolean {
        val (w, h, _) = lastSize ?: return false
        val a = aim ?: return false
        val eye = lastEye ?: lastOrigin ?: return false
        frame = SnapFrame(a.point, Frames.facingNormal(a.point, eye), Vec3.UP, 0f, w, h, a.surface, a.distance)
        return true
    }

    fun setLast(w: Float, h: Float, floating: Boolean) {
        lastSize = Triple(w, h, floating)
    }

    fun setFrame(f: SnapFrame?) {
        frame = f
        if (f != null) lastSize = Triple(f.w, f.h, f.floating)
    }

    fun clear() {
        frame = null
        judge.cancel()
        drawing = null
        trail.clear()
    }

    /** A frame [w] x [h] at the current aim (debug `frame w,h[,floating]`, same as 同上次 with a given size). */
    fun frameAtAim(w: Float, h: Float, floating: Boolean, origin: Vec3, dir: Vec3, planes: Collection<PlaneSurface>) {
        val a = Rays.aim(origin, dir.normalized(), planes, stick && !floating, floatDepth)
        setFrame(SnapFrame(a.point, Frames.facingNormal(a.point, origin), Vec3.UP, 0f, w, h, a.surface, a.distance))
    }

    companion object {
        const val MAX_TRAIL = 240

        /** Grip + stick yaw: degrees per second at full push; past "faces you" it holds this long before going on. */
        const val YAW_RATE_DEG = 60f
        const val YAW_SNAP_HOLD_MS = 400L
        const val YAW_STEP_DEG = 15f
        const val STICK_DEAD = 0.3f

        /** Indirect drag gain limits (target distance ÷ hand distance). */
        const val MIN_GAIN = 1f
        const val MAX_GAIN = 6f
        const val STICK_DT = 1f / 60f

        /** Points on a circle of radius [r] about [c] in the plane facing [n] (debug `loop`). */
        fun circle(c: Vec3, n: Vec3, r: Float, steps: Int = 40): List<Vec3> {
            val up = Frames.baseUp(n, -n)
            val right = up.cross(n).normalized()
            return (0..steps).map { i ->
                val a = 2 * Math.PI * i / steps
                c + right * (r * cos(a).toFloat()) + up * (r * sin(a).toFloat())
            }
        }
    }
}
