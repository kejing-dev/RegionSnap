package io.embodit.regionsnap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.regionsnap.ui.debug.probe
import io.embodit.regionsnap.ui.theme.RsColors
import io.embodit.regionsnap.ui.theme.RsType

enum class KeyKind { PRIMARY, DANGER, NORMAL }

/**
 * A text key (>= 48 dp, B19) with an optional icon (D23: never an icon alone). Primary / danger fills carry the dark ink;
 * normal keys are opaque slate + light ink (a system fill over the glass reads < 4.5:1).
 */
@Composable
internal fun Key(text: String, name: String, icon: Int? = null, kind: KeyKind = KeyKind.NORMAL, height: Dp = 48.dp, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = when (kind) {
        KeyKind.PRIMARY -> ButtonDefaults.buttonColors(containerColor = RsColors.Primary, contentColor = RsColors.OnFill)
        KeyKind.DANGER -> ButtonDefaults.buttonColors(containerColor = RsColors.DangerFill, contentColor = RsColors.OnFill)
        KeyKind.NORMAL -> ButtonDefaults.buttonColors(containerColor = RsColors.KeyFill, contentColor = RsColors.KeyInk)
    }
    val ink = if (kind == KeyKind.NORMAL) RsColors.KeyInk else RsColors.OnFill
    val style = if (kind == KeyKind.DANGER) RsType.button.copy(fontWeight = FontWeight.Bold) else RsType.button
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.probe(name).height(height),
        size = ButtonDefaults.Regular.copy(height = height),
        shape = CircleShape,
        colors = colors,
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, maxLines = 2, color = ink, style = style, textAlign = TextAlign.Center, onTextLayout = fits("key/$name"))
    }
}

@Composable
private fun segColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = RsColors.Primary,
        selectedItemContentColor = RsColors.OnFill,
    )

/** In-page options (SegmentControl, never for pages: D8), 48 dp; every item takes the widest label + 26 dp (D13). */
@Composable
internal fun <T> Options(options: List<Pair<T, String>>, selected: T?, name: String, minItem: Int = 64, enabled: Boolean = true, onPick: (T) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = RsType.label
    val w = remember(options.map { it.second }, minItem, density) {
        options.maxOf { (_, label) -> maxOf(minItem.dp, with(density) { (measurer.measure(label, style, maxLines = 1).size.width * 1.1f).toDp() } + 26.dp) }
    }
    // gate:allow tabs-segment options inside one settings row (language / output size / pinch mode), never to switch pages
    SegmentControl(modifier = Modifier.width(w * options.size + 8.dp).height(48.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        options.forEach { (v, label) ->
            val sel = v == selected
            SegmentItem(
                selected = sel,
                onClick = { if (enabled) onPick(v) },
                modifier = Modifier.width(w).height(40.dp).probe("${name}_$v"),
                textStyle = style,
                colors = segColors(),
                title = { Text(text = label, maxLines = 1, color = if (sel) RsColors.OnFill else PicoTheme.colorScheme.labelPrimary, style = style, onTextLayout = fits("seg/${name}_$v")) },
            )
        }
    }
}

/** A labelled row: label left, control right, >= 48 dp. */
@Composable
internal fun OptRow(label: String, name: String, height: Dp = 52.dp, control: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(height).probe("row_$name"), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = RsType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, modifier = Modifier.weight(1f), onTextLayout = fits("row/$name"))
        Spacer(Modifier.width(8.dp))
        control()
    }
}

/** A switch inside a >= 48 dp tappable box (D44: the switch itself is small). */
@Composable
internal fun SwitchBox(checked: Boolean, name: String, onChange: (Boolean) -> Unit) {
    Box(Modifier.height(48.dp).width(72.dp).probe(name), contentAlignment = Alignment.Center) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun Title(text: String, name: String, modifier: Modifier = Modifier, maxLines: Int = 2) =
    Text(text = text, style = RsType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = maxLines, modifier = modifier.probe(name), onTextLayout = fits("title/$name"))

@Composable
internal fun Body(text: String, name: String, modifier: Modifier = Modifier, maxLines: Int = 3, align: TextAlign = TextAlign.Start, color: Color? = null) =
    Text(text = text, style = RsType.body, color = color ?: PicoTheme.colorScheme.labelPrimary, maxLines = maxLines, textAlign = align, modifier = modifier.probe(name), onTextLayout = fits("body/$name"))

@Composable
internal fun Small(text: String, name: String, modifier: Modifier = Modifier, maxLines: Int = 2, color: Color? = null) =
    Text(text = text, style = RsType.label, color = color ?: PicoTheme.colorScheme.labelPrimary, maxLines = maxLines, modifier = modifier.probe(name), onTextLayout = fits("small/$name"))

/** The hint strip (design hintStrip, 40 dp, always reserved so the page does not jump). */
@Composable
internal fun HintStrip(text: String?, name: String = "hint") {
    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
        if (text == null) return@Box
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RsColors.PrimaryContainer).padding(horizontal = 12.dp, vertical = 4.dp).probe(name)) {
            Text(text = text, style = RsType.label, color = RsColors.OnPrimaryContainer, maxLines = 2, onTextLayout = fits("hint/$name"))
        }
    }
}

/** An in-page warning card (S16 / S18): errorContainer + onErrorContainer, its keys under the sentence. */
@Composable
internal fun WarnCard(text: String, name: String, keys: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RsColors.ErrorContainer).padding(14.dp).probe(name), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = text, style = RsType.body, color = RsColors.OnErrorContainer, maxLines = 4, onTextLayout = fits("card/$name"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { keys() }
    }
}
