package io.embodit.regionsnap.domain

import android.content.SharedPreferences
import io.embodit.regionsnap.platform.Store
import io.embodit.regionsnap.ui.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.5, user on the headset: "the resolution needs more choices, best 1080P / 2K / 4K". The tier is the long side of
 * the straightened picture, the short side follows the frame's real aspect; short camera pixels give a smaller picture,
 * never an upscaled one; the choice is remembered (2K by default).
 */
class OutResTest {
    /** An axis-aligned quad [w] × [h] source pixels. */
    private fun quad(w: Float, h: Float) = listOf(Px(0f, 0f), Px(w, 0f), Px(w, h), Px(0f, h))

    @Test fun threeTiersGiveTheirLongSide() {
        val q = quad(5000f, 3000f) // plenty of camera pixels
        assertEquals(1920 to 1080, Rectify.outputSize(q, 16f / 9f, OutRes.P1080.long))
        assertEquals(2560 to 1440, Rectify.outputSize(q, 16f / 9f, OutRes.K2.long))
        assertEquals(3840 to 2160, Rectify.outputSize(q, 16f / 9f, OutRes.K4.long))
        assertEquals(listOf(1920, 2560, 3840), OutRes.entries.map { it.long })
        assertEquals(listOf("1080P", "2K", "4K"), OutRes.entries.map { it.label })
    }

    @Test fun aspectFollowsTheRealFrame() {
        val q = quad(5000f, 5000f)
        for (tier in OutRes.entries) {
            for (aspect in listOf(1.414f, 0.707f, 1f, 3f, 0.42f / 0.3f)) {
                val (w, h) = Rectify.outputSize(q, aspect, tier.long)
                assertEquals("$tier long", tier.long, maxOf(w, h))
                assertEquals("$tier $aspect", aspect, w.toFloat() / h, 0.01f * aspect)
            }
        }
    }

    @Test fun shortCameraPixelsAreNotUpscaled() {
        // a whiteboard 1100 px long in a 2560 × 1920 frame: every tier gives the real 1100, and says it fell short
        val q = quad(1100f, 700f)
        for (tier in OutRes.entries) {
            val (w, h) = Rectify.outputSize(q, 1.5f, tier.long)
            assertEquals(1100, w)
            assertEquals(733, h)
            assertTrue(Rectify.belowTier(q, tier))
        }
        // 3000 px: enough for 1080P and 2K, short of 4K
        val big = quad(3000f, 2000f)
        assertFalse(Rectify.belowTier(big, OutRes.P1080))
        assertFalse(Rectify.belowTier(big, OutRes.K2))
        assertTrue(Rectify.belowTier(big, OutRes.K4))
        assertEquals(3000, Rectify.outputSize(big, 1.5f, OutRes.K4.long).first)
    }

    @Test fun cameraAsksForTheTierThenStepsDown() {
        assertEquals(listOf(3840 to 2880, 2560 to 1920, 1920 to 1440, 1280 to 960, 640 to 480), OutRes.K4.cameraSizes())
        assertEquals(2560 to 1920, OutRes.K2.cameraSizes().first())
        assertEquals(640 to 480, OutRes.P1080.cameraSizes().last())
        for (t in OutRes.entries) assertTrue(t.cameraSizes().first().first >= t.long)
    }

    @Test fun choiceIsRememberedAndDefaultsTo2K() {
        val sp = MapPrefs()
        assertEquals(OutRes.K2, Store.readPrefs(sp).outRes)
        assertEquals(OutRes.K2, Prefs().outRes)
        for (t in OutRes.entries) {
            Store.writePrefs(sp.edit(), Prefs(outRes = t)).apply()
            assertEquals(t, Store.readPrefs(sp).outRes)
        }
        // an unknown value (an older build's) falls back to 2K
        sp.edit().putString("outRes", "HUGE").apply()
        assertEquals(OutRes.K2, Store.readPrefs(sp).outRes)
    }

    /** SharedPreferences in a map (the interface only; no Android runtime needed). */
    private class MapPrefs : SharedPreferences {
        val m = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(key: String, defValue: String?) = if (m.containsKey(key)) m[key] as String? else defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = m[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = m[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = m[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = m[key] as Boolean? ?: defValue
        override fun contains(key: String) = m.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { m.clear() }
            override fun commit(): Boolean { m.putAll(pending); return true }
            override fun apply() { m.putAll(pending) }
        }
    }
}
