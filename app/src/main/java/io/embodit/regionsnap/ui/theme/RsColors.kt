package io.embodit.regionsnap.ui.theme

import androidx.compose.ui.graphics.Color

/** The design's colours (design-package `palette`, `appIcon`, `entities3d`). The only file with colour literals. */
object RsColors {
    // PicoTheme roles kept as the system's; the frame / notes / primary keys use the app's primary
    val Primary = Color(0xFF5ED6B5) // design-style: fixed-figma-color primary (frame edges, note border, primary keys)
    val Level = Color(0xFFFFC56B) // design-style: fixed-figma-color tertiary (the levelled snap flash)
    val OnFill = Color(0xFF10201B) // design-style: fixed-figma-color ink on primary / tertiary fills
    val DangerFill = Color(0xFFFF8A80) // design-style: fixed-figma-color danger key fill (退出 / 删)
    val ErrorContainer = Color(0xFF5C1F1B) // design-style: fixed-figma-color errorContainer (S16 / S18 cards)
    val OnErrorContainer = Color(0xFFFFDAD5) // design-style: fixed-figma-color onErrorContainer
    val PrimaryContainer = Color(0xFF13423A) // design-style: fixed-figma-color primaryContainer (saved strip)
    val OnPrimaryContainer = Color(0xFFB8F5E3) // design-style: fixed-figma-color onPrimaryContainer

    // attached Stage panels: opaque plateInk #1C1C1E 90 % (palette.plateInk) with light text
    val PlateInk = Color(0xE61C1C1E) // design-style: fixed-figma-color plateInk
    val PlateText = Color(0xFFF2F2F5) // design-style: fixed-figma-color onSurface on plateInk
    // normal keys: opaque slate + light ink (the system key fill over the glass reads < 4.5:1, teleop-dojo v0.1.0 table)
    val KeyFill = Color(0xFF353B43) // design-style: fixed-figma-color normal key fill
    val KeyInk = Color(0xFFE8EDF2) // design-style: fixed-figma-color normal key text
    val Dim = Color(0xFFB9C2CC) // design-style: fixed-figma-color secondary text on plate / slate (>= 4.5:1 on KeyFill)

    // 3D
    val Frame = Primary
    val FrameFlash = Color(0xFFFFFFFF) // design-style: fixed-figma-color 拍下 flash
    val Handle = Color(0xFFF2F2F5) // design-style: fixed-figma-color handle balls
    val HandleHot = Primary
    val Reticle = Color(0xFFFFFFFF) // design-style: fixed-figma-color reticle ring
    val Trail = Level
}
