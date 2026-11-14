package io.embodit.regionsnap.platform.input

import android.os.Build
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingProvider
import io.embodit.regionsnap.domain.Quat4
import io.embodit.regionsnap.domain.Vec3
import io.embodit.regionsnap.platform.RsLog

/** True on the PICO emulator (Spatial Ruler `SystemPointer.onEmulator`). */
object Device {
    val onEmulator: Boolean = Build.HARDWARE == "ranchu" || Build.DEVICE.contains("emulator") || Build.FINGERPRINT.contains("emulator")
}

/** One hand this frame: thumb-index tip distance (pinch, design input.eyeHand.pinch), the pointing ray wrist → index tip and the thumb-index midpoint (what an indirect pinch-drag moves). */
data class HandSample(val right: Boolean, val pinchM: Float, val finger: Ray?, val pinch: Vec3? = null)

/**
 * The hands (`HandTrackingProvider`, Full Space only), read once per frame by the frame system. Not started on the
 * emulator unless asked for (debug `hands live`): after the provider ran there, the emulator's own gaze + pinch stopped
 * reaching any app until it was restarted (Spatial Ruler / X-ray Hand finding).
 */
class Hands {
    private var provider: HandTrackingProvider? = null
    private var lastSupport: DataProvider.SupportState? = null

    /** Debug: report unsupported (S17b). */
    @Volatile var forceLost = false

    fun start() {
        if (provider != null) return
        val p = HandTrackingProvider()
        val r = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        RsLog.i("INPUT hands start=${r.getOrNull() ?: r.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { runCatching { it.stop() } }
        provider = null
    }

    val running: Boolean
        get() = provider != null

    fun supportState(): DataProvider.SupportState? {
        if (forceLost) return DataProvider.SupportState.DEVICE_NOT_SUPPORTED
        val s = provider?.let { runCatching { it.supportState }.getOrNull() }
        if (s != lastSupport) {
            lastSupport = s
            RsLog.i("INPUT hands support=$s")
        }
        return s
    }

    val available: Boolean
        get() = supportState() == DataProvider.SupportState.SUPPORTED

    fun sample(): List<HandSample> {
        if (!available) return emptyList()
        val data = runCatching { provider!!.latestData }.getOrNull() ?: return emptyList()
        return listOfNotNull(hand(data.left, false), hand(data.right, true))
    }

    private fun hand(pose: HandPose?, right: Boolean): HandSample? {
        val joints = pose?.handJoints ?: return null
        var thumb: Vec3? = null
        var index: Vec3? = null
        var wrist: Vec3? = null
        var knuckle: Vec3? = null
        for (j in joints) {
            val v = Vec3(j.position.x, j.position.y, j.position.z)
            if (!v.isFinite()) return null
            when (j.index) {
                HandJoint.Index.THUMB_TIP -> thumb = v
                HandJoint.Index.INDEX_TIP -> index = v
                HandJoint.Index.WRIST -> wrist = v
                HandJoint.Index.INDEX_PROXIMAL -> knuckle = v
                else -> Unit
            }
        }
        val t = thumb ?: return null
        val i = index ?: return null
        // the provider hands out a pose at the origin before it has seen a hand
        if (i.length < 1e-3f) return null
        val w = wrist ?: knuckle
        val ray = w?.let { a -> (i - a).takeIf { it.length > 0.03f }?.let { Ray(i, it.normalized()) } }
        return HandSample(right, t.distanceTo(i), ray, (t + i) * 0.5f)
    }
}

/**
 * Eye gaze (`EyeTrackingProvider`, Full Space only, runtime permission com.picovr.permission.EYE_TRACKING; design F7). The
 * gaze pose looks along its local -Z. [ray] is null whenever the eyes cannot be used; [unavailableReason] says why.
 */
class Eyes {
    private var provider: EyeTrackingProvider? = null
    private var lastStamp = 0L
    private var lastFreshMs = 0L

    /** Debug `noPermission eye`: pretend the eyes are unavailable (S17). */
    @Volatile var forceOff = false

    fun start() {
        if (provider != null) return
        val p = EyeTrackingProvider()
        val r = runCatching { p.start() }
        RsLog.i("INPUT eye start=${r.getOrNull() ?: r.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        lastStamp = 0L
    }

    fun supportState(): DataProvider.SupportState? = provider?.let { p -> runCatching { p.supportState }.getOrNull() }

    /** null = eyes in use; else "off", "permission", "unsupported", "no-data". */
    fun unavailableReason(nowMs: Long): String? {
        if (forceOff) return "permission"
        return when (supportState()) {
            DataProvider.SupportState.SUPPORTED -> if (nowMs - lastFreshMs > STALE_MS) "no-data" else null
            DataProvider.SupportState.WITHOUT_PERMISSION -> "permission"
            null -> "no-data"
            else -> "unsupported"
        }
    }

    fun ray(nowMs: Long): Ray? {
        val p = provider ?: return null
        if (forceOff || runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        if (data.timestamp == 0L) return null
        if (data.timestamp != lastStamp) {
            lastStamp = data.timestamp
            lastFreshMs = nowMs
        }
        if (nowMs - lastFreshMs > STALE_MS) return null
        val pos = data.eyePose.position
        val rot = data.eyePose.rotation
        val o = Vec3(pos.x, pos.y, pos.z)
        if (!o.isFinite() || o.length < 1e-4f) return null
        val d = Quat4(rot.x, rot.y, rot.z, rot.w).normalized().rotate(Vec3.AHEAD)
        return Ray(o, d.normalized())
    }

    private companion object {
        const val STALE_MS = 1_000L
    }
}
