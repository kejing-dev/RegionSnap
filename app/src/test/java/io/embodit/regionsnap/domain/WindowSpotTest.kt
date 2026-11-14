package io.embodit.regionsnap.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA #1 q1-1 (D41 / profile 48): the main window must not cover the frame or its shutter bar. Every opening path asks
 * [WindowSpot.turnDeg]; an open window that covers the frame is tucked ([WindowSpot.gapDeg] < 1°).
 */
class WindowSpotTest {
    private val size = WindowSpot.Size(600f, 680f, 0.0008f)
    private val barHalfW = 540f * 0.0008f * 1.25f / 2f
    private val barDrop = 0.04f + 72f * 0.0008f * 1.25f

    private fun head(yawDeg: Float, pitchDeg: Float = 0f, at: Vec3 = Vec3(0f, 1.6f, 0f)): HeadPose =
        HeadPose(at, (Quat4.axisAngle(Vec3.UP, Num.rad(yawDeg)) * Quat4.axisAngle(Vec3.RIGHT, Num.rad(pitchDeg))).normalized())

    /** A wall frame [d] m along the gaze yaw [yawDeg], [right] m to its right, facing back at the head. */
    private fun wallFrame(h: HeadPose, d: Float, right: Float = 0f, w: Float = 0.42f, hh: Float = 0.3f): SnapFrame {
        val yaw = h.yaw(0f)
        val f = Yaw.dir(yaw)
        val r = Vec3(-f.z, 0f, f.x)
        val c = h.position + f * d + r * right
        return SnapFrame(c, f * -1f, Vec3.UP, 0f, w, hh, Surface.WALL)
    }

    private fun keep(f: SnapFrame) = WindowSpot.keepClear(f, barHalfW, barDrop)

    private fun gapOpenedAt(h: HeadPose, keep: List<Vec3>) = WindowSpot.gapIfOpened(h.position, h.yaw(0f), keep, size)

    @Test fun qaCaseFrameRightOfWindowIsCoveredSoTucked() {
        // goto S06: 0.42 x 0.3 at 1.4 m, 0.62 m right of the window opened straight ahead (QA S06_en: left half covered)
        val h = head(0f)
        val k = keep(wallFrame(h, 1.4f, 0.62f))
        assertTrue("gap ${gapOpenedAt(h, k)}", gapOpenedAt(h, k) < WindowSpot.TUCK_GAP_DEG)
    }

    @Test fun frameStraightAheadIsCovered() {
        val h = head(0f)
        assertEquals(0f, gapOpenedAt(h, keep(wallFrame(h, 1.5f))), 0.01f)
    }

    @Test fun frameWellToTheSideIsClear() {
        // [Same as last] at the aim beside the window (QA real_S06_en): fully visible
        val h = head(0f)
        val k = keep(wallFrame(h, 1.4f, 1.2f))
        assertTrue("gap ${gapOpenedAt(h, k)}", gapOpenedAt(h, k) >= WindowSpot.MIN_GAP_DEG)
        assertEquals(0f, WindowSpot.turnDeg(h, k, size), 0f)
    }

    @Test fun nothingToKeepClearNeverWaits() {
        assertEquals(0f, WindowSpot.turnDeg(head(37f), emptyList(), size), 0f)
    }

    @Test fun turningByTurnDegClearsFromAllFourSides() {
        for (yaw in listOf(0f, 90f, 180f, -90f, -44f)) {
            val h = head(yaw)
            val k = keep(wallFrame(h, 1.5f, 0.1f))
            val turn = WindowSpot.turnDeg(h, k, size)
            assertNotEquals("yaw $yaw must wait", 0f, turn)
            assertTrue("yaw $yaw turn $turn is a sane head turn", kotlin.math.abs(turn) in 10f..60f)
            val turned = head(yaw + turn)
            val gap = gapOpenedAt(turned, k)
            assertTrue("yaw $yaw opened at ${yaw + turn}: gap $gap", gap >= WindowSpot.MIN_GAP_DEG)
            // a window opened there is not tucked straight away
            assertFalse(WindowSpot.gapDeg(turned.position, WindowSpot.centerFor(turned.position, turned.yaw(0f)), turned.yaw(0f), k, size) < WindowSpot.TUCK_GAP_DEG)
            // one degree less is not enough (the smallest turn)
            val short = head(yaw + turn - kotlin.math.sign(turn))
            assertTrue("yaw $yaw: ${turn - kotlin.math.sign(turn)} already clear", gapOpenedAt(short, k) < WindowSpot.MIN_GAP_DEG)
        }
    }

    @Test fun turnsAwayFromTheFrameSide() {
        // frame a bit right of the gaze: turn left (+); a bit left: turn right (-)
        val h = head(0f)
        assertTrue(WindowSpot.turnDeg(h, keep(wallFrame(h, 1.5f, 0.15f)), size) > 0f)
        assertTrue(WindowSpot.turnDeg(h, keep(wallFrame(h, 1.5f, -0.15f)), size) < 0f)
    }

    @Test fun headPitchDoesNotChangeTheTurn() {
        // the window opens level (LevelGate); the turn only depends on the yaw
        val h = head(20f)
        val k = keep(wallFrame(h, 1.5f, 0.1f))
        val t0 = WindowSpot.turnDeg(h, k, size)
        for (p in listOf(-30f, 30f)) assertEquals("pitch $p", t0, WindowSpot.turnDeg(head(20f, p), k, size), 0f)
    }

    @Test fun shutterBarCountsToo() {
        // the frame itself just over the window's top edge (TabBar included), its shutter bar inside: still covered
        val h = head(0f)
        val f = SnapFrame(Vec3(0f, 1.6f + 0.97f, -1.9f), Vec3(0f, 0f, 1f), Vec3.UP, 0f, 0.42f, 0.3f, Surface.WALL)
        assertTrue(WindowSpot.gapIfOpened(h.position, 0f, keep(f).take(9), size) > 0f)
        assertEquals(0f, gapOpenedAt(h, keep(f)), 0.01f)
    }

    @Test fun tableFrameBelowTheWindowIsClear() {
        // a frame on a table 0.75 m high, 0.6 m ahead: far under the window and its system strip
        val h = head(0f)
        val f = SnapFrame(Vec3(0f, 0.75f, -0.6f), Vec3.UP, Vec3(0f, 0f, -1f), 0f, 0.3f, 0.21f, Surface.TABLE)
        assertTrue("gap ${gapOpenedAt(h, keep(f))}", gapOpenedAt(h, keep(f)) >= WindowSpot.MIN_GAP_DEG)
    }

    private val plateHalfW = Num.deg(kotlin.math.atan(0.375f / 1f))
    private val plateHalfH = Num.deg(kotlin.math.atan(0.069f / 1f))

    private fun plateClear(h: HeadPose, k: List<Vec3>, p: WindowSpot.PlateSpot): Boolean = k.none { pt ->
        val v = pt - h.position
        val rel = Num.deg(Yaw.wrap(Yaw.of(Vec3(v.x, 0f, v.z)) - h.yaw(0f)))
        val el = Num.deg(kotlin.math.atan2(v.y, kotlin.math.hypot(v.x, v.z)))
        kotlin.math.abs(rel - p.relYawDeg) < plateHalfW && kotlin.math.abs(el + p.downDeg) < plateHalfH
    }

    @Test fun plateStaysInViewAndOffTheFrame() {
        // QA #2 q2-2: never ±40° / ±60°; ≤ 25° off the gaze in each axis, and not over the frame or its shutter bar
        val h = head(0f)
        assertEquals(WindowSpot.PlateSpot(0f, 18f), WindowSpot.plateSpot(h, emptyList(), plateHalfW, plateHalfH))
        val cases = listOf(
            wallFrame(h, 1.4f, 0f), wallFrame(h, 1.4f, 0.62f), wallFrame(h, 1.4f, -0.62f), wallFrame(h, 1.5f, 0.1f, 0.6f, 0.45f),
            SnapFrame(Vec3(0.1f, 1.6f - 0.33f, -1.4f), Vec3(0f, 0f, 1f), Vec3.UP, 0f, 0.42f, 0.3f, Surface.WALL),
            SnapFrame(Vec3(0f, 0.75f, -0.6f), Vec3.UP, Vec3(0f, 0f, -1f), 0f, 0.3f, 0.21f, Surface.TABLE),
        )
        for (f in cases) for (prefer in listOf(0, 1, -1)) {
            val k = keep(f)
            val p = WindowSpot.plateSpot(h, k, plateHalfW, plateHalfH, prefer)
            assertTrue("$f -> $p", kotlin.math.abs(p.relYawDeg) <= 20f && kotlin.math.abs(p.downDeg) <= WindowSpot.PLATE_MAX_OFF_DEG)
            assertTrue("$f -> $p sits on the frame", plateClear(h, k, p))
        }
    }

    @Test fun gateWaitsForTheTurnThenOpens() {
        val g = LevelGate()
        g.ask("preview", 0)
        var t = 0L
        while (t < 5000) {
            assertNull(g.step(t, 0f, 23f))
            assertTrue(g.turnWanted(0f, 23f))
            t += 16
        }
        var opened: String? = null
        while (t < 6000 && opened == null) {
            opened = g.step(t, 1f, 0f)
            t += 16
        }
        assertEquals("preview", opened)
    }

    @Test fun shrinkGoesToTheOutline() {
        // S05: trail points slide onto the rectangle's outline, inside or outside it
        val f = SnapFrame(Vec3(0f, 1.5f, -1.5f), Vec3(0f, 0f, 1f), Vec3.UP, 0f, 0.4f, 0.2f, Surface.WALL)
        for (p in listOf(Vec3(0.5f, 1.5f, -1.5f), Vec3(0.05f, 1.52f, -1.5f), Vec3(-0.3f, 1.8f, -1.5f), Vec3(0f, 1.3f, -1.5f))) {
            val (x, y) = f.local(f.onOutline(p))
            val onEdge = kotlin.math.abs(kotlin.math.abs(x) - 0.2f) < 1e-4f || kotlin.math.abs(kotlin.math.abs(y) - 0.1f) < 1e-4f
            assertTrue("$p -> ($x, $y)", onEdge && kotlin.math.abs(x) <= 0.2001f && kotlin.math.abs(y) <= 0.1001f)
        }
    }
}
