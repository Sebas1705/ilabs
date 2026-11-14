package es.sebas1705.ilabs.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.sebas1705.ilabs.evals.CaseResult
import es.sebas1705.ilabs.evals.EvalCase
import es.sebas1705.ilabs.evals.GraderResult
import es.sebas1705.ilabs.evals.Status
import es.sebas1705.ilabs.evals.TargetSummary
import kotlin.math.roundToInt

@Composable
internal fun statusLabel(status: Status): String = when (status) {
    Status.PASS -> stringResource(R.string.evals_status_pass)
    Status.FAIL -> stringResource(R.string.evals_status_fail)
    Status.ERROR -> stringResource(R.string.evals_status_error)
}

/** The translated status with its raw code when they differ (`superado (pass)`); in English they are the same word. */
@Composable
private fun statusText(status: Status): String {
    val label = statusLabel(status)
    return if (showRoleCode(label, status.wire)) stringResource(R.string.evals_label_code, label, status.wire) else label
}

private data class Tone(val bg: Color, val fg: Color, val glyph: String)

@Composable
private fun tone(status: Status): Tone {
    val s = ILabsTheme.status
    return when (status) {
        Status.PASS -> Tone(s.okBg, s.okFg, "✓")
        Status.FAIL -> Tone(s.errBg, s.errFg, "✕")
        Status.ERROR -> Tone(s.warnBg, s.warnFg, "!")
    }
}

/** A status chip with glyph and translated label, so the status never rests on colour alone. */
@Composable
internal fun EvalsStatusBadge(status: Status, modifier: Modifier = Modifier) {
    val t = tone(status)
    Surface(color = t.bg, contentColor = t.fg, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(t.glyph, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(statusText(status), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Status of one grader entry: an errored grader is `error` (its `passed` is false), then pass or fail. */
internal fun graderStatus(g: GraderResult): Status = when {
    g.error != null -> Status.ERROR
    g.passed -> Status.PASS
    else -> Status.FAIL
}

@Composable
internal fun graderKindLabel(kind: String): String = when (kind) {
    "equals" -> stringResource(R.string.evals_kind_equals)
    "contains" -> stringResource(R.string.evals_kind_contains)
    "notContains" -> stringResource(R.string.evals_kind_notcontains)
    "startsWith" -> stringResource(R.string.evals_kind_startswith)
    "endsWith" -> stringResource(R.string.evals_kind_endswith)
    "oneOf" -> stringResource(R.string.evals_kind_oneof)
    "length" -> stringResource(R.string.evals_kind_length)
    "json" -> stringResource(R.string.evals_kind_json)
    "jsonEquals" -> stringResource(R.string.evals_kind_jsonequals)
    "judge" -> stringResource(R.string.evals_kind_judge)
    else -> kind
}

/** Label with the raw code next to it when the translation differs from it. */
@Composable
private fun withCode(label: String, code: String): String =
    if (showRoleCode(label, code)) stringResource(R.string.evals_label_code, label, code) else label

@Composable
private fun graderErrorLabel(code: String): String = when (code) {
    "bad_verdict" -> stringResource(R.string.evals_error_bad_verdict)
    "no_judge" -> stringResource(R.string.evals_error_no_judge)
    else -> outcomeLabel(code)
}

/** Per-target summary: pass-rate bar, counts and percentage, one spoken sentence per row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SummaryCard(run: EvalsRunView) {
    Card(stringResource(R.string.evals_summary)) {
        if (run.summary.isEmpty()) {
            Text(stringResource(R.string.evals_summary_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        run.summary.forEach { SummaryRow(it) }
        Section(stringResource(R.string.evals_calls_title))
        val judgeLabel = stringResource(R.string.evals_calls_judge)
        val entries = run.calls.map { (id, n) -> (if (id == "judge") judgeLabel else id) to n }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.forEach { (id, n) ->
                Text(stringResource(R.string.evals_calls_entry, id, pluralStringResource(R.plurals.evals_calls, n, n)))
            }
            if (entries.isEmpty()) Text("0", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryRow(row: TargetSummary) {
    val percent = stringResource(R.string.evals_percent, (row.passRate * 100).roundToInt())
    val passed = pluralStringResource(R.plurals.evals_passed, row.passed, row.passed)
    val failed = pluralStringResource(R.plurals.evals_failed, row.failed, row.failed)
    val errored = pluralStringResource(R.plurals.evals_errored, row.errored, row.errored)
    val spoken = stringResource(R.string.evals_summary_spoken, row.targetId, percent, passed, failed, errored)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.targetId, fontWeight = FontWeight.SemiBold)
            Text(percent, fontWeight = FontWeight.SemiBold)
            Text(
                pluralStringResource(R.plurals.evals_cases, row.total, row.total),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PassBar(row)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("✓ $passed", color = ILabsTheme.status.okFg)
            Text("✕ $failed", color = ILabsTheme.status.errFg)
            Text("! $errored", color = ILabsTheme.status.warnFg)
        }
    }
}

/** Decorative stacked bar of passed, failed and errored shares; the counts are also written as text. */
@Composable
private fun PassBar(row: TargetSummary) {
    val s = ILabsTheme.status
    val total = row.total.coerceAtLeast(1).toFloat()
    Row(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (row.passed > 0) Box(Modifier.weight(row.passed / total).height(8.dp).background(s.okFg))
        if (row.failed > 0) Box(Modifier.weight(row.failed / total).height(8.dp).background(s.errFg))
        if (row.errored > 0) Box(Modifier.weight(row.errored / total).height(8.dp).background(s.warnFg))
        val rest = row.total - row.passed - row.failed - row.errored
        if (rest > 0) Box(Modifier.weight(rest / total).height(8.dp))
    }
}

/** Cases as rows; each target's result is a 48 dp button that opens the detail. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MatrixCard(run: EvalsRunView, selected: CellRef?, onCell: (String, String) -> Unit) {
    Card(stringResource(R.string.evals_matrix)) {
        Text(
            stringResource(R.string.evals_matrix_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (run.targets.isEmpty()) {
            Text(stringResource(R.string.evals_matrix_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        run.cases.forEach { case ->
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    case.id,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    run.targets.forEach { target ->
                        val result = run.result(case.id, target)
                        if (result != null) {
                            Cell(case.id, target, result.status, selected == CellRef(case.id, target)) { onCell(case.id, target) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(caseId: String, targetId: String, status: Status, isSelected: Boolean, onClick: () -> Unit) {
    val t = tone(status)
    val spoken = stringResource(R.string.evals_cell_spoken, caseId, targetId, statusText(status))
    Surface(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                selected = isSelected
            },
        color = t.bg,
        contentColor = t.fg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else t.fg.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(t.glyph, fontWeight = FontWeight.Bold)
            Text(targetId, fontWeight = FontWeight.SemiBold)
            Text(statusText(status))
        }
    }
}

/** Everything about one (case, target): the conversation sent, the reply or the provider error, and every grader. */
@Composable
internal fun DetailCard(case: EvalCase?, result: CaseResult, graderParams: List<String>, onClose: () -> Unit) {
    Card(stringResource(R.string.evals_detail)) {
        Text(
            stringResource(R.string.evals_detail_title, result.caseId, result.targetId),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.evals_detail_status), color = MaterialTheme.colorScheme.onSurfaceVariant)
            EvalsStatusBadge(result.status)
        }
        case?.let {
            Section(stringResource(R.string.evals_conversation))
            it.messages.forEach { message ->
                Text(
                    withCode(roleLabel(message.role), message.role.name.lowercase()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CodeBlock(message.content.ifEmpty { stringResource(R.string.evals_content_empty) })
            }
        }
        Section(stringResource(R.string.evals_reply))
        val reply = result.reply
        if (reply != null) {
            CodeBlock(reply.ifEmpty { stringResource(R.string.evals_reply_empty) })
        } else {
            Text(stringResource(R.string.evals_no_reply), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        result.error?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.evals_provider_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutcomeBadge(it)
            }
        }
        if (result.graders.isNotEmpty()) {
            Section(stringResource(R.string.evals_graders))
            result.graders.forEachIndexed { index, grader -> GraderRow(index + 1, result.graders.size, grader, graderParams.getOrNull(index).orEmpty()) }
        }
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.evals_detail_close))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GraderRow(number: Int, total: Int, grader: GraderResult, params: String) {
    val status = graderStatus(grader)
    val kind = withCode(graderKindLabel(grader.kind), grader.kind)
    val error = grader.error?.let { withCode(graderErrorLabel(it), it) }
    val spoken = if (error != null) {
        stringResource(R.string.evals_grader_error_spoken, number, total, kind, error)
    } else {
        stringResource(R.string.evals_grader_spoken, number, total, kind, statusText(status))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // One spoken sentence for the header row; the issues below keep their own sentences.
        FlowRow(
            Modifier.heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = spoken },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$number.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(kind, fontWeight = FontWeight.SemiBold)
            EvalsStatusBadge(status)
            error?.let { Text(it, color = ILabsTheme.status.warnFg) }
        }
        if (params.isNotEmpty()) CodeBlock(params)
        grader.issues?.takeIf { it.isNotEmpty() }?.let {
            Text(
                stringResource(R.string.evals_grader_issues),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IssueList(it)
        }
    }
}
