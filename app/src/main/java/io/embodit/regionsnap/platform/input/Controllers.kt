package io.embodit.regionsnap.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerAction
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.regionsnap.domain.Quat4
import io.embodit.regionsnap.domain.Vec3
import io.embodit.regionsnap.platform.RsLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A pointing ray in Stage metres. */
data class Ray(val origin: Vec3, val dir: Vec3)

/** Button edges since the last frame: A / X shoot, B / Y redraw (design input.controller), trigger press edges. */
data class Buttons(val shoot: Int, val clear: Int, val trigger: Int)

/**
 * Both PICO controllers (design input.controller): aim ray + trigger (≥ 0.6 down, ≤ 0.3 up), thumbstick, A / X / B / Y.
 * The hand whose trigger went down last is the aiming hand. `ControllerTrackingProvider` (Full Space only): the action
 * listener runs on the provider's thread and only copies values into atomics; the frame system reads them.
 */
class Controllers {
    private var provider: ControllerTrackingProvider? = null
    private val trigR = AtomicInteger(0)
    private val trigL = AtomicInteger(0)
    private val stickRx = AtomicInteger(0)
    private val stickRy = AtomicInteger(0)
    private val stickLx = AtomicInteger(0)
    private val stickLy = AtomicInteger(0)
    private val shootEdges = AtomicInteger(0)
    private val clearEdges = AtomicInteger(0)
    private val trigEdges = AtomicInteger(0)
    private val gripR = AtomicInteger(0)
    private val gripL = AtomicInteger(0)
    private val down = Array(6) { AtomicBoolean(false) }
    private val actions = AtomicInteger(0)
    private var lastSupport: DataProvider.SupportState? = null

    /** The aiming hand: true = right (the last trigger pressed). */
    @Volatile var rightAims = true
        private set

    /** Debug `controller on`: the provider counts as SUPPORTED (PICO Emulator 6.1.0 reports DEVICE_NOT_SUPPORTED). */
    @Volatile var debugPresent = false

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a: ControllerActionData ->
            actions.incrementAndGet()
            val r = runCatching { a.right }.getOrNull()
            val l = runCatching { a.left }.getOrNull()
            trigR.set(bits(r?.let { runCatching { it.triggerValue }.getOrDefault(0f) } ?: 0f))
            trigL.set(bits(l?.let { runCatching { it.triggerValue }.getOrDefault(0f) } ?: 0f))
            stickRx.set(bits(r?.let { runCatching { it.thumbstickValue.x }.getOrDefault(0f) } ?: 0f))
            stickRy.set(bits(r?.let { runCatching { it.thumbstickValue.y }.getOrDefault(0f) } ?: 0f))
            stickLx.set(bits(l?.let { runCatching { it.thumbstickValue.x }.getOrDefault(0f) } ?: 0f))
            stickLy.set(bits(l?.let { runCatching { it.thumbstickValue.y }.getOrDefault(0f) } ?: 0f))
            gripR.set(bits(r?.let { runCatching { if (it.gripPressed) 1f else it.gripValue }.getOrDefault(0f) } ?: 0f))
            gripL.set(bits(l?.let { runCatching { if (it.gripPressed) 1f else it.gripValue }.getOrDefault(0f) } ?: 0f))
            edge(0, pressed(r) { it.aButtonPressed } || pressed(l) { it.xButtonPressed }, shootEdges)
            edge(1, pressed(r) { it.bButtonPressed } || pressed(l) { it.yButtonPressed }, clearEdges)
            // trigger hysteresis per hand: ≥ 0.6 down, ≤ 0.3 up
            stepTrigger(2, f(trigR), true)
            stepTrigger(3, f(trigL), false)
        }

    private inline fun pressed(c: ControllerAction?, f: (ControllerAction) -> Boolean): Boolean = c?.let { runCatching { f(it) }.getOrDefault(false) } == true

    private fun stepTrigger(i: Int, v: Float, right: Boolean) {
        if (!down[i].get() && v >= DOWN) {
            down[i].set(true)
            rightAims = right
            trigEdges.incrementAndGet()
        } else if (down[i].get() && v <= UP) down[i].set(false)
    }

    private fun edge(i: Int, pressed: Boolean, counter: AtomicInteger) {
        if (pressed && !down[i].getAndSet(true)) counter.incrementAndGet()
        if (!pressed) down[i].set(false)
    }

    private fun bits(v: Float) = java.lang.Float.floatToIntBits(if (v.isFinite()) v else 0f)

    private fun f(a: AtomicInteger) = java.lang.Float.intBitsToFloat(a.get())

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        // listener first, then start (design container.lifecycle)
        runCatching { p.addControllerActionListener(listener) }
        val r = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        RsLog.i("INPUT controller start=${r.getOrNull() ?: r.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        for (a in listOf(trigR, trigL, stickRx, stickRy, stickLx, stickLy, gripR, gripL)) a.set(0)
        for (d in down) d.set(false)
    }

    fun supportState(): DataProvider.SupportState? {
        val s = if (debugPresent) DataProvider.SupportState.SUPPORTED else provider?.let { runCatching { it.supportState }.getOrNull() }
        if (s != lastSupport) {
            lastSupport = s
            RsLog.i("INPUT controller support=$s actions=${actions.get()}")
        }
        return s
    }

    val available: Boolean
        get() = supportState() == DataProvider.SupportState.SUPPORTED

    /** The aiming hand's trigger is held (hysteresis applied in the listener). */
    val triggerHeld: Boolean
        get() = down[if (rightAims) 2 else 3].get()

    /** The aiming hand's pose as a ray (origin, -Z of the controller; aim vs grip pose → N12). */
    fun aimRay(): Ray? {
        val p = provider ?: return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val c = (if (rightAims) data.right else data.left) ?: (if (rightAims) data.left else data.right) ?: return null
        val pos = Vec3(c.position.x, c.position.y, c.position.z)
        if (!pos.isFinite() || pos.length < 1e-4f) return null
        val q = Quat4(c.rotation.x, c.rotation.y, c.rotation.z, c.rotation.w).normalized()
        val fwd = q.rotate(Vec3.AHEAD)
        if (!fwd.isFinite() || fwd.length < 0.5f) return null
        return Ray(pos, fwd.normalized())
    }

    fun takeButtons(): Buttons = Buttons(shootEdges.getAndSet(0), clearEdges.getAndSet(0), trigEdges.getAndSet(0))

    /** Either grip held (≥ 0.6, or the SDK's gripPressed): with the stick it turns the frame about the vertical axis. */
    val gripHeld: Boolean
        get() = f(gripR) >= DOWN || f(gripL) >= DOWN

    /** The stick with the larger push (either hand turns / pushes the frame). */
    fun stick(): Pair<Float, Float> {
        val r = f(stickRx) to f(stickRy)
        val l = f(stickLx) to f(stickLy)
        val mr = kotlin.math.abs(r.first) + kotlin.math.abs(r.second)
        val ml = kotlin.math.abs(l.first) + kotlin.math.abs(l.second)
        return if (mr >= ml) r else l
    }

    private companion object {
        const val DOWN = 0.6f
        const val UP = 0.3f
    }
}
