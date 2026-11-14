package io.embodit.regionsnap.domain

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins have one source; delete runs unpin → private → MediaStore (design rules, S14 R2-7); placement is yaw-only (D9). */
class LibraryTest {
    private class Log : LibrarySink {
        val calls = ArrayList<String>()
        var entities = HashSet<String>()
        override fun removeAnchor(uuid: String) { calls += "anchor:$uuid" }
        override fun removeEntity(shotId: String) { calls += "entity:$shotId"; entities.remove(shotId) }
        override fun deletePrivate(shotId: String) { calls += "private:$shotId" }
        override fun deleteMedia(uri: String) { calls += "media:$uri" }
        override fun persist(shots: List<Shot>, pins: List<Pin>) { calls += "persist" }
    }

    private val frame = SnapFrame(Vec3(0f, 1.5f, -1.5f), Vec3(0f, 0f, 1f), Vec3.UP, 0f, 0.42f, 0.3f, Surface.WALL)

    private fun shot(id: String, t: Long = 1) = Shot(id, t, 1180, 843, 0.42f, 0.3f, Surface.WALL, null, true, "content://m/$id", frame = frame)

    @Test fun pinUnpinDeleteKeepsThreePlacesInStep() {
        val log = Log()
        val lib = Library(log)
        lib.add(shot("a"))
        assertTrue(lib.pin("a", "u1", frame))
        log.entities += "a"
        // the grid badge, the detail button and the note all read isPinned / pins
        assertTrue(lib.isPinned("a"))
        lib.unpin("a")
        assertFalse(lib.isPinned("a"))
        assertTrue(log.calls.contains("anchor:u1"))
        assertTrue(lib.pin("a", "u2", frame))
        log.entities += "a"
        log.calls.clear()
        lib.delete("a")
        val order = log.calls.filter { it != "persist" }
        assertEquals(listOf("anchor:u2", "entity:a", "private:a", "media:content://m/a"), order)
        assertTrue(lib.shots.isEmpty())
        assertTrue(lib.pins.isEmpty())
        assertTrue(log.entities.isEmpty())
    }

    @Test fun twentyPinsAtMost() {
        val lib = Library(Log())
        for (i in 0 until 21) lib.add(shot("s$i", i.toLong()))
        for (i in 0 until 20) assertTrue(lib.pin("s$i", "u$i", frame))
        assertFalse(lib.pin("s20", "u20", frame))
        // re-pinning one already up is fine
        assertTrue(lib.pin("s3", "u3b", frame))
    }

    @Test fun newestFirst() {
        val lib = Library(Log())
        lib.load(listOf(shot("old", 1), shot("new", 5)), listOf(Pin("gone", "x", frame)))
        assertEquals("new", lib.shots.first().id)
        // a pin of a shot that no longer exists is dropped
        assertTrue(lib.pins.isEmpty())
    }

    @Test fun placementIsUprightWhateverTheHead() {
        val base = Vec3(0.3f, 1.6f, 0.2f)
        for ((pitch, roll) in listOf(-30f to 0f, 30f to 0f, 0f to 20f, 25f to -15f)) {
            val q = Quat4.axisAngle(Vec3.UP, Num.rad(40f)) * Quat4.axisAngle(Vec3.RIGHT, Num.rad(pitch)) * Quat4.axisAngle(Vec3(0f, 0f, -1f), Num.rad(roll))
            val head = HeadPose(base, q)
            val u = placeUpright(head, 0f, 1.0f, 15f)
            // up stays world up (< 1°), centre 15° below the eye, 1 m away horizontally
            assertTrue(Num.angleDeg(u.rotation.rotate(Vec3.UP), Vec3.UP) < 1f)
            val drop = Num.deg(kotlin.math.atan2(base.y - u.position.y, Vec3(u.position.x - base.x, 0f, u.position.z - base.z).length))
            assertEquals(15f, drop, 0.5f)
            // faces the head
            val to = (base - u.position).let { Vec3(it.x, 0f, it.z).normalized() }
            assertTrue(Num.angleDeg(u.normal, to) < 1f)
        }
    }

    @Test fun pinInFrontFacesYouFromAllFourSides() {
        for (yawDeg in listOf(0f, 90f, 180f, -90f)) {
            val head = HeadPose(Vec3(1f, 1.6f, -2f), Quat4.axisAngle(Vec3.UP, Num.rad(yawDeg)))
            val f = pinInFront(head, 0.4f, 0.3f)
            val to = (head.position - f.center).let { Vec3(it.x, 0f, it.z).normalized() }
            assertTrue("yaw $yawDeg", Num.angleDeg(f.normal, to) < 10f)
            assertTrue(f.w > 0f && f.h > 0f)
            // text upright: the note's up is world up
            assertTrue(abs(f.up.y - 1f) < 1e-3f)
        }
    }

    @Test fun pinsBehindAreReported() {
        val head = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
        val ahead = frame
        val behind = frame.copy(center = Vec3(0f, 1.5f, 1.5f))
        assertFalse(pinsOutOfSight(head, listOf(ahead)))
        assertTrue(pinsOutOfSight(head, listOf(behind)))
        assertFalse(pinsOutOfSight(head, listOf(behind, ahead)))
        assertFalse(pinsOutOfSight(head, emptyList()))
    }
}
