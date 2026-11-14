package io.embodit.regionsnap.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import io.embodit.regionsnap.domain.Calibration
import io.embodit.regionsnap.domain.HeadPose
import io.embodit.regionsnap.domain.Intrinsics
import io.embodit.regionsnap.domain.Library
import io.embodit.regionsnap.domain.LibrarySink
import io.embodit.regionsnap.domain.Pin
import io.embodit.regionsnap.domain.Px
import io.embodit.regionsnap.domain.Rectify
import io.embodit.regionsnap.domain.Rigid
import io.embodit.regionsnap.domain.Shot
import io.embodit.regionsnap.domain.SnapEvent
import io.embodit.regionsnap.domain.SnapFrame
import io.embodit.regionsnap.domain.Snapper
import io.embodit.regionsnap.domain.Source
import io.embodit.regionsnap.domain.StrokeJudge
import io.embodit.regionsnap.domain.Surface
import io.embodit.regionsnap.domain.pinInFront
import io.embodit.regionsnap.platform.RsLog
import io.embodit.regionsnap.platform.Sound
import io.embodit.regionsnap.platform.Store
import io.embodit.regionsnap.platform.capture.Camera
import io.embodit.regionsnap.platform.capture.Capture
import io.embodit.regionsnap.platform.capture.Gallery
import io.embodit.regionsnap.platform.input.Device
import io.embodit.regionsnap.platform.sense.Anchors
import io.embodit.regionsnap.ui.window.WindowCtl
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the user (or the debug receiver, the same path) does on the panels and the Stage's small panels. */
sealed class Ui {
    data class SelectTab(val tab: Tab) : Ui()
    object OnboardStart : Ui()
    object SameAsLast : Ui()
    object Redraw : Ui()
    object Shoot : Ui()
    /** [左转] (+1) / [右转] (-1): 15° about the vertical axis (v0.1.4). */
    data class Rotate(val dir: Int) : Ui()

    /** [回正]: the frame faces the user again. */
    object FaceMe : Ui()
    data class Push(val delta: Float) : Ui()
    data class PreviewRectify(val on: Boolean) : Ui()
    data class PreviewCorner(val index: Int, val at: Px) : Ui()
    data class PreviewSave(val pin: Boolean) : Ui()
    object PreviewRetake : Ui()
    object CamAllow : Ui()
    object CamNotNow : Ui()
    object CamRetry : Ui()
    object CamDismiss : Ui()
    object OpenSettings : Ui()
    data class OpenShot(val id: String) : Ui()
    object CloseShot : Ui()
    data class PinBack(val id: String) : Ui()
    data class PinFront(val id: String) : Ui()
    data class Unpin(val id: String) : Ui()
    object DeleteAsk : Ui()
    object DeleteCancel : Ui()
    object DeleteYes : Ui()
    data class SetPrefs(val why: String, val f: (Prefs) -> Prefs) : Ui()
    object EyeSettings : Ui()
    object CalibStart : Ui()
    object CalibOk : Ui()
    object CalibRetake : Ui()
    object CalibDegraded : Ui()
    object CalibCancel : Ui()
    object CalibClear : Ui()
    object Recall : Ui()
    object About : Ui()
    object QuitAsk : Ui()
    object QuitCancel : Ui()
    object QuitYes : Ui()
    data class Background(val on: Boolean) : Ui()
    data class PinBarOpen(val id: String) : Ui()
    object PinBarClose : Ui()
    object OpenPanel : Ui()

    /** q2-1: put the window away so the spot behind it can be framed ([Move aside], or a tap on its empty area). */
    data class MoveAside(val why: String) : Ui()
}

/**
 * The state machine (design states S00-S24). The scene's frame system steps [snapper] with the live input and reports its
 * [SnapEvent]s through [onSnap]; panels and the debug receiver send [Ui] events to [onEvent]. Main thread only.
 */
object RsController {
    private val _state = MutableStateFlow(RsUiState())
    val state: StateFlow<RsUiState> = _state

    val snapper = Snapper()
    var activity: Activity? = null
    private var appContext: Context? = null
    private var store: Store? = null
    var sound: Sound? = null
        private set
    var camera: Camera? = null
        private set
    var gallery: Gallery? = null
        private set
    val anchors = Anchors()
    lateinit var library: Library
        private set
    private val main = Handler(Looper.getMainLooper())
    private val scope: CoroutineScope = MainScope()
    private var started = false
    private var shootJob: Job? = null

    /** Bumped whenever the pins change (the scene rebuilds the note entities). */
    @Volatile var pinsEpoch = 0
        private set

    /** The scene: HMD pose at a time (pose ring buffer), the current head, haptics. */
    var poseAt: ((Long) -> Rigid?)? = null
    var headNow: (() -> HeadPose?)? = null
    var haptic: ((String) -> Unit)? = null
    var quitHook: (() -> Unit)? = null

    /** Preview bitmaps (S09): source frame, the quad, the result. */
    var previewSrc: Bitmap? = null
        private set
    var previewOut: Bitmap? = null
        private set
    var previewQuad: List<Px> = emptyList()
        private set
    private var previewFrame: SnapFrame? = null
    private var previewK: Intrinsics? = null
    private var previewHmd: Rigid? = null
    private var hintUntil = 0L
    /** Debug `bar long`: hints stay until replaced or no longer true (D36, q1-4). */
    var hintHold = false

    /** Debug `hold shrink`: S05 stays on its middle frame until `hold off` (q1-5). */
    var holdShrink = false
        set(v) {
            field = v
            if (!v && _state.value.phase == Phase.SHRINK) framed("shrink released")
        }

    fun start(context: Context) {
        activity = findActivity(context) ?: activity
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        val st = Store(app)
        store = st
        val g = Gallery(app)
        gallery = g
        camera = Camera(app, Device.onEmulator)
        library = Library(object : LibrarySink {
            override fun removeAnchor(uuid: String) { scope.launch { anchors.remove(uuid) } }
            override fun removeEntity(shotId: String) { pinsEpoch++ }
            override fun deletePrivate(shotId: String) = g.deletePrivate(shotId)
            override fun deleteMedia(uri: String) = g.deleteMedia(uri)
            override fun persist(shots: List<Shot>, pins: List<Pin>) = st.persist(shots, pins)
        })
        val prefs = st.prefs()
        if (prefs.lastW > 0f) snapper.setLast(prefs.lastW, prefs.lastH, prefs.lastFloating)
        snapper.stick = prefs.stick
        snapper.pinchMode = prefs.pinchMode
        sound = Sound(app).also { it.set(prefs.muted, prefs.shutterSound); it.start() }
        library.load(st.loadShots(), st.loadPins())
        _state.value = RsUiState(prefs = prefs, onboarding = !prefs.onboarded)
        syncLibrary()
        RsLog.i("CONTROLLER start state=${_state.value.designState} shots=${library.shots.size} pins=${library.pins.size} prefs=$prefs")
        scope.launch(Dispatchers.IO) {
            g.clearPending()
            val missing = g.missing(library.shots.mapNotNull { s -> s.mediaStoreUri?.let { s.id to it } }.toMap())
            withContext(Dispatchers.Main) { if (missing.isNotEmpty()) { library.markMissing(missing); syncLibrary() } }
        }
        // eye tracking is a runtime permission (F7): ask once at start; refused → finger / head fallback with a hint (S17)
        // (not on the emulator: it has no eyes to track and draws the system dialog as a black slab over the room)
        if (!Device.onEmulator) activity?.let { a ->
            if (a.checkSelfPermission(EYE_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                RsLog.i("PERM ask eye tracking")
                runCatching { a.requestPermissions(arrayOf(EYE_PERMISSION), 42) }.onFailure { RsLog.w("PERM eye ask failed: $it") }
            }
        }
        // the camera session is built in the background when the permission is already there (R1-10)
        if (camera!!.permitted()) scope.launch { camera?.prepare(resW(prefs), resH(prefs)); set("cam") { it.copy(camState = camera?.state ?: "none") } }
    }

    private fun resW(p: Prefs) = if (p.hiRes) 1280 else 640

    private fun resH(p: Prefs) = if (p.hiRes) 960 else 480

    private fun findActivity(c: Context): Activity? {
        var x: Context? = c
        while (x != null) {
            if (x is Activity) return x
            x = (x as? ContextWrapper)?.baseContext
        }
        return null
    }

    fun set(why: String, f: (RsUiState) -> RsUiState) {
        val before = _state.value
        val after = f(before).let { s -> if (s.hint != Hint.NONE && !s.hintStillTrue()) s.copy(hint = Hint.NONE) else s }
        if (after == before) return
        if (before.hint != Hint.NONE && after.hint == Hint.NONE && before.hint != after.hint) RsLog.i("HINT off ${before.hint} why=$why")
        _state.value = after
        if (after.designState != before.designState || after.tab != before.tab || after.phase != before.phase) {
            RsLog.i("STATE ${after.designState} tab=${after.tab} phase=${after.phase} card=${after.card} why=$why")
        }
    }

    private fun syncLibrary() {
        val lib = library
        set("library") { it.copy(shots = lib.shots, pinned = lib.pins.map { p -> p.shotId }.toSet(), lostPins = lib.pins.filter { p -> !p.restored }.map { p -> p.shotId }.toSet()) }
        pinsEpoch++
    }

    /** Shows a hint for 3 s (or until replaced while [hintHold]); one at a time (R2-1). */
    fun hint(h: Hint, n: Int = 0) {
        if (_state.value.hint == h && _state.value.hintN == n && SystemClock.elapsedRealtime() < hintUntil) return
        RsLog.i("HINT $h n=$n")
        hintUntil = SystemClock.elapsedRealtime() + HINT_MS
        set("hint") { it.copy(hint = h, hintN = n, hintSeq = it.hintSeq + 1) }
        val seq = _state.value.hintSeq
        main.postDelayed({ if (!hintHold && _state.value.hintSeq == seq && _state.value.hint != Hint.LOOK_LEVEL) set("hint off") { it.copy(hint = Hint.NONE) } }, HINT_MS)
    }

    // ============================================================================================ from the scene

    /** The engine's events of one frame (the frame system calls this on the main thread). */
    fun onSnap(events: List<SnapEvent>) {
        for (e in events) when (e) {
            SnapEvent.PressEdge -> Unit
            SnapEvent.DrawStart -> { set("draw") { it.copy(phase = Phase.DRAWING, onboarding = false) }; haptic?.invoke("press") }
            is SnapEvent.Framed -> {
                val f = snapper.frame
                RsLog.i("FRAME ${e.kind} ${f?.let { "${it.sizeLabel()} ${it.surface} roll=${String.format(Locale.US, "%.1f", it.rollDeg)}" }}")
                if (e.kind == StrokeJudge.Kind.LOOP || e.kind == StrokeJudge.Kind.LINE) {
                    set("shrink") { it.copy(phase = Phase.SHRINK) }
                    main.postDelayed({ if (_state.value.phase == Phase.SHRINK && !holdShrink) framed("shrink done") }, SHRINK_MS)
                } else framed("framed")
                f?.let { fr -> updatePrefs("last") { it.copy(lastW = fr.w, lastH = fr.h, lastFloating = fr.floating) } }
            }
            SnapEvent.TooSmall -> {
                hint(Hint.TOO_SMALL)
                set("too small") { it.copy(phase = if (snapper.frame != null) Phase.FRAMED else Phase.IDLE) }
            }
            SnapEvent.Shoot -> onEvent(Ui.Shoot)
            SnapEvent.Cleared -> onEvent(Ui.Redraw)
            is SnapEvent.Grab -> { set("adjust") { it.copy(phase = Phase.ADJUSTING) }; sound?.tick(); haptic?.invoke("press") }
            SnapEvent.Released -> framed("adjusted")
            SnapEvent.Leveled -> { sound?.tick(); haptic?.invoke("level"); frameFacts() }
            SnapEvent.Rotated -> frameFacts()
            is SnapEvent.PinClick -> library.pins.getOrNull(e.index)?.let { onEvent(Ui.PinBarOpen(it.shotId)) }
            is SnapEvent.PinMoved -> Unit
            is SnapEvent.PinDropped -> library.pins.getOrNull(e.index)?.let { p -> movePin(p.shotId, e.frame) }
        }
    }

    private fun framed(why: String) {
        set(why) { it.copy(phase = Phase.FRAMED, onboarding = false) }
        frameFacts()
    }

    /** The frame's size / surface for the panel (low frequency: on change only). */
    fun frameFacts() {
        val f = snapper.frame
        set("facts") {
            it.copy(
                hasFrame = f != null,
                frameW = f?.w ?: 0f,
                frameH = f?.h ?: 0f,
                frameSurface = f?.surface ?: Surface.FLOATING,
                frameDepth = f?.depth ?: 0f,
                phase = if (f == null && it.phase == Phase.FRAMED) Phase.IDLE else it.phase,
            )
        }
    }

    /** Aim / input facts (the scene calls this only when they change). */
    fun onAim(input: InputMode, source: Source, surface: Surface?, depth: Float) {
        set("aim") {
            val ph = when {
                it.phase == Phase.IDLE && surface != null -> Phase.AIMING
                it.phase == Phase.AIMING && surface == null -> Phase.IDLE
                else -> it.phase
            }
            it.copy(input = input, source = source, aimSurface = surface, aimDepth = depth, phase = ph)
        }
    }

    // ============================================================================================ events

    fun onEvent(e: Ui) {
        val s = _state.value
        when (e) {
            is Ui.SelectTab -> set("tab") { it.copy(tab = e.tab, detail = null, deleteConfirm = false, exitConfirm = false, aboutOpen = false) }
            Ui.OnboardStart -> { set("onboard") { it.copy(onboarding = false) }; updatePrefs("onboarded") { it.copy(onboarded = true) } }
            Ui.SameAsLast -> if (snapper.sameAsLast()) framed("same as last")
            Ui.Redraw -> {
                snapper.clear()
                set("redraw") { it.copy(phase = Phase.IDLE, card = Card.NONE, preview = null) }
                frameFacts()
            }
            Ui.Shoot -> shoot()
            is Ui.Rotate -> { if (snapper.yawStep(e.dir, headNow?.invoke()?.position)) { sound?.tick(); haptic?.invoke("level") }; RsLog.i("FRAME yaw step ${e.dir}"); frameFacts() }
            Ui.FaceMe -> { if (snapper.faceUser(headNow?.invoke()?.position)) { sound?.tick(); haptic?.invoke("level") }; RsLog.i("FRAME face user"); frameFacts() }
            is Ui.Push -> { snapper.pushStep(e.delta); frameFacts() }
            is Ui.PreviewRectify -> { set("rectify") { it.copy(preview = it.preview?.copy(rectified = e.on)) }; rerender() }
            is Ui.PreviewCorner -> if (e.index in 0..3 && previewQuad.size == 4) {
                previewQuad = previewQuad.toMutableList().also { it[e.index] = e.at }
                rerender()
            }
            is Ui.PreviewSave -> save(e.pin)
            Ui.PreviewRetake -> {
                dropPreview()
                set("retake") { it.copy(phase = if (snapper.frame != null) Phase.FRAMED else Phase.IDLE, preview = null) }
            }
            Ui.CamAllow -> askCamera()
            Ui.CamNotNow -> set("cam not now") { it.copy(card = Card.NONE, phase = if (snapper.frame != null) Phase.FRAMED else Phase.IDLE) }
            Ui.CamRetry -> { set("cam retry") { it.copy(card = Card.NONE) }; camera?.close(); shoot() }
            Ui.CamDismiss -> set("cam ok") { it.copy(card = Card.NONE, phase = if (snapper.frame != null) Phase.FRAMED else Phase.IDLE) }
            Ui.OpenSettings -> openAppSettings()
            is Ui.OpenShot -> set("detail") { it.copy(tab = Tab.SHOTS, detail = e.id, deleteConfirm = false, pinBar = null) }
            Ui.CloseShot -> set("detail close") { it.copy(detail = null, deleteConfirm = false) }
            is Ui.PinBack -> library.shot(e.id)?.let { pin(it.id, it.frame) }
            is Ui.PinFront -> library.shot(e.id)?.let { sh -> headNow?.invoke()?.let { h -> pin(sh.id, pinInFront(h, sh.frame.w, sh.frame.h)) } }
            is Ui.Unpin -> { library.unpin(e.id); sound?.tack(); syncLibrary(); set("unpin") { it.copy(pinBar = null) } }
            Ui.DeleteAsk -> set("delete ask") { it.copy(deleteConfirm = true) }
            Ui.DeleteCancel -> set("delete cancel") { it.copy(deleteConfirm = false) }
            Ui.DeleteYes -> s.detail?.let { id ->
                library.delete(id)
                RsLog.i("SAVE deleted $id")
                syncLibrary()
                set("deleted") { it.copy(detail = null, deleteConfirm = false) }
            }
            is Ui.SetPrefs -> updatePrefs(e.why, e.f)
            Ui.EyeSettings -> openAppSettings()
            Ui.CalibStart -> set("calib") { it.copy(tab = Tab.SETTINGS, calib = CalibUi(), exitConfirm = false, card = Card.NONE, hint = Hint.NONE) }
            Ui.CalibCancel -> { dropPreview(); set("calib cancel") { it.copy(calib = null, preview = null, card = Card.NONE) } }
            Ui.CalibRetake -> { dropPreview(); set("calib retake") { it.copy(calib = it.calib?.copy(shot = false), preview = null) } }
            Ui.CalibOk -> calibrate(false)
            Ui.CalibDegraded -> calibrate(true)
            Ui.CalibClear -> updatePrefs("calib clear") { it.copy(calibration = null) }
            Ui.Recall -> { RsLog.i("PANEL recall"); WindowCtl.recall("settings") }
            Ui.About -> set("about") { it.copy(aboutOpen = !it.aboutOpen) }
            Ui.QuitAsk -> set("quit ask") { it.copy(exitConfirm = true) }
            Ui.QuitCancel -> set("quit cancel") { it.copy(exitConfirm = false) }
            Ui.QuitYes -> quit()
            is Ui.Background -> background(e.on)
            is Ui.PinBarOpen -> set("pin bar") { it.copy(pinBar = e.id) }
            Ui.PinBarClose -> set("pin bar close") { it.copy(pinBar = null) }
            Ui.OpenPanel -> { RsLog.i("PANEL reopen"); WindowCtl.reopen("stage") }
            is Ui.MoveAside -> WindowCtl.tuck(e.why)
        }
    }

    private fun updatePrefs(why: String, f: (Prefs) -> Prefs) {
        val before = _state.value.prefs
        val p = f(before)
        if (p == before) return
        set("prefs $why") { it.copy(prefs = p) }
        store?.savePrefs(p)
        sound?.set(p.muted, p.shutterSound)
        snapper.stick = p.stick
        snapper.pinchMode = p.pinchMode
        if (p.hiRes != before.hiRes && camera?.permitted() == true) scope.launch { camera?.prepare(resW(p), resH(p)) }
        if (why != "last") RsLog.i("PREFS $why $p")
    }

    // ============================================================================================ shoot (S08 → S09)

    private fun shoot() {
        val s = _state.value
        val f = snapper.frame ?: return
        if (s.phase == Phase.CAPTURING) return
        val calib = s.calib
        if (calib != null) {
            // S20 preconditions (R2-5): on a detected plane, short side ≥ 15 cm, ≤ 1.5 m away
            val head = headNow?.invoke()
            val far = head?.let { f.center.distanceTo(it.position) > 1.5f } ?: false
            if (f.floating || minOf(f.w, f.h) < 0.15f || far) {
                hint(Hint.CALIB_PRE, if (f.floating) 0 else if (far) 1 else 2)
                return
            }
        }
        val cam = camera ?: return
        RsLog.i("SHOOT frame=${f.sizeLabel()} surface=${f.surface} sample=${cam.sample?.id}")
        sound?.shutter()
        haptic?.invoke("shoot")
        set("shoot") { it.copy(phase = Phase.CAPTURING, card = Card.NONE, flashSeq = it.flashSeq + 1) }
        shootJob?.cancel()
        shootJob = scope.launch {
            val r = cam.shoot()
            set("cam") { it.copy(camState = cam.state) }
            when (r) {
                is Capture.Frame -> preview(r, f)
                Capture.NoPermission -> {
                    RsLog.i("PERM camera not granted")
                    set("no camera") { it.copy(phase = Phase.FRAMED, card = Card.CAMERA_PERMISSION) }
                }
                Capture.Starting -> {
                    hint(Hint.CAMERA_STARTING)
                    set("cam starting") { it.copy(phase = Phase.FRAMED, card = Card.CAMERA_STARTING) }
                }
                is Capture.Failed -> {
                    RsLog.w("CAM failed: ${r.why}")
                    set("cam failed") { it.copy(phase = Phase.FRAMED, card = Card.CAMERA_FAILED) }
                }
            }
        }
    }

    private suspend fun preview(r: Capture.Frame, f: SnapFrame) {
        val hmd = poseAt?.invoke(r.atMs) ?: headNow?.invoke()?.let { Rigid(it.position, it.rotation) }
        val tcam = _state.value.prefs.calibration?.tcam ?: Rectify.DEFAULT_TCAM
        val projected = hmd?.let { Rectify.quad(f, it * tcam, r.k) }
        val q = r.quad ?: projected
        RsLog.i("PROJ quad=${q?.joinToString(" ") { String.format(Locale.US, "(%.0f,%.0f)", it.x, it.y) }} from=${if (r.quad != null) "sample" else "projection"} k=${r.k}")
        if (q == null || q.any { !it.x.isFinite() || !it.y.isFinite() }) {
            set("proj failed") { it.copy(phase = Phase.FRAMED, card = Card.CAMERA_FAILED) }
            return
        }
        // keep the corners inside the picture so they can be grabbed
        val clamped = q.map { Px(it.x.coerceIn(0f, r.bitmap.width.toFloat()), it.y.coerceIn(0f, r.bitmap.height.toFloat())) }
        previewSrc = r.bitmap
        previewQuad = clamped
        previewFrame = f
        previewK = r.k
        previewHmd = hmd
        withContext(Dispatchers.IO) { gallery?.keepPreview(r.bitmap) }
        val seq = (_state.value.preview?.seq ?: 0) + 1
        set("preview") {
            it.copy(
                phase = Phase.PREVIEW, card = Card.NONE, calib = it.calib?.copy(shot = true),
                preview = PreviewUi(seq, true, 0, 0, if (f.floating) null else f.w, if (f.floating) null else f.h, f.surface, f.depth, false, r.sample, 0),
            )
        }
        if (!_state.value.panelOpen) WindowCtl.reopen("preview")
        rerender()
    }

    private var renderJob: Job? = null

    /** Recomputes the straightened result (≤ 200 ms, T7) off the main thread. */
    private fun rerender() {
        val src = previewSrc ?: return
        val f = previewFrame ?: return
        val q = previewQuad
        val pv = _state.value.preview ?: return
        val g = gallery ?: return
        renderJob?.cancel()
        renderJob = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val out = withContext(Dispatchers.Default) { g.render(src, q, f.aspect, pv.rectified) }
            val ms = SystemClock.elapsedRealtime() - t0
            previewOut = out
            RsLog.i("RECT ${out.width}x${out.height} rectified=${pv.rectified} in $ms ms")
            set("rendered") { it.copy(preview = it.preview?.copy(seq = it.preview.seq + 1, pxW = out.width, pxH = out.height, lowRes = minOf(out.width, out.height) < 400, renderMs = ms)) }
            if (minOf(out.width, out.height) < 400) hint(Hint.LOW_RES)
        }
    }

    private fun dropPreview() {
        previewSrc = null
        previewOut = null
        previewQuad = emptyList()
        scope.launch(Dispatchers.IO) { gallery?.dropPreview() }
    }

    private fun save(pinIt: Boolean) {
        val out = previewOut ?: return
        val pv = _state.value.preview ?: return
        val f = previewFrame ?: return
        val g = gallery ?: return
        val id = "rs" + System.currentTimeMillis()
        val desc = if (f.floating) String.format(Locale.US, "浮空 · 约 %.1f m", f.depth) else f.sizeLabel()
        scope.launch {
            val uri = withContext(Dispatchers.IO) { g.save(id, out, desc) }
            val shot = Shot(id, System.currentTimeMillis(), out.width, out.height, if (f.floating) null else f.w, if (f.floating) null else f.h, f.surface, if (f.floating) f.depth else null, pv.rectified, uri?.toString(), uri == null, f)
            library.add(shot)
            RsLog.i("SAVE shot $id ${out.width}x${out.height} uri=$uri pin=$pinIt")
            dropPreview()
            syncLibrary()
            set("saved") { it.copy(phase = Phase.SAVED, preview = null) }
            hint(Hint.SAVED)
            if (pinIt) {
                pin(id, f)
                snapper.clear()
                frameFacts()
            }
            main.postDelayed({ if (_state.value.phase == Phase.SAVED || _state.value.phase == Phase.PINNED) set("saved done") { it.copy(phase = if (snapper.frame != null) Phase.FRAMED else Phase.IDLE) } }, HINT_MS)
        }
    }

    /** Pins [id] at [f] (S11): the note shows at once; the anchor follows (failure → the note stays for this session). */
    private fun pin(id: String, f: SnapFrame) {
        if (!library.isPinned(id) && !library.canPinMore) {
            hint(Hint.PIN_LIMIT)
            return
        }
        library.pin(id, null, f)
        sound?.tack()
        syncLibrary()
        set("pinned") { it.copy(phase = Phase.PINNED, pinBar = null) }
        RsLog.i("PIN up $id at ${RsLog.pos(f.center)} ${f.sizeLabel()}")
        scope.launch {
            val uuid = anchors.create(f.center, "rs-$id")
            if (uuid == null) {
                hint(Hint.PIN_NOT_KEPT)
                RsLog.i("ANCHOR none for $id: the note stays for this session")
            } else if (library.isPinned(id)) {
                library.movePin(id, library.pinOf(id)!!.frame, uuid)
                RsLog.i("ANCHOR $uuid for $id")
            } else anchors.remove(uuid)
        }
    }

    private fun movePin(id: String, f: SnapFrame) {
        val old = library.pinOf(id) ?: return
        library.movePin(id, f, old.anchorUuid)
        pinsEpoch++
        RsLog.i("PIN moved $id to ${RsLog.pos(f.center)}")
        // re-anchor where it now hangs (design pin.interact: 松手停在原处并更新锚点)
        scope.launch {
            val uuid = anchors.create(f.center, "rs-$id") ?: return@launch
            if (library.isPinned(id)) library.movePin(id, library.pinOf(id)!!.frame, uuid) else anchors.remove(uuid)
        }
    }

    /** At start: put pinned notes back on their anchors (S12), or mark them lost (S19). */
    fun restorePins() {
        if (library.pins.isEmpty()) return
        scope.launch {
            val found = anchors.loadAll()
            val lost = HashSet<String>()
            for (p in library.pins) {
                val at = p.anchorUuid?.let { found?.get(it) }
                if (at == null) lost += p.shotId else if (at.distanceTo(p.frame.center) > 0.005f) library.movePin(p.shotId, p.frame.copy(center = at), p.anchorUuid)
            }
            library.markLost(lost)
            RsLog.i("ANCHOR restore found=${library.pins.size - lost.size} lost=${lost.size} load=${if (found == null) "failed" else "ok"}")
            syncLibrary()
            if (lost.isNotEmpty()) hint(Hint.ANCHORS_LOST, lost.size)
        }
    }

    // ============================================================================================ permissions

    private fun askCamera() {
        val a = activity
        val cam = camera
        if (cam?.forceNoPermission == true) {
            // debug noPermission: the button acts as if the system said no
            set("cam denied (debug)") { it.copy(card = Card.CAMERA_DENIED_FOREVER) }
            return
        }
        if (a == null) return openAppSettings()
        if (a.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            set("cam ok") { it.copy(card = Card.NONE) }
            shoot()
            return
        }
        RsLog.i("PERM ask camera")
        permissionAsked = true
        a.requestPermissions(arrayOf(Manifest.permission.CAMERA), 41)
        main.postDelayed(permissionPoll, 500)
    }

    private var permissionAsked = false

    /** No result callback from a SpatialLaunchActivity we do not own: poll until granted (or the card is left). */
    private val permissionPoll = object : Runnable {
        override fun run() {
            val c = camera ?: return
            if (c.permitted()) {
                RsLog.i("PERM camera granted")
                set("cam granted") { it.copy(card = Card.NONE) }
                scope.launch { c.prepare(resW(_state.value.prefs), resH(_state.value.prefs)); shoot() }
                return
            }
            if (_state.value.card == Card.CAMERA_PERMISSION) {
                val forever = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false && permissionAsked
                if (forever && !_state.value.background) set("cam denied") { it.copy(card = Card.CAMERA_DENIED_FOREVER) }
                main.postDelayed(this, 700)
            }
        }
    }

    private fun openAppSettings() {
        val ctx = activity ?: appContext ?: return
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { RsLog.w("PERM settings failed: $it") }
    }

    // ============================================================================================ calibrate (S20)

    private fun calibrate(degraded: Boolean) {
        val f = previewFrame ?: return
        val k = previewK ?: return
        val hmd = previewHmd ?: return
        val c = Rectify.calibrate(f, previewQuad, k, hmd)
        val tries = (_state.value.calib?.tries ?: 0) + 1
        RsLog.i("CALIB solve err=${c?.rmsPx} dist=${c?.distM} tries=$tries degraded=$degraded")
        if (c == null || c.rmsPx > Calibration.MAX_ERR_PX && !degraded) {
            hint(Hint.CALIB_RETRY)
            set("calib unstable") { it.copy(calib = CalibUi(tries, shot = true, unstable = true)) }
            return
        }
        val keep = if (c.rmsPx > Calibration.MAX_ERR_PX) c.degrade() else c
        updatePrefs("calibrated") { it.copy(calibration = keep) }
        dropPreview()
        set("calib done") { it.copy(calib = null, preview = null, phase = if (snapper.frame != null) Phase.FRAMED else Phase.IDLE) }
    }

    // ============================================================================================ lifecycle

    fun onPanel(open: Boolean) {
        set("panel") { it.copy(panelOpen = open, panelEverOpened = it.panelEverOpened || open) }
    }

    /** q1-1: the window was put away because it covered the frame (or opened again). */
    fun onTucked(on: Boolean) {
        set(if (on) "panel tucked" else "panel untucked") { it.copy(panelTucked = on) }
    }

    private fun background(on: Boolean) {
        if (on) {
            // S23: shooting stops, the frame stays, providers stop (scene); a system permission dialog is not an interruption
            if (_state.value.phase == Phase.CAPTURING) {
                shootJob?.cancel()
                set("interrupt") { it.copy(phase = if (snapper.frame != null) Phase.FRAMED else Phase.IDLE) }
            }
            set("background") { it.copy(background = true) }
        } else {
            set("foreground") { it.copy(background = false) }
            // back from the preview: the source frame comes from cache/preview.jpg when the process kept nothing (R1-7)
            if (_state.value.phase == Phase.PREVIEW && previewSrc == null) scope.launch {
                previewSrc = withContext(Dispatchers.IO) { gallery?.loadPreview() }
                rerender()
            }
        }
    }

    private fun quit() {
        RsLog.i("EXIT confirmed: release + close window + closeStage + finishAndRemoveTask + exit")
        shootJob?.cancel()
        quitHook?.invoke()
        camera?.close()
        runCatching { sound?.release() }
        sound = null
        WindowCtl.closeForQuit()
        val nav = WindowCtl.navigator
        val a = activity
        scope.launch {
            runCatching { nav?.closeStage() }.onFailure { RsLog.w("EXIT closeStage: $it") }
            runCatching {
                val am = a?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.appTasks?.forEach { runCatching { it.finishAndRemoveTask() } }
                a?.finishAndRemoveTask()
            }.onFailure { RsLog.w("EXIT finishAndRemoveTask: $it") }
            main.postDelayed({ kotlin.system.exitProcess(0) }, 400)
        }
    }

    /** One line for debug `dump` (design debugHooks.dump). */
    fun dump(): String {
        val s = _state.value
        val f = snapper.frame
        return buildString {
            append("DUMP state=${s.designState} tab=${s.tab} phase=${s.phase} card=${s.card} input=${s.input} source=${s.source}")
            append(" frame=${f?.let { "${it.sizeLabel()} ${it.surface} roll=${String.format(Locale.US, "%.1f", it.rollDeg)} corners=${it.corners().joinToString(" ") { c -> RsLog.pos(c) }}" } ?: "none"}")
            append(" aim=${snapper.aim?.let { "${it.surface} ${RsLog.pos(it.point)} d=${String.format(Locale.US, "%.2f", it.distance)}" }}")
            append(" quad=${previewQuad.joinToString(" ") { String.format(Locale.US, "(%.0f,%.0f)", it.x, it.y) }}")
            append(" out=${previewOut?.let { "${it.width}x${it.height}" }} calib=${s.prefs.calibration} cam=${camera?.state} sample=${camera?.sample?.id}")
            append(" shots=${library.shots.size} pins=${library.pins.joinToString { "${it.shotId}:${it.anchorUuid ?: "-"}:${if (it.restored) "on" else "lost"}" }} panel=${s.panelOpen}/${WindowCtl.panelState} tucked=${s.panelTucked}")
        }
    }

    private const val EYE_PERMISSION = "com.picovr.permission.EYE_TRACKING"
    private const val HINT_MS = 3000L
    private const val SHRINK_MS = 300L
}
