package io.embodit.regionsnap.domain

import kotlin.math.abs

/** One saved shot (design dataModel.shot). Pinned state is NOT here: [Library.pins] is the only source (R1-6). */
data class Shot(
    val id: String,
    val createdAt: Long,
    val pxW: Int,
    val pxH: Int,
    /** Real size in metres; null for a floating frame (R1-12). */
    val widthM: Float?,
    val heightM: Float?,
    val surface: Surface,
    val assumedDepthM: Float?,
    val rectified: Boolean,
    val mediaStoreUri: String?,
    val mediaStoreMissing: Boolean = false,
    /** The frame it was shot from (where 钉回原地 puts it). */
    val frame: SnapFrame,
)

/** A pinned note (design files.pins.json): which shot, its anchor (null = anchor failed, gone after a restart) and pose. */
data class Pin(val shotId: String, val anchorUuid: String?, val frame: SnapFrame, val restored: Boolean = true)

/** Side effects of the library, in the order the design fixes (R2-7: unpin → private files → MediaStore). */
interface LibrarySink {
    fun removeAnchor(uuid: String)
    fun removeEntity(shotId: String)
    fun deletePrivate(shotId: String)
    fun deleteMedia(uri: String)
    fun persist(shots: List<Shot>, pins: List<Pin>)
}

/**
 * Shots and pins (design pin, dataModel). Pure: the Android side gives a [LibrarySink]. The Shots grid badges, the detail
 * buttons and the note entities all derive from [pins] (design rules: three places, one source).
 */
class Library(private val sink: LibrarySink) {
    var shots: List<Shot> = emptyList()
        private set
    var pins: List<Pin> = emptyList()
        private set

    fun load(s: List<Shot>, p: List<Pin>) {
        shots = s.sortedByDescending { it.createdAt }
        pins = p.filter { pin -> s.any { it.id == pin.shotId } }
    }

    fun add(shot: Shot) {
        shots = listOf(shot) + shots.filter { it.id != shot.id }
        sink.persist(shots, pins)
    }

    fun shot(id: String): Shot? = shots.firstOrNull { it.id == id }

    fun pinOf(id: String): Pin? = pins.firstOrNull { it.shotId == id }

    fun isPinned(id: String): Boolean = pinOf(id) != null

    val canPinMore: Boolean
        get() = pins.size < MAX_PINS

    /** Adds or replaces the pin of [shotId]. False when 20 are already up (design pin.limits). */
    fun pin(shotId: String, anchorUuid: String?, frame: SnapFrame): Boolean {
        val existing = pinOf(shotId)
        if (existing == null && !canPinMore) return false
        existing?.anchorUuid?.let { if (it != anchorUuid) sink.removeAnchor(it) }
        pins = pins.filter { it.shotId != shotId } + Pin(shotId, anchorUuid, frame)
        sink.persist(shots, pins)
        return true
    }

    fun movePin(shotId: String, frame: SnapFrame, anchorUuid: String?) {
        val old = pinOf(shotId) ?: return
        if (old.anchorUuid != null && old.anchorUuid != anchorUuid) sink.removeAnchor(old.anchorUuid)
        pins = pins.map { if (it.shotId == shotId) it.copy(frame = frame, anchorUuid = anchorUuid, restored = true) else it }
        sink.persist(shots, pins)
    }

    fun unpin(shotId: String) {
        val p = pinOf(shotId) ?: return
        p.anchorUuid?.let { sink.removeAnchor(it) }
        sink.removeEntity(shotId)
        pins = pins.filter { it.shotId != shotId }
        sink.persist(shots, pins)
    }

    /** Delete (design S14 R2-7): take it down first, then the private files, then the MediaStore copy. */
    fun delete(shotId: String) {
        val s = shot(shotId) ?: return
        unpin(shotId)
        sink.deletePrivate(shotId)
        s.mediaStoreUri?.let { sink.deleteMedia(it) }
        shots = shots.filter { it.id != shotId }
        sink.persist(shots, pins)
    }

    fun markMissing(ids: Set<String>) {
        if (ids.isEmpty()) return
        shots = shots.map { if (it.id in ids) it.copy(mediaStoreMissing = true) else it }
        sink.persist(shots, pins)
    }

    /** After loading the anchors at start: [ids] were not found (S19, listed, shown nowhere until 钉到面前), the rest are back. */
    fun markLost(ids: Set<String>) {
        pins = pins.map { it.copy(restored = it.shotId !in ids) }
    }

    companion object {
        const val MAX_PINS = 20
    }
}

/** Panel / note placement from the head, yaw only (profile D9 placeUpright). */
data class Upright(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)

    /** Unit vector out of the face, towards the viewer. */
    val normal: Vec3
        get() = rotation.rotate(Vec3(0f, 0f, 1f))
}

/**
 * Puts something [distM] in front of the head, [relYawDeg] to the left (+), its centre [downDeg] below eye level, turned
 * to face the head about the vertical only: pitch and roll are always 0 (D9).
 */
fun placeUpright(head: HeadPose, relYawDeg: Float, distM: Float, downDeg: Float): Upright {
    val yaw = head.yaw(0f) + Num.rad(relYawDeg)
    val flat = Yaw.dir(yaw)
    val drop = distM * kotlin.math.tan(Num.rad(downDeg))
    val p = head.position + flat * distM - Vec3.UP * drop
    return Upright(p, yaw)
}

/** "钉到面前" (S19 / [移到面前]): a pin of the shot's size 1 m ahead, upright, facing the head. */
fun pinInFront(head: HeadPose, w: Float, h: Float): SnapFrame {
    val u = placeUpright(head, 0f, 1.0f, 8f)
    return SnapFrame(u.position, u.normal, Vec3.UP, 0f, w, h, Surface.FLOATING, 1f)
}

/** True when every pin is behind (outside ±45° of the head's yaw) or more than 2.5 m away (design pin.restoreRule). */
fun pinsOutOfSight(head: HeadPose, pins: List<SnapFrame>): Boolean {
    if (pins.isEmpty()) return false
    val yaw = head.yaw(0f)
    return pins.all { f ->
        val to = f.center - head.position
        val flat = Vec3(to.x, 0f, to.z)
        val far = flat.length > 2.5f
        val off = if (flat.length < 1e-3f) 0f else abs(Num.deg(Yaw.wrap(Yaw.of(flat) - yaw)))
        far || off > 45f
    }
}
