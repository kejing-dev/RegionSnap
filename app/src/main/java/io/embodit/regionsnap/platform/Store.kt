package io.embodit.regionsnap.platform

import android.content.Context
import android.content.SharedPreferences
import io.embodit.regionsnap.domain.Calibration
import io.embodit.regionsnap.domain.OutRes
import io.embodit.regionsnap.domain.Pin
import io.embodit.regionsnap.domain.Quat4
import io.embodit.regionsnap.domain.Rigid
import io.embodit.regionsnap.domain.Shot
import io.embodit.regionsnap.domain.SnapFrame
import io.embodit.regionsnap.domain.Snapper
import io.embodit.regionsnap.domain.Surface
import io.embodit.regionsnap.domain.Vec3
import io.embodit.regionsnap.ui.Lang
import io.embodit.regionsnap.ui.Prefs
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local storage (design persistence): settings and the calibration in SharedPreferences (this device), the shots index
 * files/shots/<id>.json, and files/pins.json — the only place that says what is pinned (R1-6). No network.
 */
class Store(private val context: Context) {
    private val p = context.getSharedPreferences("region_snap", Context.MODE_PRIVATE)

    fun prefs(): Prefs = readPrefs(p)

    fun savePrefs(v: Prefs) = writePrefs(p.edit(), v).apply()

    // ------------------------------------------------------------------------------------------ shots / pins

    private val shotsDir: File
        get() = File(context.filesDir, "shots").also { it.mkdirs() }

    private val pinsFile: File
        get() = File(context.filesDir, "pins.json")

    fun loadShots(): List<Shot> =
        shotsDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f -> runCatching { shot(JSONObject(f.readText())) }.onFailure { RsLog.w("SAVE bad ${f.name}: $it") }.getOrNull() }

    fun loadPins(): List<Pin> =
        if (!pinsFile.exists()) emptyList()
        else runCatching {
            val a = JSONArray(pinsFile.readText())
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Pin(o.getString("shotId"), o.optString("anchor").ifEmpty { null }, frame(o.getJSONObject("frame"))) } }
        }.getOrDefault(emptyList())

    fun persist(shots: List<Shot>, pins: List<Pin>) {
        val keep = shots.map { it.id }.toSet()
        for (s in shots) File(shotsDir, "${s.id}.json").writeText(shotJson(s).toString())
        shotsDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().filter { it.nameWithoutExtension !in keep }.forEach { it.delete() }
        val a = JSONArray()
        for (pin in pins) a.put(JSONObject().put("shotId", pin.shotId).put("anchor", pin.anchorUuid ?: "").put("frame", frameJson(pin.frame)))
        pinsFile.writeText(a.toString())
    }

    companion object {
        /** Settings in and out of SharedPreferences (pure, so the unit tests can check what is remembered, v0.1.5). */
        fun readPrefs(p: SharedPreferences): Prefs {
            val d = Prefs()
            return Prefs(
                lang = runCatching { Lang.valueOf(p.getString("lang", d.lang.name)!!) }.getOrDefault(d.lang),
                outRes = OutRes.parse(p.getString("outRes", d.outRes.name)),
                shutterSound = p.getBoolean("shutter", d.shutterSound),
                muted = p.getBoolean("muted", d.muted),
                pinchMode = runCatching { Snapper.PinchMode.valueOf(p.getString("pinch", d.pinchMode.name)!!) }.getOrDefault(d.pinchMode),
                stick = p.getBoolean("stick", d.stick),
                onboarded = p.getBoolean("onboarded", d.onboarded),
                calibration = p.getString("calib", null)?.let { runCatching { calib(JSONObject(it)) }.getOrNull() },
                lastW = p.getFloat("lastW", 0f),
                lastH = p.getFloat("lastH", 0f),
                lastFloating = p.getBoolean("lastF", false),
            )
        }

        fun writePrefs(e: SharedPreferences.Editor, v: Prefs): SharedPreferences.Editor =
            e.putString("lang", v.lang.name).putString("outRes", v.outRes.name).putBoolean("shutter", v.shutterSound).putBoolean("muted", v.muted)
                .putString("pinch", v.pinchMode.name).putBoolean("stick", v.stick).putBoolean("onboarded", v.onboarded)
                .putString("calib", v.calibration?.let { calibJson(it).toString() })
                .putFloat("lastW", v.lastW).putFloat("lastH", v.lastH).putBoolean("lastF", v.lastFloating)

        fun v(o: JSONObject, k: String) = o.getJSONArray(k).let { Vec3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }

        fun arr(v: Vec3) = JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.z.toDouble())

        fun frameJson(f: SnapFrame): JSONObject =
            JSONObject().put("c", arr(f.center)).put("n", arr(f.normal)).put("up", arr(f.baseUp)).put("roll", f.roll.toDouble())
                .put("w", f.w.toDouble()).put("h", f.h.toDouble()).put("surface", f.surface.name).put("depth", f.depth.toDouble())
                // the 4 world corners, for QA (design dataModel.shot.cornersWorld)
                .put("corners", JSONArray().apply { f.corners().forEach { put(arr(it)) } })

        fun frame(o: JSONObject) =
            SnapFrame(v(o, "c"), v(o, "n"), v(o, "up"), o.getDouble("roll").toFloat(), o.getDouble("w").toFloat(), o.getDouble("h").toFloat(), Surface.valueOf(o.getString("surface")), o.optDouble("depth", 0.0).toFloat())

        fun shotJson(s: Shot): JSONObject =
            JSONObject().put("id", s.id).put("createdAt", s.createdAt).put("pxW", s.pxW).put("pxH", s.pxH)
                .put("widthM", s.widthM?.toDouble() ?: JSONObject.NULL).put("heightM", s.heightM?.toDouble() ?: JSONObject.NULL)
                .put("surface", s.surface.name).put("assumedDepthM", s.assumedDepthM?.toDouble() ?: JSONObject.NULL).put("rectified", s.rectified)
                .put("mediaStoreUri", s.mediaStoreUri ?: JSONObject.NULL).put("mediaStoreMissing", s.mediaStoreMissing)
                .put("frame", frameJson(s.frame)).put("centerPose", arr(s.frame.center))

        fun shot(o: JSONObject) =
            Shot(
                o.getString("id"), o.getLong("createdAt"), o.getInt("pxW"), o.getInt("pxH"),
                if (o.isNull("widthM")) null else o.getDouble("widthM").toFloat(), if (o.isNull("heightM")) null else o.getDouble("heightM").toFloat(),
                Surface.valueOf(o.getString("surface")), if (o.isNull("assumedDepthM")) null else o.getDouble("assumedDepthM").toFloat(),
                o.getBoolean("rectified"), if (o.isNull("mediaStoreUri")) null else o.getString("mediaStoreUri"), o.optBoolean("mediaStoreMissing"),
                frame(o.getJSONObject("frame")),
            )

        fun calibJson(c: Calibration): JSONObject =
            JSONObject().put("t", arr(c.tcam.p)).put("q", JSONArray().put(c.tcam.q.x.toDouble()).put(c.tcam.q.y.toDouble()).put(c.tcam.q.z.toDouble()).put(c.tcam.q.w.toDouble()))
                .put("rmsPx", c.rmsPx.toDouble()).put("calibDistM", c.distM.toDouble()).put("degraded", c.degraded)

        fun calib(o: JSONObject): Calibration {
            val q = o.getJSONArray("q")
            return Calibration(Rigid(v(o, "t"), Quat4(q.getDouble(0).toFloat(), q.getDouble(1).toFloat(), q.getDouble(2).toFloat(), q.getDouble(3).toFloat())), o.getDouble("rmsPx").toFloat(), o.getDouble("calibDistM").toFloat(), o.getBoolean("degraded"))
        }
    }
}
