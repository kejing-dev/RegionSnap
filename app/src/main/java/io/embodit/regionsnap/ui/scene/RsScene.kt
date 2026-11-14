package io.embodit.regionsnap.ui.scene

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.regionsnap.domain.HeadPose
import io.embodit.regionsnap.domain.InputArbiter
import io.embodit.regionsnap.domain.InputFrame
import io.embodit.regionsnap.domain.Num
import io.embodit.regionsnap.domain.OneEuro3
import io.embodit.regionsnap.domain.PinchDetector
import io.embodit.regionsnap.domain.Quat4
import io.embodit.regionsnap.domain.Rigid
import io.embodit.regionsnap.domain.SnapEvent
import io.embodit.regionsnap.domain.SnapFrame
import io.embodit.regionsnap.domain.Source
import io.embodit.regionsnap.domain.Surface
import io.embodit.regionsnap.domain.Vec3
import io.embodit.regionsnap.domain.WindowSpot
import io.embodit.regionsnap.domain.Yaw
import io.embodit.regionsnap.domain.pinInFront
import io.embodit.regionsnap.domain.pinsOutOfSight
import io.embodit.regionsnap.domain.placeUpright
import io.embodit.regionsnap.platform.RsLog
import io.embodit.regionsnap.platform.input.Controllers
import io.embodit.regionsnap.platform.input.Device
import io.embodit.regionsnap.platform.input.Eyes
import io.embodit.regionsnap.platform.input.Hands
import io.embodit.regionsnap.platform.input.Ray
import io.embodit.regionsnap.platform.sense.Planes
import io.embodit.regionsnap.platform.tracking.HeadTracker
import io.embodit.regionsnap.ui.Hint
import io.embodit.regionsnap.ui.InputMode
import io.embodit.regionsnap.ui.Phase
import io.embodit.regionsnap.ui.RsController
import io.embodit.regionsnap.ui.Ui
import io.embodit.regionsnap.ui.components.Haptics
import io.embodit.regionsnap.ui.components.PanelSizes
import io.embodit.regionsnap.ui.theme.RsColors
import io.embodit.regionsnap.ui.window.WindowCtl
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The scene of the open Stage (debug hooks and the frame system talk to it). Main thread only. */
internal object ActiveScene {
    var current: RsScene? = null
}

/** The one per-frame ECS system: input → [io.embodit.regionsnap.domain.Snapper] → transforms (no Compose per frame). */
class RsFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.current?.onFrame(context)
    }
}

object PanelIds {
    /** The shutter bar under the frame (design panels.shutterBar). */
    const val BAR = "bar"
    /** The small bar over a pinned note: 打开 / 取下 / 移到面前 (design pin.interact). */
    const val PINBAR = "pinbar"
    /** The hint plate, only while the main window is not open (S00 / S22). */
    const val PLATE = "plate"
    val pool = listOf(BAR, PINBAR, PLATE)
}

/** The debug stand-in for a device (design debugHooks `ray` / `press` / `drag` / `loop`): same InputFrame path. */
class Synthetic {
    @Volatile var ray: Ray? = null
    @Volatile var pressed: Boolean = false
    @Volatile var stick: Pair<Float, Float> = 0f to 0f
    /** The stand-in's controller grip (debug `grip on|off`). */
    @Volatile var grip: Boolean = false
    /** The stand-in's pinching hand (eye + hand); null = it rides 0.45 m along the stand-in ray (debug `hand`). */
    @Volatile var hand: Vec3? = null
    /** One entry per engine frame (the emulator runs at a few fps: every step must be seen). */
    val queue = ArrayDeque<Pair<Ray?, Boolean>>()
    /** Controller keys: "a" / "x" shoot, "b" / "y" redraw. */
    val keys = ArrayDeque<String>()
}

/**
 * The 3D side (design entities3d): reticle, controller ray, the frame (4 edges + 9 handles), the drawing trail, pinned notes,
 * and the three small Stage panels. All per-frame work happens in [onFrame]; the panels hear through [RsController] only when
 * what they show changes. Reality is never made interactive (D24): no plane colliders, rays only meet our own maths.
 */
class RsScene {
    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var appContext: Context? = null
    val head = HeadTracker()
    val controllers = Controllers()
    val eyes = Eyes()
    val hands = Hands()
    val planes = Planes()
    private val arbiter = InputArbiter()
    private val pinchers = listOf(PinchDetector(), PinchDetector())
    private val gazeFilter = OneEuro3()
    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var mats: SceneMaterials? = null
    private var built = false
    private var attached = false
    private var paused = false

    // entities
    private var reticle: Entity? = null
    private val edges = ArrayList<Entity>()
    private val handles = ArrayList<Entity>()
    private var trail: Entity? = null
    private var trailMesh: MeshResource? = null
    private var cube: MeshResource? = null
    private var ball: MeshResource? = null
    private var matFrame: Material? = null
    private var matFlash: Material? = null
    private var matHandle: Material? = null
    private var matHot: Material? = null
    private var matLevel: Material? = null
    private val pinViews = HashMap<String, PinView>()
    private var pinsEpochShown = -1

    // head
    private var pose = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    private var hasHead = false
    private val ring = ArrayDeque<Pair<Long, Rigid>>()

    // state
    private var startMs = 0L
    private var lastFrameMs = 0L
    private var lastErrorMs = 0L
    private var flashSeqSeen = 0
    private var flashUntil = 0L
    private var shrinkFrom: List<Vec3> = emptyList()
    private var shrinkAt = 0L
    private var lastAimKey = ""
    private var pressSinceMs = -1L
    private var longPressDone = false
    private var plateAt: Vec3? = null
    private var plateTurn = 0
    private var plateFrame: SnapFrame? = null
    private var tuckCheckMs = 0L
    private var windowCenter: Vec3? = null
    private var windowYaw = 0f
    private var windowEpoch = -1
    private var behindCheckMs = 0L
    private var behindShown = false
    private var lastMode: InputMode? = null
    private var lastPinch = -1
    private var hintedMode: InputMode? = null

    /** Debug: the stand-in input and which source it plays. */
    val synthetic = Synthetic()
    @Volatile var syntheticSource: Source? = null

    /** Debug `input finger|head|eyenohand`: the mode the panel shows (the emulator has no eyes / hands to lose). */
    @Volatile var debugMode: InputMode? = null

    /** Debug `pitch deg`: placements as if the head were pitched (emulator camera is fixed). */
    var debugPitchDeg = 0f

    // =========================================================================================== lifecycle

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments, context: Context) {
        if (attached || stageRoot != null) detach()
        startMs = SystemClock.elapsedRealtime()
        this.content = content
        appContext = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        // an entity added to the SpatialView is not at the Stage origin (Spatial Ruler 0.256 m): move the root once
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.BAR, PanelSizes.BAR_W, PanelSizes.BAR_H, PanelSizes.SCALE).maxPitch = 0f
        rig.world(PanelIds.PINBAR, PanelSizes.PINBAR_W, PanelSizes.PINBAR_H, PanelSizes.SCALE).maxPitch = 0f
        rig.world(PanelIds.PLATE, PanelSizes.PLATE_W, PanelSizes.PLATE_H, PanelSizes.SCALE).maxPitch = 0f
        startProviders()
        ActiveScene.current = this
        attached = true
        RsController.poseAt = ::poseAt
        RsController.headNow = { if (hasHead) pose else null }
        RsController.quitHook = { releaseAll() }
        WindowCtl.oldSpotClear = ::oldSpotClear
        bindPanels(attachments)
        scope?.launch { build(root) }
        RsController.restorePins()
        RsLog.i("SCENE attached root offset=${RsLog.pos(offset.domain())} emulator=${Device.onEmulator}")
    }

    private fun startProviders() {
        head.start()
        controllers.start()
        eyes.start()
        // emulator: the hand provider breaks the emulator's own gaze + pinch for every app (X-ray Hand); debug `hands live`
        if (!Device.onEmulator) hands.start()
        planes.start()
        RsController.anchors.start()
    }

    private suspend fun build(root: Entity) {
        delay(150)
        val t0 = SystemClock.elapsedRealtime()
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        mats = m
        val c = sh.keep(Shapes.cube("cube"))
        val b = sh.keep(Shapes.ball("ball", 16, 10))
        val r = sh.keep(Shapes.ring("ring", 0.36f, 40))
        cube = c
        ball = b
        matFrame = m.unlit(RsColors.Frame)
        matFlash = m.unlit(RsColors.FrameFlash)
        matHandle = m.unlit(RsColors.Handle)
        matHot = m.unlit(RsColors.HandleHot)
        matLevel = m.unlit(RsColors.Level)
        reticle = model(r, m.unlit(RsColors.Reticle), "reticle", root).also { it.enabled = false }
        delay(16)
        repeat(4) { edges += model(c, matFrame!!, "edge$it", root).also { e -> e.enabled = false } }
        repeat(9) { i -> handles += model(b, if (i == 8) m.sheer(RsColors.Handle, 0.3f) else matHandle!!, "handle$i", root).also { e -> e.enabled = false } }
        delay(16)
        val tm = sh.keep(MeshBuilder(8).build("trail"))
        trailMesh = tm
        trail = model(tm, m.unlit(RsColors.Trail), "trail", root).also { it.enabled = false }
        built = true
        RsLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
    }

    fun pause() {
        if (paused || !attached) return
        paused = true
        head.stop()
        controllers.stop()
        eyes.stop()
        hands.stop()
        planes.stop()
        RsLog.i("SCENE paused (S23)")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        startProviders()
        lastFrameMs = 0L
        RsLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    private fun releaseAll() {
        head.stop()
        controllers.stop()
        eyes.stop()
        hands.stop()
        planes.stop()
        RsController.anchors.stop()
    }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.current === this) ActiveScene.current = null
        RsController.poseAt = null
        RsController.headNow = null
        RsController.quitHook = null
        WindowCtl.oldSpotClear = { true }
        main.removeCallbacksAndMessages(null)
        scope?.cancel()
        scope = null
        releaseAll()
        panelShown.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        rig = null
        built = false
        edges.clear()
        handles.clear()
        pinViews.clear()
        pinsEpochShown = -1
        RsLog.i("SCENE detached")
    }

    /** HMD pose nearest to [atMs] from the last 300 ms (design capture: 位姿配对). */
    fun poseAt(atMs: Long): Rigid? = ring.minByOrNull { abs(it.first - atMs) }?.second ?: if (hasHead) Rigid(pose.position, pose.rotation) else null

    // =========================================================================================== per frame

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameMs = now
        head.pose()?.let {
            // a first frame of all zeros + identity is "not tracking yet" (hand-sign teacher lesson)
            val zero = it.position.length < 1e-3f && abs(it.rotation.w) > 0.99999f
            if (!zero) {
                pose = if (debugPitchDeg == 0f) it else HeadPose(it.position, (it.rotation * Quat4.axisAngle(Vec3.RIGHT, Num.rad(debugPitchDeg))).normalized())
                hasHead = true
                ring.addLast(now to Rigid(it.position, it.rotation))
                while (ring.isNotEmpty() && now - ring.first().first > 300) ring.removeFirst()
            }
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        WindowCtl.stepGate(now, if (hasHead) Num.deg(pose.pitch) else null, if (hasHead && WindowCtl.waiting) WindowSpot.turnDeg(pose, keepClear(), windowSize()) else 0f)
        if (!attached || paused) return
        try {
            if (built) {
                stepInput(now, dt)
                stepView(now)
                stepPins(now)
            }
            placePanels(now)
        } catch (t: Throwable) {
            // an engine call failing for one frame (runtime restarting) must not take the app down
            if (now - lastErrorMs > 2000) {
                lastErrorMs = now
                RsLog.e("FRAME step failed: $t", t)
            }
        }
        rig.faceViewer(pose.position)
    }

    // ------------------------------------------------------------------------------------------- input

    private fun stepInput(now: Long, dt: Float) {
        val ui = RsController.state.value
        val btn = controllers.takeButtons()
        val ctrlRay = if (controllers.available) controllers.aimRay() else null
        val handSamples = hands.sample()
        // pinch edges per hand (design input.eyeHand.pinch: hysteresis, 2 frames)
        var pinched = false
        var pinchEdge = false
        var fingerRay: Ray? = null
        for ((i, p) in pinchers.withIndex()) {
            val h = handSamples.firstOrNull { it.right == (i == 1) }
            val e = p.step(h?.pinchM)
            if (e == 1) { pinchEdge = true; lastPinch = i }
            if (p.pinched) pinched = true
        }
        fingerRay = handSamples.firstOrNull { it.right == (lastPinch != 0) }?.finger ?: handSamples.firstOrNull()?.finger
        // the pinching hand's thumb-index midpoint: what moves the frame while a pinch is held (indirect pinch-drag)
        var pinchPoint = handSamples.firstOrNull { it.right == (lastPinch != 0) }?.pinch ?: handSamples.firstOrNull()?.pinch
        if (btn.trigger > 0 || btn.shoot > 0 || btn.clear > 0) arbiter.action(Source.CONTROLLER, now)
        if (ctrlRay != null) arbiter.controllerPose(ctrlRay.origin, now)
        if (pinchEdge) arbiter.action(Source.EYE, now)
        Haptics.right = controllers.rightAims

        val synth = syntheticSource
        val src = synth ?: arbiter.active
        val eyeRay = eyes.ray(now)
        val eyeReason = eyes.unavailableReason(now)
        val handsOk = hands.available
        // the aim and the input mode (design input.eyeHand.aim fallbacks: eyes → finger → head, each with a hint)
        val devMode: InputMode
        var ray: Ray?
        var pressed: Boolean
        if (src == Source.CONTROLLER) {
            devMode = InputMode.CONTROLLER
            ray = ctrlRay
            pressed = controllers.triggerHeld
        } else {
            pressed = pinched
            when {
                eyeRay != null -> {
                    devMode = if (handsOk || Device.onEmulator) InputMode.EYE_HAND else InputMode.EYE_NO_HAND
                    // smooth the gaze (one-euro) along the ray
                    val p = gazeFilter.step(eyeRay.origin + eyeRay.dir, dt)
                    ray = Ray(eyeRay.origin, (p - eyeRay.origin).normalized())
                }
                fingerRay != null -> { devMode = InputMode.FINGER; ray = fingerRay }
                else -> {
                    devMode = InputMode.HEAD
                    // last resort only (D1): the head direction, with a hint on the panel
                    ray = if (hasHead) Ray(pose.position, pose.forward) else null
                }
            }
        }
        var mode2 = devMode
        var keyShoot = false
        var keyClear = false
        if (synth != null) {
            synthetic.queue.removeFirstOrNull()?.let { (r, p) -> if (r != null) synthetic.ray = r; synthetic.pressed = p }
            synthetic.ray?.let { ray = it }
            pressed = synthetic.pressed
            if (synth != Source.CONTROLLER) pinchPoint = synthetic.hand ?: ray?.let { it.origin + it.dir * SYNTH_HAND_M }
            when (synthetic.keys.removeFirstOrNull()) { "a", "x" -> keyShoot = true; "b", "y" -> keyClear = true }
            // the stand-in plays the source it was told to (`input eye|finger|head|controller`), whatever the emulator lacks
            mode2 = when (synth) { Source.CONTROLLER -> InputMode.CONTROLLER; Source.EYE -> InputMode.EYE_HAND; Source.FINGER -> InputMode.FINGER; Source.HEAD -> InputMode.HEAD }
        }
        debugMode?.let { mode2 = it }
        val stick = if (src == Source.CONTROLLER) (if (synth != null) synthetic.stick else controllers.stick()) else 0f to 0f
        val onUi = now - WindowCtl.lastPointerMs < UI_MASK_MS || ray?.let { onUi(it) } == true
        // S22: with the window closed, A / X or a 1 s hold opens it instead of shooting
        var shootKey = btn.shoot > 0 || keyShoot
        // (not while the window is tucked: the user moved it aside to frame, so a held press is a stroke and A·X shoots;
        // the bar's [Panel] and the plate's [Open panel] bring it back, q1-1 / q2-1)
        if (!ui.panelOpen && ui.panelEverOpened && !ui.panelTucked) {
            if (shootKey) { RsController.onEvent(Ui.OpenPanel); shootKey = false }
            if (pressed && RsController.snapper.judge.phase == io.embodit.regionsnap.domain.PressJudge.Phase.PENDING) {
                if (pressSinceMs < 0) pressSinceMs = now
                if (!longPressDone && now - pressSinceMs > 1000) { longPressDone = true; RsController.onEvent(Ui.OpenPanel) }
            } else { pressSinceMs = -1; longPressDone = false }
        }
        // eye + hand: the gaze (or finger) only picks where the pinch grabs; the hand drags. The head fallback never drags.
        val canDrag = src != Source.HEAD && mode2 != InputMode.HEAD
        val frame = InputFrame(
            src, ray?.origin, ray?.dir, pressed && ui.phase != Phase.PREVIEW && ui.phase != Phase.CAPTURING, onUi, stick.first, stick.second, shootKey, btn.clear > 0 || keyClear,
            pinchPoint = if (src == Source.CONTROLLER) null else pinchPoint, eye = if (hasHead) pose.position else null, canDrag = canDrag,
            grip = src == Source.CONTROLLER && (if (synth != null) synthetic.grip else controllers.gripHeld),
        )
        val sn = RsController.snapper
        sn.pins = pinFrames()
        val before = sn.frame
        val events = sn.step(frame, planes.current(), now, dt)
        // a 1 s hold that reopened the window (S22) does not also shoot on release
        val out = if (longPressDone) events.filterNot { it == SnapEvent.Shoot } else events
        if (out.isNotEmpty()) RsController.onSnap(out)
        for (e in events) if (e is SnapEvent.Framed && (e.kind != io.embodit.regionsnap.domain.StrokeJudge.Kind.BOX)) { shrinkFrom = ArrayList(trailSnapshot); shrinkAt = now }
        trailSnapshot = ArrayList(sn.trail)
        if (sn.frame != before && events.none { it is SnapEvent.Framed }) RsController.frameFacts()
        // low-frequency facts for the panel
        val aim = sn.aim
        val depth = (aim?.distance ?: 0f)
        val mode = mode2
        val key = "$mode/$src/${aim?.surface}/${String.format(Locale.US, "%.1f", depth)}"
        if (key != lastAimKey) {
            lastAimKey = key
            RsController.onAim(mode, src, aim?.surface, depth)
            if (mode != lastMode) {
                RsLog.i("INPUT mode=$mode source=$src eye=${eyeReason ?: "ok"} hands=${hands.supportState()} controller=${controllers.supportState()}")
                lastMode = mode
            }
        }
        if (mode != hintedMode && (mode == InputMode.FINGER || mode == InputMode.EYE_NO_HAND || mode == InputMode.HEAD) && src != Source.CONTROLLER && now - startMs > 4000) {
            hintedMode = mode
            RsController.hint(when (mode) { InputMode.FINGER -> Hint.EYE_FALLBACK; InputMode.EYE_NO_HAND -> Hint.HAND_LOST; else -> Hint.HEAD_FALLBACK })
        }
        val eyePerm = eyeReason != "permission"
        if (eyePerm != ui.eyePermission) RsController.set("eye perm") { it.copy(eyePermission = eyePerm) }
    }

    private var trailSnapshot: List<Vec3> = emptyList()

    /** pressPriority ①: the ray meets the main window (estimated where the system opened it) or a shown Stage panel. */
    private fun onUi(r: Ray): Boolean {
        val wc = windowCenter
        if (wc != null && WindowCtl.open) {
            val n = Yaw.dir(windowYaw) * -1f
            val hit = io.embodit.regionsnap.domain.Rays.plane(r.origin, r.dir, wc, n)
            if (hit != null) {
                val right = Vec3(n.z, 0f, -n.x).normalized()
                val d = hit - wc
                if (abs(d.dot(right)) < PanelSizes.WINDOW_W * WindowCtl.dpM / 2f && abs(d.y) < PanelSizes.WINDOW_H * WindowCtl.dpM / 2f + 0.05f) return true
            }
        }
        val rig = rig ?: return false
        for (id in PanelIds.pool) {
            val s = rig[id] ?: continue
            if (!s.shown) continue
            val n = s.facing.normal
            val hit = io.embodit.regionsnap.domain.Rays.plane(r.origin, r.dir, s.position, n) ?: continue
            val d = hit - s.position
            if (abs(d.dot(s.facing.right)) < s.widthDp * DP_M * s.scaleX / 2f && abs(d.dot(s.facing.up)) < s.heightDp * DP_M * s.scaleY / 2f) return true
        }
        return false
    }

    // ------------------------------------------------------------------------------------------- view

    private fun stepView(now: Long) {
        val ui = RsController.state.value
        val sn = RsController.snapper
        // reticle: on the surface 3 mm out, facing out; floating: facing the aimer (design entities3d.reticle)
        val aim = sn.aim
        val ret = reticle!!
        val showRet = aim != null && ui.phase != Phase.PREVIEW && sn.judge.phase != io.embodit.regionsnap.domain.PressJudge.Phase.ADJUST
        ret.on(showRet)
        if (showRet && aim != null) {
            val k = 0.024f * (aim.distance / 1.2f).coerceIn(0.6f, 2.5f)
            ret.setLocal(position = aim.point + aim.normal * 0.003f, rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), aim.normal), scale = Vec3(k, k, 1f))
        }
        // v0.1.5 (user: "the controller shows a white and a green ray, keep one"): no ray of our own, the Full Space
        // system ray is the only one (profile D41); the reticle above is a hit mark, not a ray
        // the frame: while drawing the live rect, else the placed one
        val f = sn.drawing ?: sn.frame
        if (ui.flashSeq != flashSeqSeen) {
            flashSeqSeen = ui.flashSeq
            flashUntil = now + 120
        }
        val flash = now < flashUntil
        val shrinking = ui.phase == Phase.SHRINK && (RsController.holdShrink || now - shrinkAt < 300)
        for ((i, e) in edges.withIndex()) {
            e.on(f != null && !shrinking)
            if (f != null) {
                val c = f.corners()
                e.bar(c[i], c[(i + 1) % 4], 0.006f)
                e.setMaterial(if (flash) matFlash!! else matFrame!!)
            }
        }
        val placed = sn.frame
        val showHandles = placed != null && sn.drawing == null && ui.phase != Phase.CAPTURING
        for ((i, h) in handles.withIndex()) {
            h.on(showHandles)
            if (!showHandles || placed == null) continue
            val p = placed.handles()[i]
            val hot = sn.hoverHandle == i || (sn.judge.phase == io.embodit.regionsnap.domain.PressJudge.Phase.ADJUST && sn.judge.handle == i)
            val base = when (i) { 8 -> 0.04f; in 4..7 -> 0.024f; else -> 0.03f }
            val d = (p - pose.position).length
            // a little bigger far away so a 2 m handle stays visible; 1.3x when hovered / grabbed
            val s = base * (d / 1.2f).coerceIn(1f, 2f) * if (hot) 1.3f else 1f
            h.setLocal(position = p, scale = Vec3(s, s, s))
            if (i != 8) h.setMaterial(if (hot) matHot!! else matHandle!!)
        }
        // the trail (≤ 240 segments, 3 mm), and its 300 ms shrink into the rect (S05)
        val t = trail!!
        val pts = when {
            sn.drawing != null && sn.trail.size > 1 -> sn.trail
            shrinking && placed != null -> {
                // debug `hold shrink`: the middle frame of the 300 ms shrink, held for the S05 screenshot (q1-5)
                val k = if (RsController.holdShrink) 0.5f else ((now - shrinkAt) / 300f).coerceIn(0f, 1f)
                // each trail point slides to the nearest point on the rectangle's outline (the loop tightens into the frame)
                shrinkFrom.map { p -> p.lerp(placed.onOutline(p), k) }
            }
            else -> emptyList()
        }
        t.on(pts.size > 1 && (shrinking || RsController.snapper.pinchMode != io.embodit.regionsnap.domain.Snapper.PinchMode.BOX))
        if (pts.size > 1) {
            val b = MeshBuilder(pts.size * 24)
            for (i in 1 until pts.size) b.addBar(pts[i - 1], pts[i], 0.003f)
            trailMesh?.let { b.replace(it) }
        }
        if (ui.phase == Phase.SHRINK && !RsController.holdShrink && now - shrinkAt >= 300 && shrinkAt > 0) shrinkAt = 0
    }

    // ------------------------------------------------------------------------------------------- pins

    private class PinView(val root: Entity, val photo: Entity, val border: List<Entity>, var frame: SnapFrame)

    private fun pinFrames(): List<SnapFrame> = RsController.library.pins.map { p -> pinViews[p.shotId]?.frame ?: p.frame }

    private fun stepPins(now: Long) {
        val lib = RsController.library
        if (RsController.pinsEpoch != pinsEpochShown) {
            pinsEpochShown = RsController.pinsEpoch
            syncPins()
        }
        // live drag: the dragged note follows (released → the controller saves and re-anchors)
        val sn = RsController.snapper
        val dragged = sn.draggedPin
        if (dragged != null) {
            val idx = sn.judge.pin
            lib.pins.getOrNull(idx)?.let { p -> pinViews[p.shotId]?.let { v -> v.frame = dragged; placePin(v) } }
        }
        val hover = sn.hoverPin
        lib.pins.forEachIndexed { i, p -> pinViews[p.shotId]?.border?.forEach { it.setMaterial(if (i == hover || p.shotId == RsController.state.value.pinBar) matHot!! else matFrame!!) } }
        // design pin.restoreRule: every note behind / > 2.5 m → one hint, once per session
        if (!behindShown && hasHead && now - behindCheckMs > 2000 && now - startMs > 6000) {
            behindCheckMs = now
            val shown = lib.pins.filter { it.restored }.map { it.frame }
            if (pinsOutOfSight(pose, shown)) {
                behindShown = true
                RsController.hint(Hint.PINS_BEHIND, shown.size)
            }
        }
    }

    private fun syncPins() {
        val root = stageRoot ?: return
        val m = mats ?: return
        val lib = RsController.library
        val want = lib.pins.filter { it.restored }.associateBy { it.shotId }
        for (id in pinViews.keys.toList()) if (id !in want) {
            pinViews.remove(id)?.root?.let { runCatching { it.destroy(true) } }
            RsLog.i("PIN entity removed $id")
        }
        for ((id, p) in want) {
            val v = pinViews[id]
            if (v != null) {
                if (v.frame != p.frame) { v.frame = p.frame; placePin(v) }
                continue
            }
            val pr = Entity().named("pin:$id")
            root.addChild(pr)
            // 0.4 mm photo + 4 mm border (design pin.what); the photo's texture loads off the main thread
            val photo = model(quadXY(1f, 1f, "pinq:$id"), m.unlit(RsColors.KeyFill), "pin:$id:photo", pr)
            val border = (0 until 4).map { model(cube!!, matFrame!!, "pin:$id:edge$it", pr) }
            val view = PinView(pr, photo, border, p.frame)
            pinViews[id] = view
            placePin(view)
            scope?.launch {
                val bmp: Bitmap? = withContext(Dispatchers.IO) { RsController.gallery?.load(id, 1024)?.let { SceneMaterials.flipV(it) } }
                if (bmp != null && pinViews[id] === view) {
                    runCatching { photo.setMaterial(m.unlit(androidx.compose.ui.graphics.Color.White, m.textureFlipped(bmp))) }
                    RsLog.i("PIN texture $id ${bmp.width}x${bmp.height}")
                }
            }
            RsLog.i("PIN entity $id at ${RsLog.pos(p.frame.center)} ${p.frame.sizeLabel()}")
        }
    }

    /** The note: a quad on the frame (2 mm off the surface), its own axes, the border on its edges. */
    private fun placePin(v: PinView) {
        val f = v.frame
        val rot = Quat4.fromBasis(f.right, f.up, f.normal)
        v.root.setLocal(position = Vec3.ZERO)
        v.photo.setLocal(position = f.center + f.normal * 0.002f, rotation = rot, scale = Vec3(f.w, f.h, 1f))
        val c = f.corners().map { it + f.normal * 0.0025f }
        v.border.forEachIndexed { i, e -> e.bar(c[i], c[(i + 1) % 4], 0.004f) }
    }

    // ------------------------------------------------------------------------------------------- Stage panels

    private fun placePanels(now: Long) {
        val rig = rig ?: return
        val ui = RsController.state.value
        // where the system put the window (estimate: WindowSpot.DIST_M ahead of the head that opened it, SDK 6.1 cannot read it, N15)
        if (WindowCtl.createdEpoch != windowEpoch && hasHead) {
            windowEpoch = WindowCtl.createdEpoch
            val u = placeUpright(pose, 0f, WindowSpot.DIST_M, 0f)
            windowCenter = u.position
            windowYaw = u.yaw
            RsLog.i("PANEL estimate center=${RsLog.pos(windowCenter)} yaw=${String.format(Locale.US, "%.0f", Num.deg(windowYaw))}")
        }
        // q1-1: the open window must not cover the frame or its shutter bar while framing; put it away when it does
        if (WindowCtl.open && hasHead && now - tuckCheckMs > 200) {
            tuckCheckMs = now
            val wc = windowCenter
            val framing = ui.phase == Phase.DRAWING || ui.phase == Phase.SHRINK || ui.phase == Phase.FRAMED || ui.phase == Phase.ADJUSTING || ui.phase == Phase.AIMING || ui.phase == Phase.IDLE
            val keep = keepClear()
            if (wc != null && framing && ui.tab == io.embodit.regionsnap.ui.Tab.SNAP && ui.card == io.embodit.regionsnap.ui.Card.NONE && keep.isNotEmpty()) {
                val gap = WindowSpot.gapDeg(pose.position, wc, windowYaw, keep, windowSize())
                if (gap < WindowSpot.TUCK_GAP_DEG) WindowCtl.tuck(String.format(Locale.US, "covers frame gap=%.1f", gap))
            }
        }
        // shutter bar: under the frame's bottom edge, 4 cm out; over it when the frame is closer than 0.35 m (design rules)
        val bar = rig[PanelIds.BAR]
        val f = RsController.snapper.frame
        val showBar = f != null && RsController.snapper.drawing == null && ui.phase != Phase.PREVIEW && hasHead
        if (showBar && f != null && bar != null) {
            val worldDown = Vec3(0f, -1f, 0f)
            val near = f.center.distanceTo(pose.position) < 0.35f
            val barH = PanelSizes.BAR_H * DP_M * PanelSizes.SCALE
            val (lo, hi) = f.corners().let { c -> c.minByOrNull { it.y }!!.y to c.maxByOrNull { it.y }!!.y }
            val y = if (near) hi + 0.04f + barH / 2f else lo - 0.04f - barH / 2f
            val at = Vec3(f.center.x, y, f.center.z) + f.normal * 0.02f
            @Suppress("UNUSED_VARIABLE") val unused = worldDown
            if (!bar.shown || bar.position.distanceTo(at) > 0.005f) bar.show(at)
        } else bar?.hide()
        // pin bar: over the chosen note
        val pb = rig[PanelIds.PINBAR]
        val pinId = ui.pinBar
        val pv = pinId?.let { pinViews[it] }
        if (pv != null && pb != null) {
            val c = pv.frame
            val top = c.corners().maxOf { it.y }
            val at = Vec3(c.center.x, top + 0.06f, c.center.z) + c.normal * 0.02f
            if (!pb.shown || pb.position.distanceTo(at) > 0.005f) pb.show(at)
        } else pb?.hide()
        // hint plate: only while the window is not up (S00 waiting for a level head, S22 closed); placed once, then locked
        val plate = rig[PanelIds.PLATE]
        val wantPlate = hasHead && (WindowCtl.levelHint || WindowCtl.turnHint != 0 || (!ui.panelOpen && ui.panelEverOpened)) && !ui.background
        if (wantPlate && plate != null) {
            // in the comfortable view (≤ 25° off the gaze, QA #2 q2-2), below or above the frame, never in front of it (q1-1)
            val turn = WindowCtl.turnHint
            // placed again when the frame it steps around is committed or replaced (it may have been placed mid-stroke)
            val around = if (RsController.snapper.drawing == null && ui.phase != Phase.ADJUSTING) RsController.snapper.frame else plateFrame
            val pf = plateFrame
            val moved = (around == null) != (pf == null) || (around != null && pf != null &&
                (around.center.distanceTo(pf.center) > 0.05f || abs(around.w - pf.w) > 0.05f || abs(around.h - pf.h) > 0.05f))
            if (plateAt == null || turn != plateTurn || moved) {
                plateTurn = turn
                plateFrame = around
                val halfW = Num.deg(kotlin.math.atan(PanelSizes.PLATE_W * DP_M * PanelSizes.SCALE / 2f / PLATE_DIST_M))
                val halfH = Num.deg(kotlin.math.atan(PanelSizes.PLATE_H * DP_M * PanelSizes.SCALE / 2f / PLATE_DIST_M))
                val spot = WindowSpot.plateSpot(pose, keepClear(), halfW, halfH, turn)
                val u = placeUpright(HeadPose(pose.position, Quat4.axisAngle(Vec3.UP, pose.yaw(0f))), spot.relYawDeg, PLATE_DIST_M, spot.downDeg)
                plateAt = u.position
                plate.show(u.position)
                RsLog.i("PANEL plate at ${RsLog.pos(u.position)} rel=${String.format(Locale.US, "%.0f", spot.relYawDeg)} down=${String.format(Locale.US, "%.0f", spot.downDeg)} closed=${!ui.panelOpen} tucked=${ui.panelTucked} level=${WindowCtl.levelHint} turn=$turn")
            }
        } else if (plate != null && plate.shown) {
            plate.hide()
            plateAt = null
            plateTurn = 0
            plateFrame = null
        }
    }

    /** The frame (live while drawing) and its shutter bar: what the window must keep clear (q1-1). */
    private fun keepClear(): List<Vec3> {
        val sn = RsController.snapper
        sn.drawing?.let { return WindowSpot.keepClear(it, 0f, 0f) } // no shutter bar while drawing
        val f = sn.frame ?: return emptyList()
        val barH = PanelSizes.BAR_H * DP_M * PanelSizes.SCALE
        return WindowSpot.keepClear(f, PanelSizes.BAR_W * DP_M * PanelSizes.SCALE / 2f, 0.04f + barH)
    }

    private fun windowSize() = WindowSpot.Size(PanelSizes.WINDOW_W, PanelSizes.WINDOW_H, WindowCtl.dpM)

    /** Restore after − only when the window's old spot still keeps the frame clear (q1-1). */
    private fun oldSpotClear(): Boolean {
        val c = windowCenter ?: return true
        val keep = keepClear()
        return keep.isEmpty() || WindowSpot.gapDeg(pose.position, c, windowYaw, keep, windowSize()) >= WindowSpot.MIN_GAP_DEG
    }

    // ------------------------------------------------------------------------------------------- debug

    /** A ray from the head through Stage point [p] (debug `ray` / `drag` aim at points). */
    fun rayTo(p: Vec3): Ray = Ray(pose.position, (p - pose.position).normalized())

    /** A point [d] metres along the head's level forward, [up] above / below eye level and [right] to the side. */
    fun ahead(d: Float, right: Float = 0f, up: Float = 0f): Vec3 {
        val yaw = pose.yaw(0f)
        val fwd = Yaw.dir(yaw)
        val r = Vec3(-fwd.z, 0f, fwd.x)
        return pose.position + fwd * d + r * right + Vec3.UP * up
    }

    val headPose: HeadPose
        get() = pose

    fun startHandsLive() {
        hands.start()
    }

    fun pinInFrontOfHead(w: Float, h: Float): SnapFrame = pinInFront(pose, w, h)

    fun where(): String =
        "WHERE head=${RsLog.pos(pose.position)} yaw=${String.format(Locale.US, "%.0f", Num.deg(pose.yaw(0f)))} pitch=${String.format(Locale.US, "%.0f", Num.deg(pose.pitch))} window=${RsLog.pos(windowCenter)} bar=${rig?.get(PanelIds.BAR)?.let { if (it.shown) RsLog.pos(it.position) else "hidden" }} plate=${rig?.get(PanelIds.PLATE)?.let { if (it.shown) RsLog.pos(it.position) else "hidden" }} pins=${pinViews.size} planes=${planes.current().size}"

    fun panelDump(): List<String> = rig?.dump() ?: emptyList()

    private companion object {
        const val UI_MASK_MS = 150L
        /** Debug stand-in: the pinching hand rides this far along the stand-in ray unless `hand` places it. */
        const val SYNTH_HAND_M = 0.45f
        const val PLATE_DIST_M = 1.0f
    }
}
