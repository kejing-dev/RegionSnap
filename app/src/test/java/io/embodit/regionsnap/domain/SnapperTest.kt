package io.embodit.regionsnap.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine end to end, run once per source: the controller and the eye + hand must give the same frames. */
class SnapperTest {
    private val eye = Vec3(0f, 1.6f, 0f)
    // a wall 1.5 m ahead: a 4 × 3 m rectangle facing +Z
    private val wall = PlaneSurface("w", "WALL", listOf(Vec3(-2f, 0f, -1.5f), Vec3(2f, 0f, -1.5f), Vec3(2f, 3f, -1.5f), Vec3(-2f, 3f, -1.5f)))

    private fun dirTo(p: Vec3) = (p - eye).normalized()

    /** Eye + hand: the pinching hand rides 0.45 m along the aim (like the debug stand-in), so it moves as the aim does. */
    private fun frameOf(src: Source, dir: Vec3, pressed: Boolean) =
        if (src == Source.CONTROLLER) InputFrame(src, eye, dir, pressed)
        else InputFrame(src, eye, dir, pressed, pinchPoint = eye + dir * 0.45f, eye = eye)

    private fun run(s: Snapper, src: Source, pts: List<Vec3>, planes: List<PlaneSurface>): List<SnapEvent> {
        val ev = ArrayList<SnapEvent>()
        var t = 0L
        // aim at the start, press, sweep, release
        ev += s.step(frameOf(src, dirTo(pts.first()), false), planes, t, 0.016f); t += 16
        for (p in pts) { ev += s.step(frameOf(src, dirTo(p), true), planes, t, 0.016f); t += 16 }
        // the hand stops before it lets go (eye + hand: the smoothed pinch point catches up)
        repeat(10) { ev += s.step(frameOf(src, dirTo(pts.last()), true), planes, t, 0.016f); t += 16 }
        ev += s.step(frameOf(src, dirTo(pts.last()), false), planes, t, 0.016f)
        return ev
    }

    private fun sweep(a: Vec3, b: Vec3, n: Int = 24) = (0..n).map { a.lerp(b, it.toFloat() / n) }

    @Test fun sameBoxForBothSources() {
        for (src in listOf(Source.CONTROLLER, Source.EYE)) {
            val s = Snapper()
            val ev = run(s, src, sweep(Vec3(-0.2f, 1.7f, -1.5f), Vec3(0.22f, 1.4f, -1.5f)), listOf(wall))
            assertTrue("$src framed", ev.any { it is SnapEvent.Framed })
            val f = s.frame!!
            assertEquals(Surface.WALL, f.surface)
            // v0.1.4: the stroke plane faces the user at the start point, so on a wall seen straight on widths are ~3 % off
            assertEquals(0.42f, f.w, 0.015f)
            assertEquals(0.30f, f.h, 0.01f)
            // sticks to the wall, level, facing the user
            assertEquals(-1.5f, f.center.z, 0.02f)
            assertEquals(0f, Frames.levelAngleDeg(f), 0.5f)
            assertTrue(f.normal.z > 0.99f)
        }
    }

    @Test fun pressAgainShootsForBothSources() {
        for (src in listOf(Source.CONTROLLER, Source.EYE)) {
            val s = Snapper()
            run(s, src, sweep(Vec3(-0.2f, 1.7f, -1.5f), Vec3(0.2f, 1.4f, -1.5f)), listOf(wall))
            // aim at the middle of the wall (not a handle), press and release without moving
            val at = dirTo(Vec3(0.6f, 1.2f, -1.5f))
            s.step(frameOf(src, at, false), listOf(wall), 1000, 0.016f)
            s.step(frameOf(src, at, true), listOf(wall), 1016, 0.016f)
            val ev = s.step(frameOf(src, at, false), listOf(wall), 1032, 0.016f)
            assertTrue("$src shoot", ev.contains(SnapEvent.Shoot))
        }
    }

    @Test fun floatingWhenNoPlane() {
        val s = Snapper()
        run(s, Source.EYE, sweep(Vec3(-0.2f, 1.7f, -1.2f), Vec3(0.2f, 1.4f, -1.2f)), emptyList())
        val f = s.frame!!
        assertTrue(f.floating)
        assertEquals(1.2f, f.depth, 0.01f)
    }

    @Test fun tooSmallKeepsTheOldFrame() {
        val s = Snapper()
        run(s, Source.CONTROLLER, sweep(Vec3(-0.2f, 1.7f, -1.5f), Vec3(0.2f, 1.4f, -1.5f)), listOf(wall))
        val old = s.frame
        val ev = run(s, Source.CONTROLLER, sweep(Vec3(0.6f, 1.0f, -1.5f), Vec3(0.62f, 1.01f, -1.5f)), listOf(wall))
        assertTrue(ev.contains(SnapEvent.TooSmall) || ev.contains(SnapEvent.Shoot))
        assertEquals(old, s.frame)
    }

    @Test fun cornerHandleKeepsTheOppositeCorner() {
        val s = Snapper()
        run(s, Source.CONTROLLER, sweep(Vec3(-0.2f, 1.7f, -1.5f), Vec3(0.2f, 1.4f, -1.5f)), listOf(wall))
        val f0 = s.frame!!
        val br = f0.corners()[2]
        val tl = f0.corners()[0]
        // grab BR and pull it 10 cm right, 10 cm down
        val ev = run(s, Source.CONTROLLER, sweep(br, br + Vec3(0.1f, -0.1f, 0f)), listOf(wall))
        assertTrue(ev.any { it is SnapEvent.Grab && it.handle == 2 })
        val f1 = s.frame!!
        assertEquals(0f, f1.corners()[0].distanceTo(tl), 0.005f)
        assertEquals(f0.w + 0.1f, f1.w, 0.01f)
        assertEquals(f0.h + 0.1f, f1.h, 0.01f)
    }

    @Test fun edgeHandleMovesOnlyAlongItsNormal() {
        val f = SnapFrame(Vec3(0f, 1.5f, -1.5f), Vec3(0f, 0f, 1f), Vec3.UP, 0f, 0.4f, 0.3f, Surface.WALL)
        // right edge (5) dragged up-right: only the width changes
        val g = Frames.dragHandle(f, 5, Vec3(0.35f, 1.9f, -1.5f))
        assertEquals(0.3f, g.h, 1e-4f)
        assertEquals(0.55f, g.w, 1e-3f)
        assertEquals(f.corners()[0].x, g.corners()[0].x, 1e-4f)
    }

    @Test fun stickWithoutGripNoLongerRolls() {
        // v0.1.4: the frame stays upright; turning is grip + stick about the vertical axis (FacingTest)
        val s = Snapper()
        s.setFrame(SnapFrame(Vec3(0f, 1.5f, -1.5f), Vec3(0f, 0f, 1f), Vec3.UP, 0f, 0.4f, 0.3f, Surface.WALL))
        s.step(InputFrame(Source.CONTROLLER, eye, Vec3.AHEAD, false, stickX = -1f), listOf(wall), 0, 0.016f)
        assertEquals(0f, s.frame!!.rollDeg, 0.01f)
        assertEquals(Vec3(0f, 0f, 1f), s.frame!!.normal)
    }

    @Test fun floatingDepthClamps() {
        var f = SnapFrame(Vec3(0f, 1.6f, -1.2f), Vec3(0f, 0f, 1f), Vec3.UP, 0f, 0.4f, 0.3f, Surface.FLOATING, 1.2f)
        repeat(100) { f = Frames.push(f, eye, 0.1f) }
        assertEquals(4f, f.depth, 1e-3f)
        repeat(100) { f = Frames.push(f, eye, -0.1f) }
        assertEquals(0.3f, f.depth, 1e-3f)
    }

    @Test fun buttonsShootAndRedraw() {
        val s = Snapper()
        s.setFrame(SnapFrame(Vec3(0f, 1.5f, -1.5f), Vec3(0f, 0f, 1f), Vec3.UP, 0f, 0.4f, 0.3f, Surface.WALL))
        assertTrue(s.step(InputFrame(Source.CONTROLLER, eye, Vec3.AHEAD, false, shootKey = true), emptyList(), 0, 0.016f).contains(SnapEvent.Shoot))
        assertTrue(s.step(InputFrame(Source.CONTROLLER, eye, Vec3.AHEAD, false, clearKey = true), emptyList(), 16, 0.016f).contains(SnapEvent.Cleared))
        assertNull(s.frame)
    }

    @Test fun uiPressNeverDraws() {
        val s = Snapper()
        val pts = sweep(Vec3(-0.2f, 1.7f, -1.5f), Vec3(0.22f, 1.4f, -1.5f))
        s.step(InputFrame(Source.EYE, eye, dirTo(pts[0]), false, onUi = true), listOf(wall), 0, 0.016f)
        for (p in pts) s.step(InputFrame(Source.EYE, eye, dirTo(p), true, onUi = true), listOf(wall), 16, 0.016f)
        s.step(InputFrame(Source.EYE, eye, dirTo(pts.last()), false, onUi = true), listOf(wall), 32, 0.016f)
        assertNull(s.frame)
    }

    @Test fun loopOnTheWall() {
        val s = Snapper()
        val c = Vec3(0f, 1.5f, -1.5f)
        val ev = run(s, Source.EYE, Snapper.circle(c, Vec3(0f, 0f, 1f), 0.15f), listOf(wall))
        assertTrue(ev.contains(SnapEvent.Framed(StrokeJudge.Kind.LOOP)))
        assertEquals(0.3f, s.frame!!.w, 0.01f)
        assertEquals(0.3f, s.frame!!.h, 0.01f)
    }

    @Test fun sameAsLast() {
        val s = Snapper()
        run(s, Source.CONTROLLER, sweep(Vec3(-0.2f, 1.7f, -1.5f), Vec3(0.2f, 1.4f, -1.5f)), listOf(wall))
        s.clear()
        s.step(InputFrame(Source.CONTROLLER, eye, dirTo(Vec3(0.5f, 1.2f, -1.5f)), false), listOf(wall), 0, 0.016f)
        assertTrue(s.sameAsLast())
        assertNotNull(s.frame)
        assertEquals(0.4f, s.frame!!.w, 0.015f)
        assertEquals(0.5f, s.frame!!.center.x, 0.01f)
        // and it faces the user (v0.1.4)
        assertTrue(kotlin.math.abs(Frames.yawOffsetDeg(s.frame!!, eye)) < 2f)
    }
}
