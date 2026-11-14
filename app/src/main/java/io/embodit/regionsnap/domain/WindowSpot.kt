package io.embodit.regionsnap.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where the main window goes relative to the frame (QA #1 q1-1, profile D41 / 48). SDK 6.1 always opens the Planar window
 * straight ahead of the gaze, square to it, and has no call to read or move it, so the app only controls *when* it opens
 * and *whether it stays open*:
 *
 * - Open (launch, [Panel] / A·X / long press, recall, preview reopen) waits until the window spot the gaze would give
 *   keeps the frame and its shutter bar clear by [MIN_GAP_DEG] ([turnDeg] == 0); the Stage says which way to look.
 * - Tuck: while a frame is drawn, adjusted or framed and the open window (as estimated at its ON_CREATE) covers any of
 *   it ([gapDeg] < [TUCK_GAP_DEG]), the window is closed; it reopens through the same gate, beside the frame.
 *
 * Every opening path goes through [turnDeg]; this is the one placement function (profile 48). Pure maths, unit-tested.
 */
object WindowSpot {
    /**
     * How far out the system puts the window. Measured on the emulator (v0.1.1, goto S06): the 600 dp x 0.8 mm window
     * (0.48 m) spans ±16.3° and is drawn in front of a frame on a wall 1.4 m away, so it sits about 0.82 m out, not the
     * 1.75 m the factory snippet assumed. Headset: to check (N15).
     */
    const val DIST_M = 0.82f
    const val MIN_GAP_DEG = 10f

    /** The open window covers the frame when the gap is below this (0 = a frame point is behind the window). */
    const val TUCK_GAP_DEG = 1f

    /** The TabBar capsule over the window and the system strip (dot, ×, −) under it, in window dp (native-window-move §2). */
    const val OVER_DP = 88f
    const val UNDER_DP = 72f

    /** Window size in dp and metres per window dp (the window reports it; 0.8 mm until it does). */
    data class Size(val wDp: Float, val hDp: Float, val dpM: Float) {
        val halfW: Float get() = wDp * dpM / 2f
        val top: Float get() = (hDp / 2f + OVER_DP) * dpM
        val bottom: Float get() = -(hDp / 2f + UNDER_DP) * dpM
    }

    /** Where the system puts the window for a gaze along [yaw] from [eye]: centre at eye height, [DIST_M] out. */
    fun centerFor(eye: Vec3, yaw: Float): Vec3 = eye + Yaw.dir(yaw) * DIST_M

    /**
     * The points the window must keep clear: the frame's corners, edge midpoints and centre, and the shutter bar under
     * it ([barHalfW] wide each side, its lower edge [barDrop] below the frame's lowest corner).
     */
    fun keepClear(frame: SnapFrame, barHalfW: Float, barDrop: Float): List<Vec3> {
        val c = frame.corners()
        val pts = ArrayList<Vec3>(16)
        pts += frame.center
        for (i in 0 until 4) {
            pts += c[i]
            pts += (c[i] + c[(i + 1) % 4]) * 0.5f
        }
        if (barHalfW > 0f) {
            val low = c.minOf { it.y } - barDrop
            val side = Vec3(frame.normal.z, 0f, -frame.normal.x).let { if (it.length < 1e-4f) Vec3(1f, 0f, 0f) else it.normalized() }
            val at = Vec3(frame.center.x, low, frame.center.z) + frame.normal * 0.02f
            pts += at
            pts += at + side * barHalfW
            pts += at - side * barHalfW
        }
        return pts
    }

    /**
     * Smallest angle (degrees) seen from [eye] between any [keep] point and the window whose centre is [center], facing
     * back along [yaw] (the gaze yaw it was opened with). 0 when a point is behind the window.
     */
    fun gapDeg(eye: Vec3, center: Vec3, yaw: Float, keep: List<Vec3>, size: Size): Float {
        val f = Yaw.dir(yaw)
        val r = Vec3(cos(yaw), 0f, -sin(yaw))
        val toPlane = (center - eye).dot(f)
        var worst = 180f
        for (p in keep) {
            val v = p - eye
            val len = v.length
            if (len < 1e-4f) continue
            val along = v.dot(f)
            val cx: Float
            val cy: Float
            if (along > 1e-3f && toPlane > 1e-3f) {
                // where the line of sight to p crosses the window plane, clamped into the window's frame
                val hit = eye + v * (toPlane / along) - center
                cx = hit.dot(r).coerceIn(-size.halfW, size.halfW)
                cy = hit.y.coerceIn(size.bottom, size.top)
            } else {
                cx = if (v.dot(r) >= 0f) size.halfW else -size.halfW
                cy = (v.y - (center.y - eye.y)).coerceIn(size.bottom, size.top)
            }
            val q = center + r * cx + Vec3(0f, cy, 0f) - eye
            val cosA = (v.dot(q) / (len * q.length)).coerceIn(-1f, 1f)
            val a = Math.toDegrees(acos(cosA).toDouble()).toFloat()
            if (a < worst) worst = a
        }
        return worst
    }

    /** The gap for a window opened now, looking along [yaw]. */
    fun gapIfOpened(eye: Vec3, yaw: Float, keep: List<Vec3>, size: Size): Float = gapDeg(eye, centerFor(eye, yaw), yaw, keep, size)

    /**
     * How far to turn (degrees, + = left, the [Yaw] convention) before the window may open; 0 = clear now, or nothing to
     * keep clear, or nothing clears within 180° (never blocks). The shorter way, away from the frame on a tie.
     */
    fun turnDeg(head: HeadPose, keep: List<Vec3>, size: Size): Float {
        if (keep.isEmpty()) return 0f
        val eye = head.position
        val yaw = head.yaw(0f)
        if (gapIfOpened(eye, yaw, keep, size) >= MIN_GAP_DEG) return 0f
        // away from the frame first: the side its centre is not on
        val r = Vec3(cos(yaw), 0f, -sin(yaw))
        var sx = 0f
        for (p in keep) sx += (p - eye).dot(r)
        // + yaw turns left (towards -r), so a frame on the right (sx > 0) means turn left first
        val away = if (sx > 0f) 1f else -1f
        for (deg in 1..180) for (s in floatArrayOf(away, -away)) {
            val a = Yaw.wrap(yaw + Num.rad(s * deg))
            if (gapIfOpened(eye, a, keep, size) >= MIN_GAP_DEG) return s * deg
        }
        return 0f
    }

    /** Where the hint plate goes, relative to the level gaze: [relYawDeg] (+ = left) and [downDeg] below (negative = above). */
    data class PlateSpot(val relYawDeg: Float, val downDeg: Float)

    /** The plate never goes further than this off the gaze (QA #2 q2-2: at ±40° / ±60° it left the view). */
    const val PLATE_MAX_OFF_DEG = 25f

    /**
     * The Stage hint plate ([halfWDeg] x [halfHDeg] angular half size) stays in the comfortable view, at most
     * [PLATE_MAX_OFF_DEG] off the gaze, and out of the way of the [keep] points (frame + shutter bar): straight ahead
     * below the gaze first, a bit lower, then above the frame, then nudged 10° / 20° sideways (side [prefer] first,
     * +1 left / -1 right). Falls back to straight ahead, 18° down.
     */
    fun plateSpot(head: HeadPose, keep: List<Vec3>, halfWDeg: Float, halfHDeg: Float, prefer: Int = 0): PlateSpot {
        val eye = head.position
        val yaw = head.yaw(0f)
        val angles = keep.map { p ->
            val v = p - eye
            Num.deg(Yaw.wrap(Yaw.of(Vec3(v.x, 0f, v.z)) - yaw)) to Num.deg(atan2(v.y, hypot(v.x, v.z)))
        }
        fun clear(c: PlateSpot): Boolean = angles.none { (a, e) -> abs(a - c.relYawDeg) < halfWDeg + 3f && abs(e + c.downDeg) < halfHDeg + 3f }
        val side = if (prefer != 0) prefer.toFloat() else if (angles.sumOf { it.first.toDouble() } > 0.0) -1f else 1f
        val order = listOf(
            PlateSpot(0f, 18f), PlateSpot(0f, 22f), PlateSpot(0f, PLATE_MAX_OFF_DEG),
            PlateSpot(0f, -14f), PlateSpot(0f, -20f),
            PlateSpot(side * 10f, 18f), PlateSpot(-side * 10f, 18f), PlateSpot(side * 20f, 12f), PlateSpot(-side * 20f, 12f),
        )
        return order.firstOrNull { clear(it) } ?: order.first()
    }
}
