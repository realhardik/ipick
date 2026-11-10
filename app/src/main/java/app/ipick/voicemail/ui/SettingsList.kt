package app.ipick.voicemail.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/*
 * The parts Settings is built from: a titled group of rows, the rows
 * themselves, a footer, a dialog for picking one of a few, and a row of
 * connected buttons for the same job inline.
 *
 * WHY THESE SHAPES. Android's settings guidelines
 * (source.android.com/docs/core/settings/settings-guidelines) ask for a screen
 * that can be read at a glance - each setting's current value under its title,
 * explanations in a footer, single choices in a dialog. Settings was fourteen
 * cards of paragraphs with the values buried in them; these are what it is
 * made of now.
 *
 * IN THE LOOK'S OWN WAY - design C of the canvas's "Settings, three ways",
 * the user's choice, 2026-10-23. A group is headed as a day is in the lists
 * and sits on the look's ground, a line between its rows, as the lists' rows
 * sit: a Memo card, a Deck face plate, Multitrack's lanes. It was Material
 * 3's grouped tiles, split by small gaps, the same in every look. A row's
 * words are in the look's type; its switch is the look's own; and a setting
 * the header marks carries that mark where its icon was.
 */

@Composable
internal fun ButtonLabel(text: String) {
    val size = MaterialTheme.typography.labelLarge.fontSize
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        // Only where its pair said it must: the steps up from the floor do
        // not land on the full size, and every label came out a shade small.
        autoSize = if (LocalLabelsShrink.current) TextAutoSize.StepBased(minFontSize = size * SHRINK_AT_MOST, maxFontSize = size) else null,
    )
}

/** Whether ButtonPair's labels need to shrink to sit side by side. */
private val LocalLabelsShrink = staticCompositionLocalOf { false }

/** How far a ButtonLabel may shrink: a little, never as far as large text
 *  undone - at twice the text size, shrunk to fit beside "Delete", "Record
 *  again" was half its neighbour's size. */
private const val SHRINK_AT_MOST = 0.85f

/**
 * Buttons that share a row at equal width - or, where a label would not fit
 * its half even shrunk to [SHRINK_AT_MOST], one under the other, each the
 * full width: at large text a pair stacks, rather than wrap a label onto two
 * lines or shrink it past reading. [button] draws the [i]th with the
 * modifier it is given.
 */
@Composable
internal fun ButtonPair(
    labels: List<String>,
    modifier: Modifier = Modifier,
    button: @Composable (i: Int, modifier: Modifier) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 8.dp
        // A filled button's own padding, 24 dp a side (Material's
        // ButtonDefaults.ContentPadding), out of each share.
        val room = (maxWidth - gap * (labels.size - 1)) / labels.size - 48.dp
        fun fit(scale: Float) = labels.all { label ->
            val width = measurer.measure(label, style.copy(fontSize = style.fontSize * scale), maxLines = 1, softWrap = false).size.width
            with(density) { width.toDp() } <= room
        }
        if (fit(SHRINK_AT_MOST)) {
            CompositionLocalProvider(LocalLabelsShrink provides !fit(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { labels.indices.forEach { button(it, Modifier.weight(1f)) } }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) { labels.indices.forEach { button(it, Modifier.fillMaxWidth()) } }
        }
    }
}

