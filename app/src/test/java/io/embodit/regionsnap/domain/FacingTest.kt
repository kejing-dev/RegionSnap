package io.embodit.regionsnap.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.1.4, user on the headset: "the frames don't face the camera; by default they should, else everything is skewed;
 * after turning it must still go back to facing. Grip + stick left/right, and UI too." (profile D10 補: new objects
 * face the user, turn about the vertical axis, and come back.)
 */
class FacingTest {
    private var t = 0L

    private fun angleToUser(f: SnapFrame, eye: Vec3): Float = abs(Frames.yawOffsetDeg(f, eye))

    /** A controller box drawn from [eye] around [c] (the point it aims at), [planes] or floating. */
    private fun drawBox(s: Snapper, eye: Vec3, c: Vec3, planes: List<PlaneSurface> = emptyList(), src: Source = Source.CONTROLLER): SnapFrame {
        val to = (c - eye).normalized()
        val right = Vec3(-to.z, 0f, to.x).normalized()
        val a = c - right * 0.2f + Vec3(0f, 0.15f, 0f)
        val b = c + right * 0.2f - Vec3(0f, 0.15f, 0f)
        fun f(p: Vec3, pressed: Boolean) = InputFrame(src, eye, (p - eye).normalized(), pressed, eye = eye,
            pinchPoint = if (src == Source.CONTROLLER) null else eye + (p - eye).normalized() * 0.45f)
        t += 16; s.step(f(a, false), planes, t, 0.016f)
        for (i in 0..24) { t += 16; s.step(f(a.lerp(b, i / 24f), true), planes, t, 0.016f) }
        repeat(10) { t += 16; s.step(f(b, true), planes, t, 0.016f) }
        t += 16; s.step(f(b, false), planes, t, 0.016f)
        return s.frame!!
    }

    @Test fun newFramesFaceTheUserFromAllFourSides() {
        val c = Vec3(0f, 1.5f, 0f)
        for (deg in listOf(0f, 90f, 180f, 270f, 33f)) {
            val a = Num.rad(deg)
            val eye = Vec3(1.5f * sin(a), 1.65f, 1.5f * cos(a))
            for (src in listOf(Source.CONTROLLER, Source.EYE)) {
                val f = drawBox(Snapper(), eye, c, src = src)
                assertTrue("$deg $src: ${angleToUser(f, eye)}°", angleToUser(f, eye) < 5f)
                assertEquals("$deg upright", 0f, f.normal.y, 1e-4f)
                assertEquals(0f, f.roll, 0f)
            }
        }
    }

    @Test fun anObliqueWallStillGivesAFacingFrame() {
        // a wall turned 40° from the user: the frame faces the user, not the wall
        val eye = Vec3(0f, 1.6f, 0f)
        val a = Num.rad(40f)
        val n = Vec3(sin(a), 0f, cos(a))
        val along = Vec3(cos(a), 0f, -sin(a))
        val o = Vec3(0f, 0f, -1.6f)
        val wall = PlaneSurface("w", "WALL", listOf(o - along * 2f, o + along * 2f, o + along * 2f + Vec3.UP * 3f, o - along * 2f + Vec3.UP * 3f))
        assertTrue(abs(n.dot(Vec3(0f, 0f, 1f))) < 0.8f)
        val f = drawBox(Snapper(), eye, Vec3(0f, 1.5f, -1.6f), listOf(wall))
        assertTrue("${angleToUser(f, eye)}°", angleToUser(f, eye) < 5f)
        assertEquals(Surface.WALL, f.surface)
    }

    @Test fun aTableFrameStandsUpAndFacesTheUser() {
        val eye = Vec3(0f, 1.6f, 0f)
        val table = PlaneSurface("t", "TABLE", listOf(Vec3(-1f, 0.75f, -0.3f), Vec3(1f, 0.75f, -0.3f), Vec3(1f, 0.75f, -1.3f), Vec3(-1f, 0.75f, -1.3f)))
        val f = drawBox(Snapper(), eye, Vec3(0f, 0.75f, -0.8f), listOf(table))
        assertEquals(0f, f.normal.y, 1e-4f)
        assertTrue(angleToUser(f, eye) < 5f)
    }

    @Test fun slantedLineStrokeStaysLevel() {
        val s = Snapper()
        val eye = Vec3(0f, 1.6f, 0f)
        val a = Vec3(-0.25f, 1.75f, -1.4f)
        val b = Vec3(0.25f, 1.45f, -1.4f)
        t += 16; s.step(InputFrame(Source.CONTROLLER, eye, (a - eye).normalized(), false, eye = eye), emptyList(), t, 0.016f)
        for (i in 0..24) { t += 16; s.step(InputFrame(Source.CONTROLLER, eye, (a.lerp(b, i / 24f) - eye).normalized(), true, eye = eye), emptyList(), t, 0.016f) }
        t += 16; s.step(InputFrame(Source.CONTROLLER, eye, (b - eye).normalized(), false, eye = eye), emptyList(), t, 0.016f)
        assertEquals(0f, s.frame!!.roll, 0f)
        assertEquals(0f, Frames.levelAngleDeg(s.frame!!), 0.5f)
    }

    private fun stick(s: Snapper, eye: Vec3, x: Float, grip: Boolean, ms: Int): List<SnapEvent> {
        val ev = ArrayList<SnapEvent>()
        val f = s.frame!!
        repeat(ms / 16) {
            t += 16
            ev += s.step(InputFrame(Source.CONTROLLER, eye, (f.center - eye).normalized() + Vec3(0f, 1f, 0f), false, stickX = x, eye = eye, grip = grip), emptyList(), t, 1f / 60f)
        }
        return ev
    }

    @Test fun gripAndStickTurnAboutTheVerticalAxisAndCatchOnFacing() {
        val eye = Vec3(0f, 1.6f, 0f)
        val s = Snapper()
        drawBox(s, eye, Vec3(0f, 1.5f, -1.5f))
        // stick alone does nothing
        stick(s, eye, 1f, false, 500)
        assertTrue(angleToUser(s.frame!!, eye) < 0.5f)
        // grip + full right for 1 s: about 60° clockwise (seen from above = negative), still upright
        stick(s, eye, 1f, true, 1008)
        val off = Frames.yawOffsetDeg(s.frame!!, eye)
        assertEquals(-60f, off, 4f)
        assertEquals(0f, s.frame!!.normal.y, 1e-4f)
        assertEquals(Vec3.UP, s.frame!!.baseUp)
        // back the other way: it catches on facing (Leveled) and holds there briefly
        val ev = stick(s, eye, -1f, true, 1100)
        assertTrue(ev.contains(SnapEvent.Leveled))
        assertTrue("${Frames.yawOffsetDeg(s.frame!!, eye)}", angleToUser(s.frame!!, eye) < 2f)
        // keep pushing: past the hold it goes on to the other side
        stick(s, eye, -1f, true, 800)
        assertTrue(Frames.yawOffsetDeg(s.frame!!, eye) > 10f)
    }

    @Test fun uiStepsAreFifteenDegreesAndStopOnFacing() {
        val eye = Vec3(0f, 1.6f, 0f)
        val s = Snapper()
        drawBox(s, eye, Vec3(0f, 1.5f, -1.5f))
        assertFalse(s.yawStep(1, eye))
        assertEquals(15f, Frames.yawOffsetDeg(s.frame!!, eye), 0.5f)
        s.yawStep(1, eye)
        assertEquals(30f, Frames.yawOffsetDeg(s.frame!!, eye), 0.5f)
        s.yawStep(-1, eye)
        assertTrue("lands on facing", s.yawStep(-1, eye))
        assertTrue(angleToUser(s.frame!!, eye) < 0.5f)
        s.yawStep(-1, eye)
        assertEquals(-15f, Frames.yawOffsetDeg(s.frame!!, eye), 0.5f)
        // from an odd angle a step that passes facing stops there (no dead zone, D29)
        s.setFrame(Frames.yaw(s.frame!!, 25f)) // now +10°
        assertTrue(s.yawStep(-1, eye))
        assertTrue(angleToUser(s.frame!!, eye) < 0.5f)
    }

    @Test fun faceMeAfterAnyTurnIsWithinTwoDegrees() {
        for (start in listOf(0f, 90f, 200f, 300f)) {
            val a = Num.rad(start)
            val eye0 = Vec3(1.5f * sin(a), 1.65f, 1.5f * cos(a))
            val s = Snapper()
            drawBox(s, eye0, Vec3(0f, 1.5f, 0f))
            for (turn in listOf(12f, -37f, 95f, 178f, -150f)) {
                s.setFrame(Frames.yaw(s.frame!!, turn))
                // the user also walked a bit: face-me turns to where they are now
                val eye = eye0 + Vec3(0.3f, 0f, -0.2f)
                assertTrue(s.faceUser(eye))
                assertTrue("$start/$turn: ${angleToUser(s.frame!!, eye)}", angleToUser(s.frame!!, eye) < 2f)
                assertEquals(0f, s.frame!!.normal.y, 1e-4f)
            }
        }
    }

    @Test fun twelveDegreesOffBothDirectionsComeBack() {
        // D29: 12° off, one press each way lands within 2° of facing
        val eye = Vec3(0f, 1.6f, 0f)
        for (sign in listOf(1f, -1f)) {
            val s = Snapper()
            drawBox(s, eye, Vec3(0f, 1.5f, -1.5f))
            s.setFrame(Frames.yaw(s.frame!!, 12f * sign))
            s.yawStep(if (sign > 0) -1 else 1, eye)
            assertTrue(angleToUser(s.frame!!, eye) < 2f)
        }
    }
}
