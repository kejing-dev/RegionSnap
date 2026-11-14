package io.embodit.regionsnap.platform.sense

import android.os.Handler
import android.os.Looper
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult
import io.embodit.regionsnap.domain.PlaneSurface
import io.embodit.regionsnap.domain.Vec3
import io.embodit.regionsnap.platform.RsLog
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Detected planes (`PlaneTrackingManager`, Full Space only; Spatial Ruler / Cam Coverage). Only kept as polygons for the
 * aim's ray / plane maths (design rules: no plane colliders, no occlusion, D24); nothing is drawn or made interactive.
 */
class Planes {
    private val main = Handler(Looper.getMainLooper())
    private val surfaces = LinkedHashMap<String, PlaneSurface>()
    private var subscription: Cancellable? = null

    val all: Collection<PlaneSurface>
        get() = surfaces.values

    /** Debug `plane x y z nx ny nz w h`: stand-in planes (emulator room has none until it scans). */
    val debug = ArrayList<PlaneSurface>()

    fun current(): List<PlaneSurface> = if (debug.isEmpty()) surfaces.values.toList() else surfaces.values.toList() + debug

    fun start() {
        if (subscription != null) return
        subscription = runCatching { PlaneTrackingManager.subscribeAnchorUpdate { u -> if (Looper.myLooper() == Looper.getMainLooper()) handle(u) else main.post { handle(u) } } }
            .onFailure { RsLog.w("AIM planes subscribe failed: $it") }.getOrNull()
        runCatching { PlaneTrackingManager.start() }.onFailure { RsLog.w("AIM planes start failed: $it") }
        RsLog.i("AIM planes started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        main.removeCallbacksAndMessages(null)
    }

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        if (subscription == null) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                val q = anchor.transform.quaternion
                val p = anchor.transform.position
                val v = runCatching { anchor.vertices.map { val w = q.rotateVector(it) + p; Vec3(w.x, w.y, w.z) } }.getOrNull() ?: return
                if (v.size < 3) return
                val s = PlaneSurface(id, anchor.semantics.name, v)
                if (surfaces.put(id, s) == null) RsLog.i("AIM plane add ${s.semantic} n=${surfaces.size}")
            }
            AnchorUpdate.Event.REMOVED -> surfaces.remove(id)
            else -> Unit
        }
    }
}

/**
 * World anchors for pinned notes (`WorldTrackingManager`, Full Space only; design pin.anchor): -6 "not ready" retried,
 * load retried 3 × 500 ms (N10), an empty store (-7) is no anchors. Main thread only.
 */
class Anchors {
    private var subscription: Cancellable? = null

    /** Debug `anchorsFail`: loading fails (S19) and creation fails. */
    @Volatile var fail = false

    fun start() {
        if (subscription != null) return
        // world tracking answers -6 to a creation until someone listens (Spatial Ruler)
        subscription = runCatching { WorldTrackingManager.subscribeAnchorUpdate { } }.onFailure { RsLog.w("ANCHOR subscribe failed: $it") }.getOrNull()
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
    }

    /** Creates an anchor at [p]; the uuid, or null on failure (the note still shows, design S11). */
    suspend fun create(p: Vec3, name: String): String? {
        if (fail) return null.also { RsLog.i("ANCHOR create failed (debug)") }
        repeat(8) { attempt ->
            val r = guarded { WorldTrackingManager.createAnchor(Vector3(p.x, p.y, p.z), EulerAngles(0f, 0f, 0f), name) }
            when (r) {
                is WorldTrackingResult.Success -> return r.data?.anchorUUID?.toString().also { RsLog.i("ANCHOR created $it tries=${attempt + 1}") }
                is WorldTrackingResult.Error -> {
                    RsLog.w("ANCHOR create error ${r.errorCode} ${r.errorMessage}")
                    if (r.errorCode != NOT_READY) return null
                }
                null -> return null
            }
            delay(750)
        }
        return null
    }

    /** All stored anchors: uuid → position; null when loading failed 3 times (S19). */
    suspend fun loadAll(): Map<String, Vec3>? {
        if (fail) return null.also { RsLog.i("ANCHOR load failed (debug)") }
        repeat(3) { attempt ->
            when (val r = guarded { WorldTrackingManager.loadAnchor() }) {
                is WorldTrackingResult.Success -> return (r.data ?: emptyArray()).associate { a ->
                    val t = a.transform.position
                    a.anchorUUID.toString() to Vec3(t.x, t.y, t.z)
                }.also { RsLog.i("ANCHOR loaded ${it.size} tries=${attempt + 1}") }
                is WorldTrackingResult.Error -> {
                    if (r.errorCode == NOT_FOUND) return emptyMap()
                    RsLog.w("ANCHOR load error ${r.errorCode} ${r.errorMessage} try=${attempt + 1}")
                }
                null -> Unit
            }
            delay(500)
        }
        return null
    }

    suspend fun remove(uuid: String) {
        val r = guarded { WorldTrackingManager.removeAnchor(UUID.fromString(uuid)) }
        RsLog.i("ANCHOR remove $uuid ${if (r is WorldTrackingResult.Error) "error ${r.errorCode}" else "ok"}")
    }

    private inline fun <T> guarded(block: () -> T): T? =
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            RsLog.w("ANCHOR call failed: $t")
            null
        }

    private companion object {
        const val NOT_FOUND = -7
        const val NOT_READY = -6
    }
}
