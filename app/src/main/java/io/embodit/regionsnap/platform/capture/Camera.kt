package io.embodit.regionsnap.platform.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.pico.spatial.ml.readback.readbackContentSuspend
import com.pico.spatial.ml.securemr.GlobalTensor
import com.pico.spatial.ml.securemr.Pipeline
import com.pico.spatial.ml.securemr.PipelineTensorPlaceholder
import com.pico.spatial.ml.securemr.SpatialMLInstance
import com.pico.spatial.ml.securemr.SpatialMLSession
import com.pico.spatial.ml.securemr.Tensor
import io.embodit.regionsnap.domain.Intrinsics
import io.embodit.regionsnap.domain.Px
import io.embodit.regionsnap.domain.Rectify
import io.embodit.regionsnap.platform.RsLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What one 拍 brought back. */
sealed class Capture {
    /** A camera frame (or a sample frame), [k] its intrinsics, [quad] the sample's known quad (null for the camera). */
    class Frame(val bitmap: Bitmap, val k: Intrinsics, val atMs: Long, val sample: String?, val quad: List<Px>?, val tookMs: Long) : Capture()
    object NoPermission : Capture()
    /** The session is still being built (S08 "相机还在启动…" past 2 s). */
    object Starting : Capture()
    class Failed(val why: String) : Capture()
}

/** Debug `sample whiteboard|a4|plate|none`: the emulator has no passthrough camera (F5), sample frames stand in. */
enum class SampleFrame(val id: String) { WHITEBOARD("whiteboard"), A4("a4"), PLATE("plate") }

/**
 * Camera frames through SpatialML Readback mode (design capture.pipeline, F1-F3): one session (container DISABLED, so it
 * is not hidden in the Full Space, F6), one pipeline built once: rectifiedVSTAccess (right eye) → RGB→RGBA into a dynamic
 * global tensor (+ the 3×3 K copied into a global tensor); 拍 submits it up to 3 times (≤ 350 ms) and reads each back,
 * keeping the sharpest. Pixels are read only at that moment (rules: no continuous readback, nothing uploaded); every
 * TensorContent is closed by `use { }`.
 *
 * Without the CAMERA permission readback is refused (F2) → [Capture.NoPermission]. On the emulator the camera pipeline is
 * not submitted at all (low-vision: a session that asked for a VST frame there never finishes its destroy and ANRs the app),
 * so a real shot reports [Capture.Failed] (S18) and QA uses [sample].
 */
class Camera(private val context: Context, private val onEmulator: Boolean) {
    private var instance: SpatialMLInstance? = null
    private var session: SpatialMLSession? = null
    private var rgba: GlobalTensor? = null
    private var kTensor: GlobalTensor? = null
    private var pipeline: Pipeline? = null
    private var map: Map<PipelineTensorPlaceholder, GlobalTensor> = emptyMap()
    private var withK = false
    private var lastTask: Pipeline.RunTask? = null
    var w = 640
        private set
    var h = 480
        private set

    /** Session state for the panel and the spike log: "none", "building", "ready", "failed: …". */
    @Volatile var state = "none"
        private set

    /** Debug `sample …` (null = the real camera). */
    @Volatile var sample: SampleFrame? = null

    /** Debug `noPermission camera`: treat CAMERA as not granted. */
    @Volatile var forceNoPermission = false

    /** Frames per shot: 3, or 1 when a single frame took > 150 ms in the spike (R2-9). */
    @Volatile var burst = 3

    fun permitted(): Boolean = !forceNoPermission && context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Builds the session in the background (start-up with permission; R1-10). Safe to call again. */
    suspend fun prepare(width: Int, height: Int): Boolean {
        if (session != null && width == w && height == h) return true
        if (onEmulator) {
            state = "failed: no passthrough camera on the emulator"
            RsLog.i("CAM prepare skipped on the emulator (F5)")
            return false
        }
        close()
        w = width
        h = height
        state = "building"
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val inst = instance ?: SpatialMLInstance.create(context.applicationContext).also { instance = it }
            val ready = withTimeoutOrNull(READY_TIMEOUT_MS) { while (!inst.ready) delay(100); true }
            if (ready != true) {
                state = "failed: SpatialML not ready after ${READY_TIMEOUT_MS / 1000} s"
                return false
            }
            val s = inst.createSession(SpatialMLSession.InitInfo(w, h, 0, 0, 0, SpatialMLSession.ContainerType.DISABLED))
            if (s == null) {
                state = "failed: createSession returned null"
                return false
            }
            session = s
            rgba = s.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.Image.R8G8B8A8_U_DYNAMIC, intArrayOf(h, w)))
            build(s, true) || build(s, false)
            state = if (pipeline != null) "ready" else "failed: pipeline"
            RsLog.i("CAM session ${state} ${w}x$h withK=$withK in ${SystemClock.elapsedRealtime() - t0} ms")
            pipeline != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state = "failed: $e"
            RsLog.e("CAM prepare failed: $e", e)
            false
        }
    }

    private fun build(s: SpatialMLSession, k: Boolean): Boolean =
        runCatching {
            val p = s.newPipeline()
            val m = HashMap<PipelineTensorPlaceholder, GlobalTensor>()
            fun ph(g: GlobalTensor) = p.newPlaceholderLike(g).also { m[it] = g }
            p.run {
                val img = newLocalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.UINT8, intArrayOf(h, w), 3, false))
                if (k) {
                    val kg = s.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32, intArrayOf(3, 3), 1, false))
                    val kl = newLocalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32, intArrayOf(3, 3), 1, false))
                    rectifiedVSTAccess(img, null, null, kl)
                    copy(kl, ph(kg))
                    kTensor = kg
                } else rectifiedVSTAccess(img, null, null, null)
                convertColor(Pipeline.ColorConversion.RGB_TO_RGBA, img, ph(rgba!!))
            }
            pipeline = p
            map = m
            withK = k
            true
        }.onFailure { RsLog.w("CAM pipeline withK=$k failed: $it") }.getOrDefault(false)

    /**
     * 拍 (S08): a sample frame when one is chosen, else up to [burst] camera frames, the sharpest kept. Waits up to 2 s for a
     * session still being built.
     */
    suspend fun shoot(): Capture {
        sample?.let { return loadSample(it) }
        if (!permitted()) return Capture.NoPermission
        if (onEmulator) return Capture.Failed("no passthrough camera on the emulator (F5)")
        val t0 = SystemClock.elapsedRealtime()
        if (pipeline == null) {
            val ok = withTimeoutOrNull(2000) { prepare(w, h) } ?: return Capture.Starting
            if (!ok) return Capture.Failed(state)
        }
        val p = pipeline ?: return Capture.Failed(state)
        val t = rgba ?: return Capture.Failed(state)
        var best: Bitmap? = null
        var bestScore = -1.0
        var k: Intrinsics? = null
        val n = burst.coerceIn(1, 3)
        for (i in 0 until n) {
            val ts = SystemClock.elapsedRealtime()
            try {
                lastTask = p.submit(map, null, lastTask)
                val bmp = t.readbackContentSuspend().use { c -> withContext(Dispatchers.Default) { toBitmap(c.buffer) } } ?: continue
                if (k == null) k = readK()
                val score = withContext(Dispatchers.Default) { sharpness(bmp) }
                RsLog.i(String.format(java.util.Locale.US, "CAM frame %d/%d %dx%d in %d ms sharp=%.0f", i + 1, n, bmp.width, bmp.height, SystemClock.elapsedRealtime() - ts, score))
                if (i == 0 && SystemClock.elapsedRealtime() - ts > 150) burst = 1
                if (score > bestScore) { best = bmp; bestScore = score }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                RsLog.w("CAM readback failed: $e")
                if (!permitted()) return Capture.NoPermission
                return Capture.Failed("readback: $e")
            }
            if (SystemClock.elapsedRealtime() - t0 > 350) break
        }
        val b = best ?: return Capture.Failed("no frame came back")
        return Capture.Frame(b, k ?: Intrinsics.assumed(b.width, b.height), t0, null, null, SystemClock.elapsedRealtime() - t0)
    }

    private suspend fun readK(): Intrinsics? {
        val kt = kTensor ?: return null
        return runCatching {
            kt.readbackContentSuspend().use { c ->
                val fb = c.buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                if (fb.remaining() < 9) return null
                val m = FloatArray(9) { fb.get(it) }
                RsLog.i("CAM K=${m.joinToString(",")}")
                if (m[0] <= 1f || m[4] <= 1f) null else Intrinsics(m[0], m[4], m[2], m[5], w, h)
            }
        }.onFailure { RsLog.w("CAM K readback failed: $it") }.getOrNull()
    }

    private fun toBitmap(buffer: ByteBuffer): Bitmap? {
        val b = buffer.duplicate().apply { rewind() }
        val stride = b.remaining() / h
        if (stride < w * 4) {
            RsLog.w("CAM readback ${b.remaining()} bytes, expected ${w * h * 4}")
            return null
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (stride == w * 4) {
            b.limit(w * h * 4)
            bmp.copyPixelsFromBuffer(b)
        } else {
            val packed = ByteBuffer.allocate(w * h * 4)
            for (row in 0 until h) {
                b.limit(row * stride + w * 4).position(row * stride)
                packed.put(b)
            }
            packed.rewind()
            bmp.copyPixelsFromBuffer(packed)
        }
        return bmp
    }

    private fun sharpness(b: Bitmap): Double {
        val sw = (b.width / 4).coerceAtLeast(3)
        val sh = (b.height / 4).coerceAtLeast(3)
        val small = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val lum = IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10 }
        return Rectify.sharpness(lum, sw, sh)
    }

    /** A sample frame (assets/samples/<id>.jpg + .txt with its known quad TL TR BR BL in pixels). */
    private suspend fun loadSample(s: SampleFrame): Capture =
        withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val bmp = runCatching { context.assets.open("samples/${s.id}.jpg").use { BitmapFactory.decodeStream(it) } }.getOrNull()
                ?: return@withContext Capture.Failed("sample ${s.id} missing")
            val quad = runCatching {
                context.assets.open("samples/${s.id}.txt").bufferedReader().readText().trim().split(Regex("[\\s,]+")).map { it.toFloat() }.chunked(2).map { Px(it[0], it[1]) }
            }.getOrNull()?.takeIf { it.size == 4 }
            RsLog.i("CAM sample ${s.id} ${bmp.width}x${bmp.height} quad=$quad")
            Capture.Frame(bmp, Intrinsics.assumed(bmp.width, bmp.height), t0, s.id, quad, SystemClock.elapsedRealtime() - t0)
        }

    /** Releases the session on its own thread (low-vision: destroy can block; the app never waits for it). */
    fun close() {
        val s = session
        session = null
        pipeline = null
        rgba = null
        kTensor = null
        map = emptyMap()
        lastTask = null
        if (state == "ready" || state == "building") state = "none"
        if (s != null) {
            Thread({ runCatching { s.destructor.destroy() }.onFailure { RsLog.w("CAM destroy: $it") } }, "cam-destroy").apply { isDaemon = true }.start()
            RsLog.i("CAM closed")
        }
    }

    private companion object {
        const val READY_TIMEOUT_MS = 8_000L
    }
}
