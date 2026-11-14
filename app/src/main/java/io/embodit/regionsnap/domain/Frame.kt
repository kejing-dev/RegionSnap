package io.embodit.regionsnap.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What a frame is stuck to (design input.surfaceDepth, dataModel.shot.surface). */
enum class Surface { WALL, TABLE, FLOOR, PLANE, FLOATING }

/** A flat surface the aim can land on: a detected plane polygon (Stage metres, convex fan) and its semantic. */
data class PlaneSurface(val id: String, val semantic: String, val vertices: List<Vec3>) {
    val normal: Vec3 by lazy {
        var best = Vec3.UP
        var bestLen = 0f
        for (i in 1 until vertices.size - 1) {
            val c = (vertices[i] - vertices[0]).cross(vertices[i + 1] - vertices[0])
            if (c.length > bestLen) {
                bestLen = c.length
                best = c
            }
        }
        if (bestLen < 1e-9f) Vec3.UP else best.normalized()
    }

    val isVertical: Boolean
        get() = abs(normal.y) < 0.35f

    val isHorizontal: Boolean
        get() = abs(normal.y) > 0.85f

    /** The kind of surface this is, by its semantic (N9) and orientation. */
    fun surface(): Surface =
        when {
            semantic == "FLOOR" -> Surface.FLOOR
            semantic == "WALL" || semantic == "WALL_FACE" -> Surface.WALL
            semantic == "TABLE" || semantic == "DESK" -> Surface.TABLE
            isVertical -> Surface.WALL
            isHorizontal && vertices.fold(0f) { a, v -> a + v.y } / vertices.size < 0.3f -> Surface.FLOOR
            isHorizontal -> Surface.TABLE
            else -> Surface.PLANE
        }

    /** Two-sided ray / polygon hit (Moeller-Trumbore over the fan); distance along the unit [dir], or null. */
    fun hit(origin: Vec3, dir: Vec3, maxDistance: Float = 8f): Float? {
        var best: Float? = null
        for (i in 1 until vertices.size - 1) {
            val v0 = vertices[0]
            val e1 = vertices[i] - v0
            val e2 = vertices[i + 1] - v0
            val pv = dir.cross(e2)
            val det = e1.dot(pv)
            if (abs(det) < 1e-9f) continue
            val inv = 1f / det
            val tv = origin - v0
            val u = tv.dot(pv) * inv
            if (u < 0f || u > 1f) continue
            val qv = tv.cross(e1)
            val v = dir.dot(qv) * inv
            if (v < 0f || u + v > 1f) continue
            val t = e2.dot(qv) * inv
            if (t > 0.05f && t < maxDistance && (best == null || t < best)) best = t
        }
        return best
    }
}

/** Where the aim lands this frame: a point, the surface's outward normal (towards the aimer) and its kind. */
data class AimHit(val point: Vec3, val normal: Vec3, val surface: Surface, val distance: Float, val planeId: String? = null) {
    /** The surface's "up" for a frame drawn here: vertical faces use world up, horizontal ones the aim direction. */
    fun baseUp(aimDir: Vec3): Vec3 = Frames.baseUp(normal, aimDir)
}

/**
 * A rectangle in the room (design entities3d.frameEdges): [center], outward [normal], [baseUp] (the level "up" in the
 * surface) and [roll] (radians, turned about the normal, + counter-clockwise seen from the front), [w] x [h] metres.
 * For a floating frame [depth] is the distance from the aimer it was drawn at (the 3D tag "约 1.2 m").
 */
data class SnapFrame(
    val center: Vec3,
    val normal: Vec3,
    val baseUp: Vec3,
    val roll: Float,
    val w: Float,
    val h: Float,
    val surface: Surface,
    val depth: Float = 0f,
) {
    val floating: Boolean
        get() = surface == Surface.FLOATING

    /** In-plane unit axes after [roll]. */
    val up: Vec3
        get() {
            val n = normal.normalized()
            val u0 = baseUp.rejectFrom(n).normalized()
            val r0 = u0.cross(n).normalized()
            return (u0 * cos(roll) - r0 * sin(roll)).normalized()
        }

    val right: Vec3
        get() = up.cross(normal).normalized()

    /** Corners TL, TR, BR, BL (image order, seen from the front). */
    fun corners(): List<Vec3> {
        val r = right * (w / 2f)
        val u = up * (h / 2f)
        return listOf(center - r + u, center + r + u, center + r - u, center - r - u)
    }

    /** Edge midpoints top, right, bottom, left. */
    fun edgeMids(): List<Vec3> {
        val r = right * (w / 2f)
        val u = up * (h / 2f)
        return listOf(center + u, center + r, center - u, center - r)
    }

    /** All 9 handles: corners 0-3, edges 4-7, centre 8 (design entities3d.frameHandles). */
    fun handles(): List<Vec3> = corners() + edgeMids() + listOf(center)

    /** [p] in the frame's own 2D coordinates (metres along right / up from the centre). */
    fun local(p: Vec3): Pair<Float, Float> {
        val d = p - center
        return d.dot(right) to d.dot(up)
    }

    fun world(x: Float, y: Float): Vec3 = center + right * x + up * y

    /** The nearest point to [p] on the rectangle's outline, in the frame's plane (the S05 shrink). */
    fun onOutline(p: Vec3): Vec3 {
        val (x0, y0) = local(p)
        val hw = w / 2f
        val hh = h / 2f
        val x = x0.coerceIn(-hw, hw)
        val y = y0.coerceIn(-hh, hh)
        // inside: push to the closest edge; outside: the clamp already lies on the outline
        if (abs(x0) <= hw && abs(y0) <= hh) {
            val dx = hw - abs(x)
            val dy = hh - abs(y)
            return if (dx < dy) world(if (x >= 0f) hw else -hw, y) else world(x, if (y >= 0f) hh else -hh)
        }
        return world(x, y)
    }

    /** Roll measured from level, degrees in -180..180. */
    val rollDeg: Float
        get() = Num.deg(Yaw.wrap(roll))

    /** "0.42 × 0.30 m" for a frame on a surface; floating frames have no true size (design R1-12). */
    fun sizeLabel(): String = String.format(java.util.Locale.US, "%.2f × %.2f m", w, h)

    /** Width : height of the real rectangle (floating: the angular ratio, which equals the metric one at its depth). */
    val aspect: Float
        get() = w / max(h, 1e-4f)
}

object Num {
    fun deg(rad: Float): Float = (rad * 180.0 / Math.PI).toFloat()

    fun rad(deg: Float): Float = (deg * Math.PI / 180.0).toFloat()

    /** Angle between two unit vectors, degrees. */
    fun angleDeg(a: Vec3, b: Vec3): Float = deg(kotlin.math.acos(a.normalized().dot(b.normalized()).coerceIn(-1f, 1f)))
}

/** Frame operations (design input.controller.adjust / eyeHand.adjust, interactions T6). Pure. */
object Frames {
    const val MIN_SIDE = 0.04f
    const val STEP_DEG = 5f
    const val LEVEL_SNAP_DEG = 5f
    const val DEPTH_MIN = 0.3f
    const val DEPTH_MAX = 4f
    const val FLOAT_DEPTH = 1.2f

    fun baseUp(normal: Vec3, aimDir: Vec3): Vec3 {
        val n = normal.normalized()
        val vertical = abs(n.y) < 0.7f
        val ref = if (vertical) Vec3.UP else {
            // horizontal face: up = where the user looks along the surface (away from them), from the aim ray, never the head
            val flat = Vec3(aimDir.x, 0f, aimDir.z)
            if (flat.length < 1e-3f) Vec3.AHEAD else flat.normalized()
        }
        val u = ref.rejectFrom(n)
        return if (u.length < 1e-4f) Vec3.AHEAD.rejectFrom(n).normalized() else u.normalized()
    }

    /** A frame spanning the 2D rect [x0..x1] x [y0..y1] in a surface basis at [origin]. */
    fun fromRect(origin: Vec3, normal: Vec3, baseUp: Vec3, roll: Float, x0: Float, x1: Float, y0: Float, y1: Float, surface: Surface, depth: Float): SnapFrame {
        val f = SnapFrame(origin, normal, baseUp, roll, 1f, 1f, surface, depth)
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        return f.copy(center = f.world(cx, cy), w = abs(x1 - x0), h = abs(y1 - y0))
    }

    /** One stick push / [转] press: turn by [steps] x 5°, then snap to the nearest level angle when within ±5°. */
    fun rotate(f: SnapFrame, steps: Int): Pair<SnapFrame, Boolean> {
        val turned = f.roll + Num.rad(STEP_DEG * steps)
        return snapLevel(f.copy(roll = turned))
    }

    /** Snaps roll to 0 / 90 / 180 / 270° when within [LEVEL_SNAP_DEG]; returns whether it snapped. */
    fun snapLevel(f: SnapFrame): Pair<SnapFrame, Boolean> {
        val d = Num.deg(Yaw.wrap(f.roll))
        for (target in listOf(-180f, -90f, 0f, 90f, 180f)) {
            if (abs(d - target) < LEVEL_SNAP_DEG && abs(d - target) > 1e-3f) return f.copy(roll = Num.rad(target)) to true
        }
        return f to false
    }

    /**
     * The normal of a frame at [center] that faces [eye] (v0.1.4, user on the headset: "the frame should face the camera
     * by default, else everything is skewed"): horizontal, towards the eyes; only the yaw, never pitch or roll.
     */
    fun facingNormal(center: Vec3, eye: Vec3): Vec3 {
        val v = Vec3(eye.x - center.x, 0f, eye.z - center.z)
        return if (v.length < 1e-3f) Vec3(0f, 0f, 1f) else v.normalized()
    }

    /** [f] turned to face [eye]: upright (base up = world up, no roll), only the yaw changes ([回正]). */
    fun faceUser(f: SnapFrame, eye: Vec3): SnapFrame = f.copy(normal = facingNormal(f.center, eye), baseUp = Vec3.UP, roll = 0f)

    /** [f] turned about the vertical axis through its centre by [deg] (+ = counter-clockwise seen from above). */
    fun yaw(f: SnapFrame, deg: Float): SnapFrame {
        val n = f.normal
        val a = Num.rad(deg)
        val c = cos(a)
        val s = sin(a)
        val flat = Vec3(n.x * c + n.z * s, 0f, -n.x * s + n.z * c)
        return f.copy(normal = if (flat.length < 1e-4f) n else flat.normalized(), baseUp = Vec3.UP, roll = 0f)
    }

    /** How far [f] is turned from facing [eye], degrees, + = counter-clockwise seen from above (0 = faces the user). */
    fun yawOffsetDeg(f: SnapFrame, eye: Vec3): Float {
        val want = facingNormal(f.center, eye)
        val n = Vec3(f.normal.x, 0f, f.normal.z).let { if (it.length < 1e-4f) want else it.normalized() }
        return Num.deg(atan2(want.z * n.x - want.x * n.z, want.x * n.x + want.z * n.z))
    }

    /** Push / pull a floating frame along its normal ([delta] metres, + = away), depth kept in 0.3 - 4 m. */
    fun push(f: SnapFrame, aimer: Vec3, delta: Float): SnapFrame {
        if (!f.floating) return f
        val dir = (f.center - aimer).normalized()
        val d = (f.center - aimer).length
        val nd = (d + delta).coerceIn(DEPTH_MIN, DEPTH_MAX)
        val k = nd / max(d, 1e-3f)
        return f.copy(center = aimer + dir * nd, w = f.w * k, h = f.h * k, depth = nd)
    }

    /**
     * Drag handle [handle] to the in-plane point [p] (Stage): a corner keeps the opposite corner, an edge midpoint moves only
     * along that edge's normal (R1-15), the centre moves the whole frame in its plane. Sides never get below 4 cm.
     */
    fun dragHandle(f: SnapFrame, handle: Int, p: Vec3): SnapFrame {
        val (x, y) = f.local(p)
        var x0 = -f.w / 2f
        var x1 = f.w / 2f
        var y0 = -f.h / 2f
        var y1 = f.h / 2f
        when (handle) {
            0 -> { x0 = min(x, x1 - MIN_SIDE); y1 = max(y, y0 + MIN_SIDE) }
            1 -> { x1 = max(x, x0 + MIN_SIDE); y1 = max(y, y0 + MIN_SIDE) }
            2 -> { x1 = max(x, x0 + MIN_SIDE); y0 = min(y, y1 - MIN_SIDE) }
            3 -> { x0 = min(x, x1 - MIN_SIDE); y0 = min(y, y1 - MIN_SIDE) }
            4 -> y1 = max(y, y0 + MIN_SIDE)
            5 -> x1 = max(x, x0 + MIN_SIDE)
            6 -> y0 = min(y, y1 - MIN_SIDE)
            7 -> x0 = min(x, x1 - MIN_SIDE)
            8 -> return f.copy(center = f.world(x, y))
        }
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        return f.copy(center = f.world(cx, cy), w = x1 - x0, h = y1 - y0)
    }

    /** Where the ray meets the frame's plane (null when parallel or behind). */
    fun onPlane(f: SnapFrame, origin: Vec3, dir: Vec3): Vec3? = Rays.plane(origin, dir, f.center, f.normal)

    /** Handle [i]'s pick radius: Ø 4.5 cm collider, growing with distance so a 2 m handle is still easy (R1-15). */
    fun pickRadius(distance: Float): Float = max(0.0225f, distance * 0.022f)

    /** The handle the ray points at (nearest along the ray), or null. */
    fun pickHandle(f: SnapFrame, origin: Vec3, dir: Vec3): Int? {
        var best: Int? = null
        var bestT = Float.MAX_VALUE
        f.handles().forEachIndexed { i, h ->
            val t = (h - origin).dot(dir)
            if (t <= 0f) return@forEachIndexed
            val miss = (origin + dir * t).distanceTo(h)
            // the centre handle is bigger (Ø 4 cm)
            val r = pickRadius(t) * if (i == 8) 1.2f else 1f
            if (miss <= r && t < bestT) {
                best = i
                bestT = t
            }
        }
        return best
    }

    /** True when the ray passes through the frame's rectangle (pins and the frame body). */
    fun rayInside(f: SnapFrame, origin: Vec3, dir: Vec3, margin: Float = 0f): Boolean {
        val p = onPlane(f, origin, dir) ?: return false
        val (x, y) = f.local(p)
        return abs(x) <= f.w / 2f + margin && abs(y) <= f.h / 2f + margin
    }

    /** The angle of the frame's right axis from level, degrees (dump / tests). */
    fun levelAngleDeg(f: SnapFrame): Float {
        val r0 = f.baseUp.cross(f.normal).normalized()
        return Num.deg(atan2(f.right.dot(f.baseUp), f.right.dot(r0)))
    }
}

object Rays {
    /** Ray / infinite plane, distance > 2 cm; null when parallel or behind. */
    fun plane(origin: Vec3, dir: Vec3, point: Vec3, normal: Vec3): Vec3? {
        val den = dir.dot(normal)
        if (abs(den) < 1e-5f) return null
        val t = (point - origin).dot(normal) / den
        if (t < 0.02f) return null
        return origin + dir * t
    }

    /**
     * Where an aim ray lands (design input.surfaceDepth): the nearest detected plane within 6 m when [stick] is on, else a
     * floating point [floatDepth] ahead, facing back along the ray.
     */
    fun aim(origin: Vec3, dir: Vec3, planes: Collection<PlaneSurface>, stick: Boolean, floatDepth: Float = Frames.FLOAT_DEPTH): AimHit {
        if (stick) {
            var best: Pair<PlaneSurface, Float>? = null
            for (p in planes) {
                val t = p.hit(origin, dir, 6f) ?: continue
                if (best == null || t < best.second) best = p to t
            }
            if (best != null) {
                val (p, t) = best
                val n = if (p.normal.dot(dir) > 0f) -p.normal else p.normal
                return AimHit(origin + dir * t, n, p.surface(), t, p.id)
            }
        }
        return AimHit(origin + dir * floatDepth, -dir, Surface.FLOATING, floatDepth)
    }
}
