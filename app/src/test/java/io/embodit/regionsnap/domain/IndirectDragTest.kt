package io.embodit.regionsnap.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.3, user on the headset: "eye + hand should pinch and pull the frame with the hand, not pinch and steer with the
 * head". The gaze only picks where the pinch grabs; while it is held only the hand moves the point (gain = target
 * distance ÷ hand distance, 1-6x). Turning the head or moving the gaze must change nothing.
 */
class IndirectDragTest {
    private val eye = Vec3(0f, 1.6f, 0f)
    private val wall = PlaneSurface("w", "WALL", listOf(Vec3(-2f, 0f, -1.5f), Vec3(2f, 0f, -1.5f), Vec3(2f, 3f, -1.5f), Vec3(-2f, 3f, -1.5f)))
    private val planes = listOf(wall)

    /** The hand at rest: 0.45 m out, 0.2 m below the eyes (0.49 m from them). */
    private val hand0 = Vec3(0f, 1.4f, -0.45f)
    private val gain = (1.5f / hand0.distanceTo(eye)).coerceIn(1f, 6f)

    private fun dirTo(p: Vec3, from: Vec3 = eye) = (p - from).normalized()

    private var t = 0L

    private fun step(s: Snapper, gazeAt: Vec3, pressed: Boolean, hand: Vec3?, src: Source = Source.EYE, head: Vec3 = eye, canDrag: Boolean = true): List<SnapEvent> {
        t += 16
        return s.step(InputFrame(src, head, dirTo(gazeAt, head), pressed, pinchPoint = hand, eye = head, canDrag = canDrag), planes, t, 0.016f)
    }

    /** Pinch with the gaze at [start], move the hand by [move] over 20 frames, then release. */
    private fun draw(s: Snapper, start: Vec3, move: Vec3, gaze: (Int) -> Vec3 = { start }, head: (Int) -> Vec3 = { eye }): List<SnapEvent> {
        val ev = ArrayList<SnapEvent>()
        ev += step(s, start, false, hand0)
        ev += step(s, start, true, hand0)
        for (i in 1..20) ev += step(s, gaze(i), true, hand0 + move * (i / 20f), head = head(i))
        // the one-euro filter settles on the last hand position
        for (i in 1..30) ev += step(s, gaze(20), true, hand0 + move, head = head(20))
        ev += step(s, gaze(20), false, hand0 + move, head = head(20))
        return ev
    }

    @Test fun handTenCentimetresDrawsGainTimesThat() {
        val s = Snapper()
        val ev = draw(s, Vec3(-0.1f, 1.7f, -1.5f), Vec3(0.10f, -0.08f, 0f))
        assertTrue(ev.any { it is SnapEvent.Framed })
        val f = s.frame!!
        assertEquals(0.10f * gain, f.w, 0.02f)
        assertEquals(0.08f * gain, f.h, 0.02f)
        // starts where the gaze was at the pinch, stays on the wall
        assertEquals(-1.5f, f.center.z, 0.02f) // faces the user, so up to 1-2 cm off a straight-on wall (v0.1.4)
        assertEquals(-0.1f + 0.05f * gain, f.center.x, 0.02f)
    }

    @Test fun turningTheHeadOrGazeWhileDrawingChangesNothing() {
        val still = Snapper()
        draw(still, Vec3(-0.1f, 1.7f, -1.5f), Vec3(0.10f, -0.08f, 0f))
        val turned = Snapper()
        // the gaze sweeps 1 m to the right and up, the head turns and moves 10 cm: the hand alone decides
        draw(
            turned, Vec3(-0.1f, 1.7f, -1.5f), Vec3(0.10f, -0.08f, 0f),
            gaze = { i -> Vec3(-0.1f + i * 0.05f, 1.7f + i * 0.02f, -1.5f) },
            head = { i -> eye + Vec3(i * 0.005f, 0f, 0f) },
        )
        val a = still.frame!!
        val b = turned.frame!!
        assertEquals(a.w, b.w, 0.005f)
        assertEquals(a.h, b.h, 0.005f)
        assertEquals(0f, a.center.distanceTo(b.center), 0.005f)
    }

    @Test fun onlyTheHeadMovesNothingIsDrawnAndTheFrameStays() {
        val s = Snapper()
        draw(s, Vec3(-0.1f, 1.7f, -1.5f), Vec3(0.10f, -0.08f, 0f))
        val before = s.frame!!
        // pinch on empty wall, keep the hand still, swing the gaze 1 m: no stroke, the release shoots, the frame stays
        val far = Vec3(0.9f, 1.2f, -1.5f)
        val ev = ArrayList<SnapEvent>()
        ev += step(s, far, false, hand0)
        ev += step(s, far, true, hand0)
        for (i in 1..20) ev += step(s, far + Vec3(-i * 0.05f, i * 0.03f, 0f), true, hand0)
        ev += step(s, far, false, hand0)
        assertFalse(ev.any { it == SnapEvent.DrawStart })
        assertTrue(ev.contains(SnapEvent.Shoot))
        assertEquals(before, s.frame)
    }

    @Test fun handleFollowsTheHandNotTheGaze() {
        val s = Snapper()
        draw(s, Vec3(-0.1f, 1.7f, -1.5f), Vec3(0.10f, -0.08f, 0f))
        val f = s.frame!!
        val corner = f.handles()[2] // bottom right
        // grab it with the gaze, then look away while the hand moves 5 cm right
        step(s, corner, false, hand0)
        val grab = step(s, corner, true, hand0)
        assertTrue(grab.any { it is SnapEvent.Grab })
        for (i in 1..20) step(s, corner + Vec3(-0.6f, 0.4f, 0f), true, hand0 + Vec3(0.05f * i / 20f, 0f, 0f))
        for (i in 1..30) step(s, corner + Vec3(-0.6f, 0.4f, 0f), true, hand0 + Vec3(0.05f, 0f, 0f))
        step(s, corner, false, hand0 + Vec3(0.05f, 0f, 0f))
        val g = s.frame!!
        assertEquals(f.w + 0.05f * gain, g.w, 0.02f)
        assertEquals(f.h, g.h, 0.01f)
    }

    @Test fun pinFollowsTheHand() {
        val s = Snapper()
        val pin = SnapFrame(Vec3(0.5f, 1.5f, -1.49f), Vec3(0f, 0f, 1f), Vec3.UP, 0f, 0.3f, 0.2f, Surface.WALL)
        s.pins = listOf(pin)
        val ev = ArrayList<SnapEvent>()
        ev += step(s, pin.center, false, hand0)
        ev += step(s, pin.center, true, hand0)
        for (i in 1..20) ev += step(s, pin.center + Vec3(0f, -0.5f, 0f), true, hand0 + Vec3(0f, 0.06f * i / 20f, 0f))
        for (i in 1..30) ev += step(s, pin.center + Vec3(0f, -0.5f, 0f), true, hand0 + Vec3(0f, 0.06f, 0f))
        ev += step(s, pin.center, false, hand0 + Vec3(0f, 0.06f, 0f))
        val drop = ev.filterIsInstance<SnapEvent.PinDropped>().single()
        // up by 6 cm × gain (the gaze went down: it does not matter); no jump at the start
        assertEquals(pin.center.y + 0.06f * gain, drop.frame.center.y, 0.02f)
        assertEquals(pin.center.x, drop.frame.center.x, 0.01f)
    }

    @Test fun headFallbackAimsButNeverDrags() {
        val s = Snapper()
        val ev = ArrayList<SnapEvent>()
        val at = Vec3(-0.1f, 1.7f, -1.5f)
        ev += step(s, at, false, hand0, canDrag = false)
        ev += step(s, at, true, hand0, canDrag = false)
        for (i in 1..20) ev += step(s, at + Vec3(i * 0.02f, -i * 0.02f, 0f), true, hand0 + Vec3(i * 0.01f, -i * 0.01f, 0f), canDrag = false)
        ev += step(s, at, false, hand0, canDrag = false)
        assertFalse(ev.any { it == SnapEvent.DrawStart || it is SnapEvent.Framed })
        assertNull(s.frame)
    }

    @Test fun noHandNoDrag() {
        // eye tracking but no hand data: the gaze alone cannot pull a frame
        val s = Snapper()
        val at = Vec3(-0.1f, 1.7f, -1.5f)
        step(s, at, false, null)
        step(s, at, true, null)
        for (i in 1..20) step(s, at + Vec3(i * 0.02f, -i * 0.02f, 0f), true, null)
        step(s, at, false, null)
        assertNull(s.frame)
    }

    @Test fun gainIsTargetOverHandDistanceClamped() {
        val s = Snapper()
        step(s, Vec3(0f, 1.6f, -1.5f), false, hand0)
        step(s, Vec3(0f, 1.6f, -1.5f), true, hand0)
        assertEquals(gain, s.dragGain, 0.01f)
        step(s, Vec3(0f, 1.6f, -1.5f), false, hand0)
        // hand almost at the eyes: clamped at 6x
        step(s, Vec3(0f, 1.6f, -1.5f), false, eye + Vec3(0f, -0.06f, -0.03f))
        step(s, Vec3(0f, 1.6f, -1.5f), true, eye + Vec3(0f, -0.06f, -0.03f))
        assertEquals(6f, s.dragGain, 0.001f)
    }

    @Test fun controllerKeepsItsDirectRay() {
        val s = Snapper()
        val a = Vec3(-0.2f, 1.7f, -1.5f)
        val b = Vec3(0.22f, 1.4f, -1.5f)
        step(s, a, false, null, Source.CONTROLLER)
        step(s, a, true, null, Source.CONTROLLER)
        for (i in 1..24) step(s, a.lerp(b, i / 24f), true, null, Source.CONTROLLER)
        step(s, b, false, null, Source.CONTROLLER)
        val f = s.frame
        assertNotNull(f)
        assertEquals(0.42f, f!!.w, 0.015f)
        assertEquals(0.30f, f.h, 0.01f)
    }
}
