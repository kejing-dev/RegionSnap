package io.embodit.regionsnap.debug

import android.os.Handler
import android.os.Looper
import io.embodit.regionsnap.domain.PlaneSurface
import io.embodit.regionsnap.domain.Px
import io.embodit.regionsnap.domain.Snapper
import io.embodit.regionsnap.domain.Source
import io.embodit.regionsnap.domain.Vec3
import io.embodit.regionsnap.platform.RsLog
import io.embodit.regionsnap.platform.capture.SampleFrame
import io.embodit.regionsnap.ui.InputMode
import io.embodit.regionsnap.ui.Lang
import io.embodit.regionsnap.ui.RsController
import io.embodit.regionsnap.ui.Tab
import io.embodit.regionsnap.ui.Ui
import io.embodit.regionsnap.ui.components.TextFit
import io.embodit.regionsnap.ui.debug.Probe
import io.embodit.regionsnap.ui.scene.RsScene
import io.embodit.regionsnap.ui.window.PANEL_ID
import io.embodit.regionsnap.ui.window.PANEL_TAG
import io.embodit.regionsnap.ui.window.WindowCtl

/**
 * The QA hooks (design verification.debugHooks). Every input command feeds the scene's synthetic stand-in, which goes
 * through the very same InputFrame → Snapper → controller path as the controller and the eye + hand (factory rule: debug
 * commands take the user's path); panel commands send the same [Ui] events as the keys. Main thread.
 */
object DebugCommands {
    private val main = Handler(Looper.getMainLooper())

    private fun ev(e: Ui) = RsController.onEvent(e)

    private fun state() = RsController.state.value

    private fun scene(): RsScene? = Probe.host?.scene()

    private fun log(msg: String) = RsLog.i("CMD $msg state=${state().designState}")

    private fun later(ms: Long, f: () -> Unit) = main.postDelayed({ runCatching(f).onFailure { RsLog.w("CMD later failed: $it") } }, ms)

    private fun f(s: String?): Float? = s?.toFloatOrNull()

    private fun vec(s: String?): Vec3? = s?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }

    /** The source the stand-in plays (debug `input`); defaults to the eye + hand. */
    private var playAs: Source = Source.EYE

    private fun sc(): RsScene = scene() ?: error("no scene")

    /** Queue: aim at each point (one per engine frame), pressed or not. */
    private fun play(points: List<Vec3>, pressedFrom: Int, pressedTo: Int, release: Boolean = true) {
        val s = sc()
        s.syntheticSource = playAs
        val q = s.synthetic.queue
        points.forEachIndexed { i, p -> q.addLast(s.rayTo(p) to (i in pressedFrom..pressedTo)) }
        if (release) q.addLast(s.rayTo(points.last()) to false)
    }

    private fun line(a: Vec3, b: Vec3, n: Int) = (0..n).map { a.lerp(b, it.toFloat() / n) }

    /** A box of [w] x [h] m centred [d] m ahead at eye height (on the stand-in wall when there is one). */
    private fun box(w: Float, h: Float, d: Float, hold: Boolean = false, right: Float = 0f) {
        val s = sc()
        val a = s.ahead(d, right - w / 2f, h / 2f - 0.1f)
        val b = s.ahead(d, right + w / 2f, -h / 2f - 0.1f)
        val pts = listOf(a, a) + line(a, b, 12)
        play(pts, 1, pts.size - 1, release = !hold)
    }

    private fun loop(r: Float, d: Float) {
        val s = sc()
        val c = s.ahead(d, 0f, -0.1f)
        val n = (s.headPose.position - c).let { Vec3(it.x, 0f, it.z).normalized() }
        val pts = Snapper.circle(c, n, r, 24)
        play(listOf(pts[0]) + pts, 1, pts.size)
    }

    private fun shootWith(sample: String?) {
        RsController.camera?.sample = sample?.let { id -> SampleFrame.entries.firstOrNull { it.id == id } }
        ev(Ui.Shoot)
    }

    private fun ensureFrame(then: () -> Unit) {
        ev(Ui.SelectTab(Tab.SNAP))
        if (RsController.snapper.frame == null) {
            box(0.42f, 0.30f, 1.4f, right = 0.62f)
            later(4000) { then() }
        } else then()
    }

    fun run(cmd: String, a: List<String>) {
        val s = scene()
        when (cmd) {
            "goto" -> goto(a.getOrNull(0)?.uppercase() ?: "S02")
            "input" -> {
                playAs = when (a.getOrNull(0)) { "controller" -> Source.CONTROLLER; "finger" -> Source.FINGER; "head" -> Source.HEAD; else -> Source.EYE }
                s?.syntheticSource = playAs
                s?.debugMode = when (a.getOrNull(0)) { "finger" -> InputMode.FINGER; "head" -> InputMode.HEAD; "eyenohand" -> InputMode.EYE_NO_HAND; else -> null }
                if (a.getOrNull(0) == "off") { s?.syntheticSource = null; s?.debugMode = null }
            }
            // ray x,y,z,dx,dy,dz (Stage metres) or `ray ahead d [right up]`
            "ray" -> {
                val sc = sc()
                sc.syntheticSource = playAs
                if (a.getOrNull(0) == "ahead") sc.synthetic.queue.addLast(sc.rayTo(sc.ahead(f(a.getOrNull(1)) ?: 1.2f, f(a.getOrNull(2)) ?: 0f, f(a.getOrNull(3)) ?: 0f)) to sc.synthetic.pressed)
                else {
                    val v = a.getOrNull(0)?.split(',')?.mapNotNull { it.toFloatOrNull() }
                    if (v != null && v.size == 6) sc.synthetic.queue.addLast(io.embodit.regionsnap.platform.input.Ray(Vec3(v[0], v[1], v[2]), Vec3(v[3], v[4], v[5]).normalized()) to sc.synthetic.pressed)
                }
            }
            // hand R U: the stand-in's pinching hand 0.45 m ahead, R right / 0.2 + U below the eyes (head-yaw frame);
            // hand off: it rides along the stand-in ray again (eye + hand indirect drag, v0.1.3)
            "hand" -> sc().let { sc ->
                sc.synthetic.hand = if (a.getOrNull(0) == "off") null else sc.ahead(0.45f, f(a.getOrNull(0)) ?: 0f, -0.2f + (f(a.getOrNull(1)) ?: 0f))
                log("hand ${sc.synthetic.hand}")
            }
            "press" -> sc().let { it.syntheticSource = playAs; it.synthetic.queue.addLast(it.synthetic.ray to true) }
            "release" -> sc().let { it.synthetic.queue.addLast(it.synthetic.ray to false) }
            // drag x1,y1,z1 x2,y2,z2 (Stage points): press at the first, move to the second, release
            "drag" -> {
                val p1 = vec(a.getOrNull(0)) ?: return
                val p2 = vec(a.getOrNull(1)) ?: return
                val pts = listOf(p1, p1) + line(p1, p2, 12)
                play(pts, 1, pts.size - 1, release = a.getOrNull(2) != "hold")
            }
            "box" -> box(f(a.getOrNull(0)) ?: 0.42f, f(a.getOrNull(1)) ?: 0.3f, f(a.getOrNull(2)) ?: 1.2f, a.getOrNull(3) == "hold", f(a.getOrNull(4)) ?: 0f)
            "loop" -> loop(f(a.getOrNull(0)) ?: 0.15f, f(a.getOrNull(1)) ?: 1.2f)
            // a stroke: `line` (划线 rule)
            "line" -> {
                val c = sc().ahead(f(a.getOrNull(0)) ?: 1.2f, 0f, -0.1f)
                val p1 = c + Vec3(-0.2f, 0f, 0f)
                val p2 = c + Vec3(0.2f, 0.01f, 0f)
                val pts = listOf(p1, p1) + line(p1, p2, 12)
                play(pts, 1, pts.size - 1)
            }
            // press and release on a handle (0-8) without moving (adjust → nothing) or `handle i dx dy` to drag it
            "handle" -> {
                val fr = RsController.snapper.frame ?: return log("handle: no frame")
                val i = a.getOrNull(0)?.toIntOrNull() ?: 2
                val p = fr.handles()[i]
                val to = p + fr.right * (f(a.getOrNull(1)) ?: 0f) + fr.up * (f(a.getOrNull(2)) ?: 0f)
                val pts = listOf(p, p) + line(p, to, 8)
                play(pts, 1, pts.size - 1, release = a.getOrNull(3) != "hold")
            }
            // tap: press and release in place where the stand-in aims (shoot when a frame is up, pressPriority ③)
            "tap" -> sc().let { sc -> val r = sc.synthetic.ray ?: sc.rayTo(sc.ahead(1.2f, 0.4f, -0.3f)); sc.syntheticSource = playAs; sc.synthetic.queue.addLast(r to false); sc.synthetic.queue.addLast(r to true); sc.synthetic.queue.addLast(r to false) }
            "shoot" -> ev(Ui.Shoot)
            "key" -> sc().synthetic.keys.addLast(a.getOrNull(0) ?: "a")
            "stick" -> sc().let { it.syntheticSource = Source.CONTROLLER; playAs = Source.CONTROLLER; it.synthetic.stick = (f(a.getOrNull(0)) ?: 0f) to (f(a.getOrNull(1)) ?: 0f) }
            "controller" -> s?.controllers?.debugPresent = a.getOrNull(0) != "off"
            "hands" -> if (a.getOrNull(0) == "live") s?.startHandsLive() else s?.hands?.forceLost = a.getOrNull(0) == "lost"
            "sample" -> { RsController.camera?.sample = SampleFrame.entries.firstOrNull { it.id == a.getOrNull(0) }; log("sample ${RsController.camera?.sample}") }
            // frame w,h[,floating]: a frame of that size at the stand-in's aim (同上次 path)
            "frame" -> {
                val v = a.getOrNull(0)?.split(',') ?: return
                val sc = sc()
                val origin = sc.headPose.position
                val dir = (sc.ahead(1.2f, 0f, -0.1f) - origin).normalized()
                RsController.snapper.frameAtAim(v[0].toFloat(), v[1].toFloat(), v.getOrNull(2) == "floating", origin, dir, sc.planes.current())
                RsController.onSnap(listOf(io.embodit.regionsnap.domain.SnapEvent.Framed(io.embodit.regionsnap.domain.StrokeJudge.Kind.BOX)))
            }
            // a stand-in plane: `plane wall d` (a 3 x 2.5 m wall d m ahead) / `plane table` / `plane clear`
            "plane" -> {
                val sc = sc()
                sc.planes.debug.clear()
                when (a.getOrNull(0)) {
                    "wall" -> {
                        val d = f(a.getOrNull(1)) ?: 1.5f
                        val c = sc.ahead(d)
                        val n = (sc.headPose.position - c).let { Vec3(it.x, 0f, it.z).normalized() }
                        val r = Vec3(-n.z, 0f, n.x)
                        val b = Vec3(c.x, 0f, c.z)
                        sc.planes.debug += PlaneSurface("debug-wall", "WALL", listOf(b - r * 1.5f, b + r * 1.5f, b + r * 1.5f + Vec3.UP * 2.5f, b - r * 1.5f + Vec3.UP * 2.5f))
                    }
                    "table" -> {
                        val c = sc.ahead(0.7f)
                        val y = sc.headPose.position.y - 0.55f
                        sc.planes.debug += PlaneSurface("debug-table", "TABLE", listOf(Vec3(c.x - 0.6f, y, c.z + 0.4f), Vec3(c.x + 0.6f, y, c.z + 0.4f), Vec3(c.x + 0.6f, y, c.z - 0.4f), Vec3(c.x - 0.6f, y, c.z - 0.4f)))
                    }
                    else -> Unit
                }
                log("plane ${sc.planes.debug.map { it.semantic }}")
            }
            "calib" -> when (a.getOrNull(0)) {
                "start" -> ev(Ui.CalibStart)
                "ok" -> ev(Ui.CalibOk)
                "degraded" -> ev(Ui.CalibDegraded)
                "clear" -> ev(Ui.CalibClear)
                "cancel" -> ev(Ui.CalibCancel)
                else -> ev(Ui.CalibStart)
            }
            "noPermission" -> {
                val off = a.getOrNull(1) == "off"
                when (a.getOrNull(0)) {
                    "eye" -> s?.eyes?.forceOff = !off
                    else -> RsController.camera?.forceNoPermission = !off
                }
            }
            "anchorsFail" -> { RsController.anchors.fail = a.getOrNull(0) != "off"; RsController.restorePins() }
            "save" -> ev(Ui.PreviewSave(false))
            "savepin" -> ev(Ui.PreviewSave(true))
            "retake" -> ev(Ui.PreviewRetake)
            "rectify" -> ev(Ui.PreviewRectify(a.getOrNull(0) != "off"))
            "corner" -> ev(Ui.PreviewCorner(a.getOrNull(0)?.toIntOrNull() ?: 0, Px(f(a.getOrNull(1)) ?: 0f, f(a.getOrNull(2)) ?: 0f)))
            "pin" -> RsController.library.shots.getOrNull(a.getOrNull(0)?.toIntOrNull() ?: 0)?.let { ev(Ui.PinBack(it.id)) }
            "unpin" -> RsController.library.pins.getOrNull(a.getOrNull(0)?.toIntOrNull() ?: 0)?.let { ev(Ui.Unpin(it.shotId)) }
            "front" -> RsController.library.shots.getOrNull(a.getOrNull(0)?.toIntOrNull() ?: 0)?.let { ev(Ui.PinFront(it.id)) }
            "pinbar" -> RsController.library.pins.getOrNull(a.getOrNull(0)?.toIntOrNull() ?: 0)?.let { ev(Ui.PinBarOpen(it.shotId)) }
            "open" -> RsController.library.shots.getOrNull(a.getOrNull(0)?.toIntOrNull() ?: 0)?.let { ev(Ui.OpenShot(it.id)) }
            "delete" -> { ev(Ui.DeleteAsk); if (a.getOrNull(0) == "yes") ev(Ui.DeleteYes) }
            "tab" -> ev(Ui.SelectTab(when (a.getOrNull(0)) { "shots" -> Tab.SHOTS; "settings" -> Tab.SETTINGS; else -> Tab.SNAP }))
            "lang" -> ev(Ui.SetPrefs("lang") { it.copy(lang = when (a.getOrNull(0)) { "zh" -> Lang.ZH; "en" -> Lang.EN; else -> Lang.SYSTEM }) })
            "mute" -> ev(Ui.SetPrefs("mute") { it.copy(muted = a.getOrNull(0) != "off") })
            "stick2surface" -> ev(Ui.SetPrefs("stick") { it.copy(stick = a.getOrNull(0) != "off") })
            "pinch" -> ev(Ui.SetPrefs("pinch") { it.copy(pinchMode = when (a.getOrNull(0)) { "box" -> Snapper.PinchMode.BOX; "loop" -> Snapper.PinchMode.LOOP; else -> Snapper.PinchMode.AUTO }) })
            "res" -> ev(Ui.SetPrefs("res") { it.copy(hiRes = a.getOrNull(0) == "hi") })
            "bar" -> RsController.hintHold = a.getOrNull(0) == "long"
            "hold" -> when (a.getOrNull(0)) { "shrink", "on" -> RsController.holdShrink = true; else -> RsController.holdShrink = false }
            "hint" -> RsController.hint(io.embodit.regionsnap.ui.Hint.valueOf(a.getOrNull(0)?.uppercase() ?: "TOO_SMALL"), a.getOrNull(1)?.toIntOrNull() ?: 3)
            "pitch" -> s?.debugPitchDeg = f(a.getOrNull(0)) ?: 0f
            "recall" -> ev(Ui.Recall)
            "close" -> runCatching { WindowCtl.navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
            "openpanel" -> ev(Ui.OpenPanel)
            "moveaside" -> ev(Ui.MoveAside("debug"))
            "quit" -> ev(Ui.QuitAsk)
            "exit" -> { ev(Ui.SelectTab(Tab.SETTINGS)); ev(Ui.QuitAsk); later(800) { ev(Ui.QuitYes) } }
            "start" -> ev(Ui.OnboardStart)
            "redraw" -> ev(Ui.Redraw)
            "same" -> ev(Ui.SameAsLast)
            // rotate left|right|face: the [左转] / [右转] / [回正] keys (v0.1.4)
            "rotate" -> ev(when (a.getOrNull(0)) { "right" -> Ui.Rotate(-1); "face" -> Ui.FaceMe; else -> Ui.Rotate(1) })
            // grip on|off: the stand-in controller grip (with `stick x y` it turns the frame about the vertical axis)
            "grip" -> sc().let { it.syntheticSource = Source.CONTROLLER; playAs = Source.CONTROLLER; it.synthetic.grip = a.getOrNull(0) != "off" }
            "about" -> ev(Ui.About)
            "onboard" -> { ev(Ui.SetPrefs("onboard") { it.copy(onboarded = false) }); RsController.set("debug onboarding") { it.copy(onboarding = true, tab = Tab.SNAP) } }
            "fit" -> if (a.getOrNull(0) == "reset") TextFit.reset() else RsLog.i(TextFit.summary())
            "where" -> s?.let { RsLog.i(it.where()) }
            "panels" -> s?.panelDump()?.forEach { RsLog.i(it) }
            "dump" -> RsLog.i(RsController.dump())
            else -> log("unknown $cmd")
        }
        log("$cmd ${a.joinToString(" ")} done")
    }

    /** Walks to a design state with the user's own steps (goto S00 / S23 cannot be reached this way and say so). */
    private fun goto(id: String) {
        when (id) {
            "S00" -> log("goto S00: cold start (adb shell am start), the plate shows while the head is not level (`pitch 30`)")
            "S01" -> run("onboard", emptyList())
            "S02" -> { ev(Ui.SelectTab(Tab.SNAP)); ev(Ui.Redraw); scene()?.let { it.syntheticSource = null } }
            "S03" -> { ev(Ui.SelectTab(Tab.SNAP)); ev(Ui.Redraw); run("ray", listOf("ahead", "1.2", "0.1", "-0.1")) }
            "S04" -> { ev(Ui.SelectTab(Tab.SNAP)); ev(Ui.Redraw); box(0.42f, 0.3f, 1.4f, hold = true, right = 0.62f) }
            "S05" -> { RsController.holdShrink = true; ev(Ui.SelectTab(Tab.SNAP)); ev(Ui.Redraw); loop(0.15f, 1.2f) }
            "S06" -> { ev(Ui.SelectTab(Tab.SNAP)); ev(Ui.Redraw); box(0.42f, 0.3f, 1.4f, right = 0.62f) }
            "S07" -> ensureFrame { run("handle", listOf("2", "0.05", "-0.05", "hold")) }
            "S08", "S09" -> ensureFrame { shootWith(RsController.camera?.sample?.id ?: "whiteboard") }
            "S10" -> ensureFrame { shootWith(RsController.camera?.sample?.id ?: "whiteboard"); later(2500) { ev(Ui.PreviewSave(false)) } }
            "S11", "S12" -> ensureFrame { shootWith(RsController.camera?.sample?.id ?: "whiteboard"); later(2500) { ev(Ui.PreviewSave(true)) } }
            "S13" -> ev(Ui.SelectTab(Tab.SHOTS))
            "S14" -> { ev(Ui.SelectTab(Tab.SHOTS)); RsController.library.shots.firstOrNull()?.let { ev(Ui.OpenShot(it.id)) } ?: log("S14: no shot yet, run goto S10 first") }
            "S15" -> ev(Ui.SelectTab(Tab.SETTINGS))
            "S16" -> { RsController.camera?.forceNoPermission = true; ensureFrame { shootWith(null) } }
            "S17" -> { run("input", listOf("finger")); RsController.hint(io.embodit.regionsnap.ui.Hint.EYE_FALLBACK) }
            "S17B" -> { run("input", listOf("eyenohand")); RsController.hint(io.embodit.regionsnap.ui.Hint.HAND_LOST) }
            "S18" -> { RsController.camera?.forceNoPermission = false; ensureFrame { shootWith(null) } }
            "S19" -> run("anchorsFail", emptyList())
            "S20" -> ev(Ui.CalibStart)
            "S21" -> { ev(Ui.SelectTab(Tab.SETTINGS)); ev(Ui.QuitAsk) }
            "S22" -> run("close", emptyList())
            "S23" -> log("goto S23: press the system Home key (adb shell input keyevent HOME), then reopen")
            "S24" -> run("lang", listOf("en"))
            else -> log("goto $id: unknown")
        }
    }
}
