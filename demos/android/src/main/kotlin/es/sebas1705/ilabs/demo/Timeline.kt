package es.sebas1705.ilabs.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ilabs.providers.Event

/** Translated label for an outcome code ("ok" or an error kind); unknown codes fall back to the raw code. */
@Composable
fun outcomeLabel(outcome: String): String = when (outcome) {
    "ok" -> stringResource(R.string.providers_outcome_ok)
    "rate_limit" -> stringResource(R.string.providers_outcome_rate_limit)
    "timeout" -> stringResource(R.string.providers_outcome_timeout)
    "invalid_output" -> stringResource(R.string.providers_outcome_invalid_output)
    "unavailable" -> stringResource(R.string.providers_outcome_unavailable)
    else -> outcome
}

/** A status chip with glyph, translated label and the raw code, so meaning never rests on colour alone. */
@Composable
fun OutcomeBadge(outcome: String, modifier: Modifier = Modifier) {
    val status = ILabsTheme.status
    val (bg, fg, glyph) = when (outcome) {
        "ok" -> Triple(status.okBg, status.okFg, "✓")
        "rate_limit", "timeout" -> Triple(status.warnBg, status.warnFg, "!")
        else -> Triple(status.errBg, status.errFg, "✕")
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(glyph, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.providers_outcome_label, outcomeLabel(outcome), outcome),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** One row per event, merged into a single TalkBack sentence ("Step 2: wait 100 milliseconds"). */
@Composable
fun Timeline(events: List<Event>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        events.forEachIndexed { index, event ->
            val step = index + 1
            val spoken = when (event) {
                is Event.Call -> stringResource(
                    R.string.providers_timeline_call, step, event.provider, outcomeLabel(event.outcome),
                )
                is Event.Wait -> stringResource(R.string.providers_timeline_wait, step, event.ms)
            }
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clearAndSetSemantics { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("$step", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (event) {
                    is Event.Call -> {
                        Text("${stringResource(R.string.providers_call_to)} →", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(event.provider, fontWeight = FontWeight.SemiBold)
                        OutcomeBadge(event.outcome)
                    }
                    is Event.Wait -> {
                        Text(stringResource(R.string.providers_wait), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.providers_ms, event.ms), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
