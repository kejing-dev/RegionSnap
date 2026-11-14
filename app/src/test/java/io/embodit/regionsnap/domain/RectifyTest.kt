package io.embodit.regionsnap.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Four-point rectification, projection and calibrate-once (design interactions T8 / T14, rules). */
class RectifyTest {
    private val k = Intrinsics.assumed(1280, 960, 90f)

    @Test fun homographyMapsTheQuadOntoTheRectWithinOnePixel() {
        val q = listOf(Px(312f, 205f), Px(905f, 260f), Px(870f, 700f), Px(290f, 640f))
        val (w, h) = Rectify.outputSize(q, 0.42f / 0.30f)
        val r = Rectify.rect(w, h)
        val hm = Rectify.homography(q, r)!!
        for (i in 0 until 4) assertTrue("corner $i", Rectify.apply(hm, q[i]).dist(r[i]) < 1f)
        // output keeps the frame's aspect, long side = longest quad side, no upscale
        assertEquals(0.42f / 0.30f, w.toFloat() / h, 0.01f)
        assertTrue(w <= 2048)
    }

    @Test fun outputNeverUpscalesPastTheSourceOrTheCap() {
        val q = listOf(Px(0f, 0f), Px(4000f, 0f), Px(4000f, 3000f), Px(0f, 3000f))
        assertEquals(2048, Rectify.outputSize(q, 4f / 3f).first)
        val small = listOf(Px(0f, 0f), Px(300f, 0f), Px(300f, 200f), Px(0f, 200f))
        assertEquals(300, Rectify.outputSize(small, 1.5f).first)
    }

    @Test fun projectionOfAFrameAheadIsCentred() {
        val f = SnapFrame(Vec3(0.032f, 1.6f, -1f), Vec3(0f, 0f, 1f), Vec3.UP, 0f, 0.4f, 0.3f, Surface.WALL)
        val hmd = Rigid(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
        val cam = hmd * Rectify.DEFAULT_TCAM
        val q = Rectify.quad(f, cam, k)!!
        // centred on the right camera, TL above-left of the image centre, square pixels: 0.4 m at 1 m = 0.4 f wide
        assertEquals(640f - 0.2f * k.fx, q[0].x, 0.5f)
        assertEquals(480f - 0.15f * k.fy, q[0].y, 0.5f)
        assertEquals(0.4f * k.fx, q[1].x - q[0].x, 0.5f)
        val pad = Rectify.previewRegion(q, 1280, 960)
        assertTrue(pad[0] < q[0].x && pad[2] > q[1].x)
    }

    @Test fun calibrateRecoversAKnownCameraOffset() {
        // the real camera sits 5 cm right, 2 cm down and is turned 3° about Y relative to the HMD
        val truth = Rigid(Vec3(0.05f, -0.02f, 0.01f), Quat4.axisAngle(Vec3.UP, Num.rad(3f)))
        val hmd = Rigid(Vec3(0.1f, 1.55f, 0.2f), Quat4.axisAngle(Vec3.UP, Num.rad(-20f)))
        val camTrue = hmd * truth
        val f = SnapFrame(Vec3(-0.2f, 1.3f, -0.9f), Vec3(0.3f, 0.1f, 1f).normalized(), Vec3.UP, Num.rad(4f), 0.297f, 0.21f, Surface.WALL)
        val q = Rectify.quad(f, camTrue, k)!!
        val c = Rectify.calibrate(f, q, k, hmd)
        assertNotNull(c)
        c!!
        assertTrue("err ${c.rmsPx}", c.rmsPx < 0.5f)
        assertEquals(0f, c.tcam.p.distanceTo(truth.p), 0.002f)
        // rotation recovered within half a degree
        val d = c.tcam.q.rotate(Vec3.AHEAD)
        assertTrue(Num.angleDeg(d, truth.q.rotate(Vec3.AHEAD)) < 0.5f)
        // re-projecting with the calibration matches the quad
        val q2 = Rectify.quad(f, hmd * c.tcam, k)!!
        for (i in 0 until 4) assertTrue(q2[i].dist(q[i]) < 1f)
    }

    @Test fun degradedKeepsTheRotationOnly() {
        val c = Calibration(Rigid(Vec3(0.2f, 0f, 0f), Quat4.axisAngle(Vec3.UP, 0.1f)), 5f, 1f, false).degrade()
        assertTrue(c.degraded)
        assertEquals(Rectify.DEFAULT_TCAM.p, c.tcam.p)
    }

    @Test fun sharpestFrameWins() {
        val w = 32; val h = 32
        val sharp = IntArray(w * h) { if ((it % w) % 2 == 0) 255 else 0 }
        val soft = IntArray(w * h) { 128 + (it % w) }
        assertTrue(Rectify.sharpness(sharp, w, h) > Rectify.sharpness(soft, w, h))
    }
}
