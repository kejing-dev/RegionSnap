package io.embodit.regionsnap.domain

/**
 * v0.1.5 output tiers (user: "more resolutions, best 1080P / 2K / 4K"): the long side of the straightened picture, the
 * short side follows the frame's real aspect. Each tier also asks the camera for a readback this big ([camW] × [camH],
 * 4:3); when the session refuses it the camera steps down [cameraSizes] and the picture comes out as big as the real
 * pixels allow (never upscaled).
 */
enum class OutRes(val long: Int, val label: String, val camW: Int, val camH: Int) {
    P1080(1920, "1080P", 1920, 1440),
    K2(2560, "2K", 2560, 1920),
    K4(3840, "4K", 3840, 2880);

    /** Camera readback sizes to try, this tier's first, then smaller ones down to the old 640 × 480. */
    fun cameraSizes(): List<Pair<Int, Int>> = (listOf(camW to camH) + FALLBACK.filter { it.first < camW }).distinct()

    companion object {
        val DEFAULT = K2
        private val FALLBACK = listOf(2560 to 1920, 1920 to 1440, 1280 to 960, 640 to 480)

        fun parse(s: String?): OutRes = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}
