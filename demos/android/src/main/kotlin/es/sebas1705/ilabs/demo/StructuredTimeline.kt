package es.sebas1705.ilabs.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.sebas1705.ilabs.structured.AttemptTrace
import es.sebas1705.ilabs.structured.Extraction
import es.sebas1705.ilabs.structured.Issue
import es.sebas1705.ilabs.structured.IssueCode

/** Translated label for an issue or extraction code; the raw code is always shown next to it. */
@Composable
fun issueLabel(code: IssueCode): String = when (code) {
    IssueCode.TYPE -> stringResource(R.string.structured_issue_type)
    IssueCode.ENUM -> stringResource(R.string.structured_issue_enum)
    IssueCode.MINIMUM -> stringResource(R.string.structured_issue_minimum)
    IssueCode.MAXIMUM -> stringResource(R.string.structured_issue_maximum)
    IssueCode.MIN_LENGTH -> stringResource(R.string.structured_issue_min_length)
    IssueCode.MAX_LENGTH -> stringResource(R.string.structured_issue_max_length)
    IssueCode.MIN_ITEMS -> stringResource(R.string.structured_issue_min_items)
    IssueCode.MAX_ITEMS -> stringResource(R.string.structured_issue_max_items)
    IssueCode.REQUIRED -> stringResource(R.string.structured_issue_required)
    IssueCode.ADDITIONAL_PROPERTIES -> stringResource(R.string.structured_issue_additional)
    IssueCode.NO_JSON -> stringResource(R.string.structured_extract_no_json)
    IssueCode.INVALID_JSON -> stringResource(R.string.structured_extract_invalid_json)
}

/** Extraction outcome as a chip: glyph, translated label and the raw code (`ok`, `no_json`, `invalid_json`). */
@Composable
private fun ExtractionBadge(extracted: Extraction) {
    val status = ILabsTheme.status
    val ok = extracted is Extraction.Found
    val code = if (extracted is Extraction.Failed) extracted.code.wire else "ok"
    val label = if (extracted is Extraction.Failed) issueLabel(extracted.code) else stringResource(R.string.structured_extract_ok)
    val text = stringResource(R.string.structured_label_code, label, code)
    val spoken = stringResource(R.string.structured_extraction_spoken, text)
    Surface(
        color = if (ok) status.okBg else status.errBg,
        contentColor = if (ok) status.okFg else status.errFg,
        shape = RoundedCornerShape(50),
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (ok) "✓" else "✕", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Monospace block for raw replies and pretty-printed JSON; long unbroken text wraps instead of scrolling. */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        SelectionContainer {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

/** Issues as path, translated label and raw code; the root path `""` reads as "(root)". One spoken sentence per row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssueList(issues: List<Issue>, modifier: Modifier = Modifier) {
    val root = stringResource(R.string.structured_root_path)
    Column(modifier) {
        issues.forEach { issue ->
            val path = issue.path.ifEmpty { root }
            val label = issueLabel(issue.code)
            val spoken = stringResource(R.string.structured_issue_spoken, path, label)
            FlowRow(
                Modifier.heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = spoken },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(path, fontFamily = if (issue.path.isEmpty()) null else FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.structured_label_code, label, issue.code.wire))
            }
        }
    }
}

@Composable
private fun AttemptHeader(number: Int, accepted: Boolean) {
    val status = ILabsTheme.status
    val verdict = stringResource(
        if (accepted) R.string.structured_verdict_accepted else R.string.structured_verdict_rejected,
    )
    val spoken = stringResource(R.string.structured_attempt_spoken, number, verdict)
    Row(
        Modifier.heightIn(min = 48.dp).clearAndSetSemantics {
            heading()
            contentDescription = spoken
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.structured_attempt, number),
            style = MaterialTheme.typography.titleMedium,
        )
        Surface(
            color = if (accepted) status.okBg else status.errBg,
            contentColor = if (accepted) status.okFg else status.errFg,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                "${if (accepted) "✓" else "✕"} $verdict",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Attempt(number: Int, trace: AttemptTrace) {
    val accepted = trace.extracted is Extraction.Found && trace.issues.isEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AttemptHeader(number, accepted)
        Label(stringResource(R.string.structured_reply))
        when {
            trace.reply.isEmpty() -> Text(stringResource(R.string.structured_reply_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                if (trace.reply.isBlank()) {
                    Text(stringResource(R.string.structured_reply_blank), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CodeBlock(trace.reply)
            }
        }
        Label(stringResource(R.string.structured_extraction))
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { ExtractionBadge(trace.extracted) }
        (trace.extracted as? Extraction.Found)?.let {
            Label(stringResource(R.string.structured_extracted_json))
            CodeBlock(prettyJson(it.value))
        }
        val count = pluralStringResource(R.plurals.structured_issues_count, trace.issues.size, trace.issues.size)
        Label("${stringResource(R.string.structured_issues)}: $count")
        if (trace.issues.isEmpty()) {
            Text(stringResource(R.string.structured_no_issues), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            IssueList(trace.issues)
        }
    }
}

@Composable
private fun RepairStep() {
    val title = stringResource(R.string.structured_repair_requested)
    val detail = stringResource(R.string.structured_repair_detail)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "$title. $detail assistant, user" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("↓ $title", fontWeight = FontWeight.SemiBold)
        Text("$detail assistant, user", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The call that raised a provider error has no reply, so it gets a row of its own, in words as well as colour. */
@Composable
private fun StructuredProviderFailureRow(failure: StructuredProviderFailure) {
    val status = ILabsTheme.status
    val kind = stringResource(R.string.structured_label_code, outcomeLabel(failure.kind.wire), failure.kind.wire)
    val text = stringResource(R.string.structured_provider_failed, failure.attempt, kind)
    Surface(
        color = status.errBg,
        contentColor = status.errFg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = text },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✕", fontWeight = FontWeight.Bold)
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One block per recorded attempt, a repair step between consecutive ones, and a final row for a provider failure. */
@Composable
fun AttemptTimeline(trace: List<AttemptTrace>, failure: StructuredProviderFailure?, modifier: Modifier = Modifier) {
    if (trace.isEmpty() && failure == null) {
        Text(stringResource(R.string.structured_no_attempts), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        trace.forEachIndexed { index, attempt ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(12.dp)) { Attempt(index + 1, attempt) }
            }
            if (index < trace.size - 1 || failure != null) RepairStep()
        }
        failure?.let { StructuredProviderFailureRow(it) }
    }
}
