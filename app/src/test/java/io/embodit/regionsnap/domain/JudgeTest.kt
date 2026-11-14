package io.embodit.regionsnap.domain

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stroke and press judgement (design input.pressPriority.unitTest, rules: 16 + 3 cases, 3 small / 3 line / 3 loop, ≥ 10 sloppy). */
class JudgeTest {
    private fun line(a: P2, b: P2, n: Int = 20, wobble: Float = 0f): List<P2> =
        (0..n).map { i ->
            val t = i.toFloat() / n
            val off = if (wobble == 0f) 0f else wobble * sin(t * 9.3f)
            P2(a.x + (b.x - a.x) * t + off, a.y + (b.y - a.y) * t - off)
        }

    private fun loop(cx: Float, cy: Float, rx: Float, ry: Float, n: Int = 40, close: Float = 1f): List<P2> =
        (0..(n * close).toInt()).map { i ->
            val a = 2 * Math.PI * i / n
            P2(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
        }

    @Test fun boxFromDiagonal() {
        val r = StrokeJudge.judge(line(P2(0f, 0f), P2(0.42f, 0.30f)))
        assertEquals(StrokeJudge.Kind.BOX, r.kind)
        assertEquals(0.42f, r.w, 1e-3f)
        assertEquals(0.30f, r.h, 1e-3f)
    }

    @Test fun threeSmall() {
        for (end in listOf(P2(0.02f, 0.03f), P2(0.035f, 0.01f), P2(0.01f, 0.05f))) {
            assertEquals("small $end", StrokeJudge.Kind.TOO_SMALL, StrokeJudge.judge(line(P2(0f, 0f), end)).kind)
        }
    }

    @Test fun threeLinesBecomeCentreLineRects() {
        for ((end, long) in listOf(P2(0.30f, 0.01f) to 0.3002f, P2(0.6f, -0.02f) to 0.6003f, P2(0.12f, 0.0f) to 0.12f)) {
            val r = StrokeJudge.judge(line(P2(0f, 0f), end))
            assertEquals(StrokeJudge.Kind.LINE, r.kind)
            assertEquals(long, r.w, 2e-3f)
            assertEquals(maxOf(0.06f, 0.2f * long), r.h, 2e-3f)
            assertEquals(end.x / 2f, r.cx, 1e-3f)
        }
    }

    @Test fun threeLoopsBecomeBoundingRects() {
        for ((rx, ry) in listOf(0.2f to 0.1f, 0.08f to 0.08f, 0.3f to 0.25f)) {
            val r = StrokeJudge.judge(loop(0.5f, 0.2f, rx, ry))
            assertEquals(StrokeJudge.Kind.LOOP, r.kind)
            assertEquals(2 * rx, r.w, 0.01f)
            assertEquals(2 * ry, r.h, 0.01f)
            assertEquals(0.5f, r.cx, 0.01f)
        }
    }

    /** D27 sloppy input: wobbly diagonals, loops that do not close, tilted lines, slow / jittery strokes. */
    @Test fun tenSloppyStrokes() {
        val cases = listOf(
            line(P2(0f, 0f), P2(0.4f, 0.3f), wobble = 0.01f) to StrokeJudge.Kind.BOX,
            line(P2(0f, 0f), P2(-0.3f, 0.2f), wobble = 0.015f) to StrokeJudge.Kind.BOX,
            line(P2(0.1f, 0.1f), P2(0.5f, -0.2f), n = 80, wobble = 0.008f) to StrokeJudge.Kind.BOX,
            loop(0f, 0f, 0.15f, 0.1f, close = 0.85f) to StrokeJudge.Kind.LOOP,
            loop(0f, 0f, 0.2f, 0.12f, close = 1.15f) to StrokeJudge.Kind.LOOP,
            loop(0f, 0f, 0.1f, 0.25f, close = 0.75f) to StrokeJudge.Kind.LOOP,
            line(P2(0f, 0f), P2(0.25f, 0.035f), wobble = 0.002f) to StrokeJudge.Kind.LINE,
            line(P2(0f, 0f), P2(0.4f, -0.03f), n = 60, wobble = 0.003f) to StrokeJudge.Kind.LINE,
            line(P2(0f, 0f), P2(0.06f, 0.05f), wobble = 0.004f) to StrokeJudge.Kind.BOX,
            line(P2(0f, 0f), P2(0.03f, 0.02f), wobble = 0.002f) to StrokeJudge.Kind.TOO_SMALL,
        )
        cases.forEachIndexed { i, (pts, want) -> assertEquals("sloppy #$i", want, StrokeJudge.judge(pts).kind) }
    }

    // ------------------------------------------------------------------------------------------ press priority

    private val o = Vec3(0f, 1.5f, 0f)
    private val ahead = Vec3(0f, 0f, -1f)
    private fun dirDeg(deg: Float) = Vec3(sin(Num.rad(deg)), 0f, -cos(Num.rad(deg)))

    /** One press on [target], moving the aim by [deg] (and the point by the same arc at 1 m), released; with / without a frame. */
    private fun press(src: Source, target: PressJudge.Target, deg: Float, hasFrame: Boolean): Pair<PressJudge.Phase, PressJudge.Outcome> {
        val j = PressJudge()
        j.down(src, target, o + ahead, ahead, handleIndex = 2, pinIndex = 0)
        var peak = j.phase
        val steps = 6
        for (i in 1..steps) {
            val d = dirDeg(deg * i / steps)
            j.move(o + d, d)
            if (j.phase == PressJudge.Phase.DRAW) peak = j.phase
        }
        return peak to j.up(hasFrame)
    }

    @Test fun sixteenPressCases() {
        // four places × still / moved × controller / eye
        for (src in listOf(Source.CONTROLLER, Source.EYE)) {
            val moveDeg = if (src == Source.CONTROLLER) 4f else 6f
            // ① UI: the system click owns it, still or moved
            assertEquals(PressJudge.Outcome.None, press(src, PressJudge.Target.UI, 0f, true).second)
            assertEquals(PressJudge.Outcome.None, press(src, PressJudge.Target.UI, moveDeg, true).second)
            // ② handle: adjust, still or moved
            assertEquals(PressJudge.Outcome.EndAdjust, press(src, PressJudge.Target.HANDLE, 0f, true).second)
            assertEquals(PressJudge.Outcome.EndAdjust, press(src, PressJudge.Target.HANDLE, moveDeg, true).second)
            // ③ elsewhere with a frame: still = shoot, moved = draw
            assertEquals(PressJudge.Outcome.Shoot, press(src, PressJudge.Target.ELSEWHERE, 0f, true).second)
            val moved = press(src, PressJudge.Target.ELSEWHERE, moveDeg, true)
            assertEquals(PressJudge.Phase.DRAW, moved.first)
            assertEquals(PressJudge.Outcome.EndDraw, moved.second)
            // ③ elsewhere without a frame: still = nothing at all, moved = draw
            assertEquals(PressJudge.Outcome.None, press(src, PressJudge.Target.ELSEWHERE, 0f, false).second)
            assertEquals(PressJudge.Outcome.EndDraw, press(src, PressJudge.Target.ELSEWHERE, moveDeg, false).second)
        }
    }

    @Test fun pinchWithJitterAndASaccadeStillShoots() {
        // three cases: a 1.5° tremble, a tremble + one 2.5° saccade, the saccade back: all under the 3° eye threshold
        val seqs = listOf(listOf(1.5f, -1.5f, 1.2f, -0.8f), listOf(1.5f, 2.5f, -1f, 0.5f), listOf(-1.5f, -2.8f, 0f, 1.5f))
        for (seq in seqs) {
            val j = PressJudge()
            j.down(Source.EYE, PressJudge.Target.ELSEWHERE, o + ahead, ahead)
            for (deg in seq) dirDeg(deg).let { j.move(o + it, it) }
            assertEquals("seq $seq", PressJudge.Outcome.Shoot, j.up(true))
        }
    }

    @Test fun controllerTremorUnderTwoCmShoots() {
        val j = PressJudge()
        j.down(Source.CONTROLLER, PressJudge.Target.ELSEWHERE, o + ahead, ahead)
        val d = dirDeg(0.6f)
        j.move(o + ahead + Vec3(0.012f, 0f, 0f), d)
        assertEquals(PressJudge.Outcome.Shoot, j.up(true))
    }

    @Test fun pinPressStillIsAClickMovedIsADrag() {
        assertEquals(PressJudge.Outcome.PinClick(0), press(Source.EYE, PressJudge.Target.PIN, 0f, false).second)
        assertEquals(PressJudge.Outcome.EndPinDrag, press(Source.CONTROLLER, PressJudge.Target.PIN, 5f, false).second)
    }

    @Test fun pinchHysteresisNeedsTwoFrames() {
        val p = PinchDetector()
        assertEquals(0, p.step(0.05f))
        assertEquals(0, p.step(0.015f))
        assertEquals(1, p.step(0.015f))
        assertTrue(p.pinched)
        // 2.5 cm sits in the hysteresis band: stays pinched
        assertEquals(0, p.step(0.025f))
        assertEquals(0, p.step(0.025f))
        assertEquals(0, p.step(0.035f))
        assertEquals(-1, p.step(0.035f))
        // a lost hand releases at once
        p.step(0.01f); p.step(0.01f)
        assertEquals(-1, p.step(null))
    }

    @Test fun activeSourceSwitchesOnlyOnActions() {
        val a = InputArbiter()
        a.force(Source.EYE)
        assertTrue(!a.controllerPose(Vec3(0f, 1f, 0f), 0))
        assertTrue(!a.controllerPose(Vec3(0.01f, 1f, 0f), 10))
        assertEquals(Source.EYE, a.active)
        assertTrue(a.controllerPose(Vec3(0.04f, 1f, 0f), 20))
        assertEquals(Source.CONTROLLER, a.active)
        a.action(Source.EYE, 30)
        assertEquals(Source.EYE, a.active)
    }
}
