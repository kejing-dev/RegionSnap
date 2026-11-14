package io.embodit.regionsnap.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.random.Random

/**
 * The three short cues (design assets.sounds): 快门 (mechanical click, CC0 recording), 嗒 (pin up / down, CC0 wood tap),
 * tick (handle grabbed / frame levelled, synthesised UI cue); no continuous sound (soundRules). WAVs in assets/sfx
 * (sources in apps/region-snap/build/sound-sources.md). One instance per cue, ±5 % pitch jitter on the recordings;
 * 快门声 off and 静音 both silence the shutter; 静音 silences everything (D15).
 */
class Sound(private val context: Context) {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, IntArray>()
    private val streams = HashMap<String, Int>()
    private var muted = false
    private var shutterOn = true
    private val rnd = Random(11)

    fun start() {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = p
        for ((cue, files) in CUES) {
            val loaded = files.mapNotNull { f -> runCatching { context.assets.openFd("sfx/$f.wav").use { fd -> p.load(fd, 1) } }.onFailure { RsLog.w("SOUND load $f failed: $it") }.getOrNull() }
            if (loaded.isNotEmpty()) ids[cue] = loaded.toIntArray()
        }
        RsLog.i("SOUND ready ${ids.mapValues { it.value.size }}")
    }

    fun release() {
        runCatching { pool?.release() }
        pool = null
        ids.clear()
        streams.clear()
    }

    fun set(mute: Boolean, shutter: Boolean) {
        muted = mute
        shutterOn = shutter
        RsLog.i("SOUND muted=$mute shutter=$shutter")
    }

    private fun play(cue: String, g: Float, jitter: Boolean) {
        val p = pool ?: return
        val variants = ids[cue] ?: return
        if (muted) return
        val v = (MASTER * g).coerceIn(0f, 1f)
        streams[cue]?.let { p.stop(it) }
        val rate = if (jitter) 1f + (rnd.nextFloat() - 0.5f) * 0.1f else 1f
        streams[cue] = p.play(variants[rnd.nextInt(variants.size)], v, v, 1, 0, rate)
    }

    fun shutter() {
        if (shutterOn) play("shutter", 1f, true)
    }

    fun tack() = play("tack", 0.7f, true)

    fun tick() = play("tick", 0.5f, false)

    companion object {
        /** Restrained default (D15): peaks about -3 dBFS. */
        const val MASTER = 0.71f

        val CUES = mapOf(
            "shutter" to listOf("shutter1", "shutter2"),
            "tack" to listOf("tack1", "tack2"),
            "tick" to listOf("tick"),
        )
    }
}
