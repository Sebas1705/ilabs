package es.sebas1705.ilabs.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Above this many token steps the slider track is continuous: one tick mark per token would be thousands of marks. */
internal const val MAX_DISCRETE_STEPS = 100

/** Slider `steps` for a budget range of 1..max: one per token up to [MAX_DISCRETE_STEPS], otherwise 0 (continuous). */
internal fun sliderSteps(max: Int): Int {
    val span = maxOf(max, 2) - 1
    return if (span > MAX_DISCRETE_STEPS) 0 else span - 1
}

/** The slider value as a whole token count within 1..max (continuous tracks yield fractions). */
internal fun sliderTokens(value: Float, max: Int): Int = value.roundToInt().coerceIn(1, maxOf(max, 1))

/** The slider (enabled for `window` fixtures only), the "needs N" status and the totals. */
@Composable
internal fun BudgetCard(state: ContextUiState, view: ContextView, onBudget: (Int) -> Unit) {
    val budget = state.budget
    val tokens = pluralStringResource(R.plurals.context_tokens, budget, budget)
    val label = stringResource(R.string.context_budget_slider)
    val max = state.budgetMax
    val fixedReason = stringResource(R.string.context_budget_fixed_reason)
    Card(stringResource(R.string.context_budget)) {
        Text(tokens, style = MaterialTheme.typography.titleMedium)
        Slider(
            value = budget.toFloat(),
            onValueChange = { onBudget(sliderTokens(it, max)) },
            valueRange = 1f..maxOf(max, 2).toFloat(),
            // One token per step on short conversations; a continuous track (still rounded to whole tokens) on long ones.
            steps = sliderSteps(max),
            enabled = state.budgetEditable,
            colors = SliderDefaults.colors(
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                disabledActiveTickColor = Color.Transparent,
                disabledInactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = if (state.budgetEditable) tokens else "$tokens, $fixedReason"
            },
        )
        Text(
            if (state.budgetEditable) {
                stringResource(R.string.context_budget_range, 1, max)
            } else if (state.fixture?.setup?.kind == "window") {
                stringResource(R.string.context_budget_range_single)
            } else {
                stringResource(R.string.context_budget_fixed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        view.requiredTokens?.let { NeedsStatus(it) }
        view.failureKind?.let { kind ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.context_provider_failed), style = MaterialTheme.typography.bodyLarge)
                OutcomeBadge(kind)
            }
        }
        Totals(view)
    }
}

/** The "budget too small" state: polite live status with a glyph and the number, never a clamped slider. */
@Composable
private fun NeedsStatus(required: Int) {
    val status = ILabsTheme.status
    val text = pluralStringResource(R.plurals.context_needs, required, required)
    Surface(
        color = status.errBg,
        contentColor = status.errFg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            contentDescription = text
        },
    ) {
        Row(
            Modifier.padding(12.dp).heightIn(min = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("✕", fontWeight = FontWeight.Bold)
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The four totals as compact label/value pairs that wrap, so the conversation stays near the top of the screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Totals(view: ContextView) {
    Section(stringResource(R.string.context_totals))
    // A failed run has no result, so "after" and "dropped" say so instead of repeating the "before" numbers.
    val failed = view.requiredTokens != null || view.failureKind != null
    val none = stringResource(R.string.context_no_result)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(stringResource(R.string.context_tokens_before), pluralStringResource(R.plurals.context_tokens, view.tokensBefore, view.tokensBefore))
        Stat(
            stringResource(R.string.context_tokens_after),
            if (failed) none else pluralStringResource(R.plurals.context_tokens, view.tokensAfter, view.tokensAfter),
        )
        Stat(
            stringResource(R.string.context_dropped),
            if (failed) none else pluralStringResource(R.plurals.context_messages, view.droppedCount, view.droppedCount),
        )
        Stat(stringResource(R.string.context_calls), pluralStringResource(R.plurals.context_calls, view.calls, view.calls))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

