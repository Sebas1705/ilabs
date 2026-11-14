package es.sebas1705.ilabs.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import es.sebas1705.ilabs.providers.Role

/** Translated role name; the raw code is always shown next to it. */
@Composable
internal fun roleLabel(role: Role): String = when (role) {
    Role.SYSTEM -> stringResource(R.string.context_role_system)
    Role.USER -> stringResource(R.string.context_role_user)
    Role.ASSISTANT -> stringResource(R.string.context_role_assistant)
}

@Composable
private fun stateLabel(state: MessageState): String = when (state) {
    MessageState.KEPT -> stringResource(R.string.context_state_kept)
    MessageState.DROPPED -> stringResource(R.string.context_state_dropped)
    MessageState.REPLACED -> stringResource(R.string.context_state_replaced)
    MessageState.NEW_SUMMARY -> stringResource(R.string.context_state_new_summary)
}

private fun stateGlyph(state: MessageState): String = when (state) {
    MessageState.KEPT -> "✓"
    MessageState.DROPPED -> "✕"
    MessageState.REPLACED -> "↻"
    MessageState.NEW_SUMMARY -> "+"
}

/** The (background, foreground) pair of a state chip. Colour only reinforces the glyph and the label. */
@Composable
private fun stateTones(state: MessageState): Pair<Color, Color> {
    val status = ILabsTheme.status
    return when (state) {
        MessageState.KEPT, MessageState.NEW_SUMMARY -> status.okBg to status.okFg
        MessageState.REPLACED -> status.warnBg to status.warnFg
        MessageState.DROPPED -> status.errBg to status.errFg
    }
}

/** A state chip with glyph and translated label, so the state never rests on colour alone. */
@Composable
private fun StateChip(state: MessageState) {
    val (bg, fg) = stateTones(state)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stateGlyph(state), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(stateLabel(state), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A decorative bar whose length is the message cost relative to the costliest one; the cost is also written as text. */
@Composable
private fun CostBar(fraction: Float, state: MessageState?) {
    val color = when (state) {
        MessageState.KEPT, MessageState.NEW_SUMMARY -> ILabsTheme.status.okFg
        MessageState.REPLACED -> ILabsTheme.status.warnFg
        MessageState.DROPPED -> ILabsTheme.status.errFg
        null -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp).background(color))
    }
}

/** Whether the raw role code adds anything to the translated label (in English both are the same word). */
internal fun showRoleCode(label: String, code: String): Boolean = !label.equals(code, ignoreCase = true)

/** One row per message, merged into a single TalkBack sentence: position, role, cost, state, content. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextRows(rows: List<ContextRow>, modifier: Modifier = Modifier) {
    val maxCost = rows.maxOfOrNull { it.cost }?.coerceAtLeast(1) ?: 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { index, row ->
            val role = roleLabel(row.message.role)
            val tokens = pluralStringResource(R.plurals.context_tokens, row.cost, row.cost)
            val state = row.state?.let { stateLabel(it) }
            val spoken = stringResource(R.string.context_row_spoken, index + 1, rows.size, role, listOfNotNull(tokens, state).joinToString(", "), row.message.content)
            val dim = row.state == MessageState.DROPPED || row.state == MessageState.REPLACED
            Column(
                Modifier
                    .heightIn(min = 48.dp)
                    .padding(vertical = 6.dp)
                    .clearAndSetSemantics { contentDescription = spoken },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${index + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val code = row.message.role.name.lowercase()
                    Text(
                        if (showRoleCode(role, code)) stringResource(R.string.context_label_code, role, code) else role,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(tokens, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    row.state?.let { StateChip(it) }
                }
                CostBar(row.cost.toFloat() / maxCost, row.state)
                SelectionContainer {
                    Text(
                        row.message.content.ifEmpty { stringResource(R.string.context_content_empty) },
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (row.state == MessageState.DROPPED) TextDecoration.LineThrough else null,
                        modifier = Modifier.alpha(if (dim) 0.7f else 1f),
                    )
                }
            }
        }
    }
}
