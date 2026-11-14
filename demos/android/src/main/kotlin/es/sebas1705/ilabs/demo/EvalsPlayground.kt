package es.sebas1705.ilabs.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.sebas1705.ilabs.evals.Status

/** The callbacks the playground needs; the screen wires them to the view model. */
class PlaygroundActions(
    val onKind: (PlaygroundKind) -> Unit,
    val onParam: (String) -> Unit,
    val onMin: (String) -> Unit,
    val onMax: (String) -> Unit,
    val onTrim: (Boolean) -> Unit,
    val onIgnoreCase: (Boolean) -> Unit,
    val onReply: (String) -> Unit,
)

@Composable
private fun kindLabel(kind: PlaygroundKind): String {
    val label = graderKindLabel(kind.wire)
    return if (showRoleCode(label, kind.wire)) stringResource(R.string.evals_label_code, label, kind.wire) else label
}

@Composable
private fun paramMessage(error: ParamError): String = when (error.code) {
    ParamErrorCode.INVALID_JSON -> stringResource(R.string.evals_err_invalid_json)
    ParamErrorCode.INVALID_PARAMS -> stringResource(R.string.evals_err_invalid_params, error.detail.orEmpty())
    ParamErrorCode.INVALID_SCHEMA -> stringResource(R.string.evals_err_invalid_schema, error.detail.orEmpty())
}

@Composable
internal fun PlaygroundCard(state: PlaygroundState, actions: PlaygroundActions) {
    Card(stringResource(R.string.evals_playground)) {
        Text(
            stringResource(R.string.evals_playground_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KindPicker(state.kind, actions.onKind)
        when (state.kind) {
            PlaygroundKind.LENGTH -> {
                NumberField(stringResource(R.string.evals_pg_min), state.min, actions.onMin)
                NumberField(stringResource(R.string.evals_pg_max), state.max, actions.onMax)
            }
            PlaygroundKind.ONE_OF -> TextField(stringResource(R.string.evals_pg_values), state.param, actions.onParam, minLines = 3, monospace = false)
            PlaygroundKind.JSON -> TextField(stringResource(R.string.evals_pg_schema), state.param, actions.onParam, minLines = 3, monospace = true)
            PlaygroundKind.JSON_EQUALS -> TextField(stringResource(R.string.evals_pg_json_value), state.param, actions.onParam, minLines = 2, monospace = true)
            else -> TextField(stringResource(R.string.evals_pg_value), state.param, actions.onParam, minLines = 1, monospace = false)
        }
        if (state.kind.hasTextOptions) {
            OptionSwitch(stringResource(R.string.evals_pg_trim), state.trim, actions.onTrim)
            OptionSwitch(stringResource(R.string.evals_pg_ignore_case), state.ignoreCase, actions.onIgnoreCase)
        }
        TextField(stringResource(R.string.evals_pg_reply), state.reply, actions.onReply, minLines = 3, monospace = false)
        Verdict(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindPicker(selected: PlaygroundKind, onSelect: (PlaygroundKind) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val text = kindLabel(selected)
    val description = stringResource(R.string.evals_pg_grader_picker, text)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.evals_pg_grader)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { contentDescription = description },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlaygroundKind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kindLabel(kind)) },
                    onClick = {
                        onSelect(kind)
                        expanded = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun TextField(label: String, value: String, onChange: (String) -> Unit, minLines: Int, monospace: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = minLines,
        textStyle = MaterialTheme.typography.bodyLarge.let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

/** A labelled switch whose whole row toggles, so the touch target is the row and not just the thumb. */
@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The live verdict: pass or fail with its issues, or the parameter error that stops a verdict. */
@Composable
private fun Verdict(state: PlaygroundState) {
    val s = ILabsTheme.status
    val error = state.error
    val verdict = state.verdict
    Section(stringResource(R.string.evals_pg_verdict))
    if (error != null) {
        val text = stringResource(R.string.evals_pg_param_error, paramMessage(error))
        Surface(
            color = s.errBg,
            contentColor = s.errFg,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            },
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("✕", fontWeight = FontWeight.Bold)
                Text(text, fontWeight = FontWeight.SemiBold)
            }
        }
    } else if (verdict != null) {
        val status = if (verdict.passed) Status.PASS else Status.FAIL
        val label = statusLabel(status)
        val spoken = stringResource(R.string.evals_pg_verdict_spoken, label)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.clearAndSetSemantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = spoken
                },
            ) { EvalsStatusBadge(status) }
            verdict.issues?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    stringResource(R.string.evals_grader_issues),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IssueList(it)
            }
        }
    }
}
