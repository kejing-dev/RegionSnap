package io.embodit.regionsnap.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** A point in image pixels (x right, y down, origin top-left). */
data class Px(val x: Float, val y: Float) {
    fun dist(o: Px): Float = kotlin.math.hypot(x - o.x, y - o.y)
}

/** Pinhole intrinsics (design capture: the 3×3 K from rectifiedVSTAccess, or an assumed one until calibrated). */
data class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val w: Int, val h: Int) {
    companion object {
        /** An assumed K for a [w] x [h] image with horizontal field of view [hfovDeg] (used when K is not read back). */
        fun assumed(w: Int, h: Int, hfovDeg: Float = 90f): Intrinsics {
            val f = (w / 2f) / tan(Num.rad(hfovDeg / 2f))
            return Intrinsics(f, f, w / 2f, h / 2f, w, h)
        }
    }
}

/** A rigid transform: x_world = q.rotate(x_local) + p. */
data class Rigid(val p: Vec3, val q: Quat4) {
    fun apply(v: Vec3): Vec3 = q.rotate(v) + p

    fun inverse(): Rigid {
        val qi = q.conjugate()
        return Rigid(-qi.rotate(p), qi)
    }

    operator fun times(o: Rigid): Rigid = Rigid(q.rotate(o.p) + p, (q * o.q).normalized())

    companion object {
        val IDENTITY = Rigid(Vec3.ZERO, Quat4.IDENTITY)
    }
}

/**
 * From the frame in the room to a straight picture (design capture.pipeline): camera pose = HMD pose ∘ T_cam, the 4
 * corners projected with K, a padded preview region, and the homography that maps the quad onto a rectangle with the
 * frame's real aspect. Pure; Android's Matrix.setPolyToPoly does the drawing with the same 4 point pairs.
 */
object Rectify {
    /** Default T_cam (no calibration): the right-eye VST camera 3.2 cm to the right of the HMD centre, no rotation. */
    val DEFAULT_TCAM = Rigid(Vec3(0.032f, 0f, 0f), Quat4.IDENTITY)

    /** World point → pixel for a camera at [cam] (camera looks along its local -Z, +Y up). Null behind the camera. */
    fun project(world: Vec3, cam: Rigid, k: Intrinsics): Px? {
        val c = cam.inverse().apply(world)
        if (c.z > -1e-3f) return null
        val x = c.x / -c.z
        val y = c.y / -c.z
        return Px(k.cx + k.fx * x, k.cy - k.fy * y)
    }

    fun quad(frame: SnapFrame, cam: Rigid, k: Intrinsics): List<Px>? {
        val out = frame.corners().map { project(it, cam, k) ?: return null }
        return out
    }

    /**
     * The preview region around the quad (R1-11): padded by max(8 % of the quad size, 3 % of the image width, 24 px),
     * clamped to the image. Returns left, top, right, bottom.
     */
    fun previewRegion(q: List<Px>, imgW: Int, imgH: Int): IntArray {
        val x0 = q.minOf { it.x }; val x1 = q.maxOf { it.x }
        val y0 = q.minOf { it.y }; val y1 = q.maxOf { it.y }
        val size = max(x1 - x0, y1 - y0)
        val pad = max(max(0.08f * size, 0.03f * imgW), 24f)
        return intArrayOf(
            floor(x0 - pad).toInt().coerceIn(0, imgW - 1),
            floor(y0 - pad).toInt().coerceIn(0, imgH - 1),
            ceil(x1 + pad).toInt().coerceIn(1, imgW),
            ceil(y1 + pad).toInt().coerceIn(1, imgH),
        )
    }

    /**
     * Output size (design capture.pipeline 拉平): aspect = the frame's real w : h; long side = the quad's longest side in
     * source pixels, at most [cap] (the chosen tier, v0.1.5), never upscaled.
     */
    fun outputSize(q: List<Px>, aspect: Float, cap: Int = OutRes.DEFAULT.long): Pair<Int, Int> {
        val longest = (0 until 4).maxOf { q[it].dist(q[(it + 1) % 4]) }
        val long = min(longest, cap.toFloat()).coerceAtLeast(16f)
        return if (aspect >= 1f) Pair(long.toInt(), (long / aspect).toInt().coerceAtLeast(8)) else Pair((long * aspect).toInt().coerceAtLeast(8), long.toInt())
    }

    /** True when the camera pixels in [q] fall short of [tier]: the picture comes out smaller, not upscaled. */
    fun belowTier(q: List<Px>, tier: OutRes): Boolean = (0 until 4).maxOf { q[it].dist(q[(it + 1) % 4]) } < tier.long

    /** Straight (no rectification) crop: the quad's bounding box (design [拉平] off). */
    fun boundingCrop(q: List<Px>, imgW: Int, imgH: Int): IntArray =
        intArrayOf(
            floor(q.minOf { it.x }).toInt().coerceIn(0, imgW - 1),
            floor(q.minOf { it.y }).toInt().coerceIn(0, imgH - 1),
            ceil(q.maxOf { it.x }).toInt().coerceIn(1, imgW),
            ceil(q.maxOf { it.y }).toInt().coerceIn(1, imgH),
        )

    // ------------------------------------------------------------------------------------------ homography

    /** 3×3 homography (row-major, h[8] = 1) that maps src[i] → dst[i] (4 pairs, DLT with h33 = 1). Null when degenerate. */
    fun homography(src: List<Px>, dst: List<Px>): DoubleArray? {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[i].x.toDouble(); val y = src[i].y.toDouble()
            val u = dst[i].x.toDouble(); val v = dst[i].y.toDouble()
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        val h = solve(a) ?: return null
        return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
    }

    fun apply(h: DoubleArray, p: Px): Px {
        val x = p.x.toDouble(); val y = p.y.toDouble()
        val w = h[6] * x + h[7] * y + h[8]
        return Px(((h[0] * x + h[1] * y + h[2]) / w).toFloat(), ((h[3] * x + h[4] * y + h[5]) / w).toFloat())
    }

    /** Gaussian elimination with partial pivoting on an n×(n+1) augmented matrix. */
    private fun solve(m: Array<DoubleArray>): DoubleArray? {
        val n = m.size
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
            if (abs(m[piv][c]) < 1e-12) return null
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            for (r in 0 until n) {
                if (r == c) continue
                val f = m[r][c] / m[c][c]
                if (f == 0.0) continue
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /** The rectangle corners TL, TR, BR, BL of an output of [w] x [h] px. */
    fun rect(w: Int, h: Int): List<Px> = listOf(Px(0f, 0f), Px(w.toFloat(), 0f), Px(w.toFloat(), h.toFloat()), Px(0f, h.toFloat()))

    // ------------------------------------------------------------------------------------------ calibration

    /**
     * Calibrate once (design capture.calibrateOnce, R1-2): the frame is a known planar rectangle of [w] x [h] m; [q] its 4
     * corners as the user dragged them onto the real paper. Solve the camera pose relative to the frame plane from the
     * homography (Zhang's planar pose: H = K [r1 r2 t]), then T_cam = HMD⁻¹ ∘ camera. Returns null when degenerate; the
     * reprojection error (px) says whether to keep it (≤ 3 px).
     */
    fun calibrate(frame: SnapFrame, q: List<Px>, k: Intrinsics, hmd: Rigid): Calibration? {
        // plane coordinates (metres) of the corners, origin at the centre, X right, Y up
        val plane = listOf(Px(-frame.w / 2, frame.h / 2), Px(frame.w / 2, frame.h / 2), Px(frame.w / 2, -frame.h / 2), Px(-frame.w / 2, -frame.h / 2))
        // normalised image coordinates (camera looks -Z, +Y up): xn = (u - cx) / fx, yn = -(v - cy) / fy
        val norm = q.map { Px((it.x - k.cx) / k.fx, -(it.y - k.cy) / k.fy) }
        val h = homography(plane, norm) ?: return null
        // columns of H ~ [r1 r2 t] up to scale, with the camera looking down -Z: X_c = -(...) handled by the sign below
        val h1 = Vec3(h[0].toFloat(), h[3].toFloat(), h[6].toFloat())
        val h2 = Vec3(h[1].toFloat(), h[4].toFloat(), h[7].toFloat())
        val h3 = Vec3(h[2].toFloat(), h[5].toFloat(), h[8].toFloat())
        // homogeneous image point (xn, yn, 1) ∝ (X, Y, -Z): flip the third component to get camera coordinates
        fun cam(v: Vec3) = Vec3(v.x, v.y, -v.z)
        var r1 = cam(h1); var r2 = cam(h2); var t = cam(h3)
        val s = 2f / (r1.length + r2.length)
        r1 = r1 * s; r2 = r2 * s; t = t * s
        if (t.z > 0f) { r1 = -r1; r2 = -r2; t = -t }
        // orthonormalise
        val x = r1.normalized()
        var y = (r2 - x * r2.dot(x)).normalized()
        val z = x.cross(y)
        y = z.cross(x)
        val planeInCam = Rigid(t, Quat4.fromBasis(x, y, z))
        val planeWorld = Rigid(frame.center, Quat4.fromBasis(frame.right, frame.up, frame.normal))
        val camWorld = planeWorld * planeInCam.inverse()
        val tcam = hmd.inverse() * camWorld
        // reprojection error with the solved camera
        val err = frame.corners().zip(q).maxOf { (wpt, px) -> project(wpt, camWorld, k)?.dist(px) ?: Float.MAX_VALUE }
        return Calibration(tcam, err, (frame.center - hmd.p).length, degraded = false)
    }

    /** Distance from the frame centre to the camera, for the "离校准距离 > 1 m" hint. */
    fun distance(frame: SnapFrame, cam: Rigid): Float = frame.center.distanceTo(cam.p)

    fun sharpness(lum: IntArray, w: Int, h: Int): Double {
        // variance of the 4-neighbour Laplacian (design capture 连拍: pick the sharpest of up to 3)
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = 4 * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - w] - lum[i + w]
            sum += l; sq += l.toDouble() * l; n++
        }
        val mean = sum / n
        return sq / n - mean * mean
    }

    fun norm(v: DoubleArray): Double = sqrt(v.sumOf { it * it })
}

/** A solved (or degraded) camera extrinsic (design dataModel.settings.calibration). */
data class Calibration(val tcam: Rigid, val rmsPx: Float, val distM: Float, val degraded: Boolean) {
    /** Degraded = only the rotation kept, the default 3.2 cm offset (design calibrateOnce.fallbackIfUnstable). */
    fun degrade(): Calibration = copy(tcam = Rigid(Rectify.DEFAULT_TCAM.p, tcam.q), degraded = true)

    companion object {
        const val MAX_ERR_PX = 3f
    }
}
