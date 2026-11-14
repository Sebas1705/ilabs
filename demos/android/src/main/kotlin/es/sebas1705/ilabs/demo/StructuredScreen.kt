package es.sebas1705.ilabs.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.sebas1705.ilabs.structured.Fixture
import es.sebas1705.ilabs.structured.FixtureOutcome
import es.sebas1705.ilabs.structured.FixtureResult
import es.sebas1705.ilabs.providers.ScriptEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StructuredScreen(state: StructuredUiState, onSelect: (String) -> Unit, onBack: (() -> Unit)? = null) {
    val layoutDirection = LocalLayoutDirection.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (onBack != null) BackToModules(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp + padding.calculateLeftPadding(layoutDirection),
                    end = 16.dp + padding.calculateRightPadding(layoutDirection),
                    top = 12.dp,
                    bottom = 12.dp + padding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Header(state, onSelect) }
                state.fixture?.let {
                    item(key = "inputs-${it.name}") { InputsCard(it) }
                    item(key = "setup-${it.name}") { SetupCard(it) }
                }
                state.outcome?.let { outcome ->
                    item(key = "attempts-${state.selected}") {
                        Card(stringResource(R.string.structured_attempts)) {
                            AttemptTimeline(outcome.trace, state.providerFailure)
                        }
                    }
                    item(key = "result-${state.selected}") { ResultCard(outcome) }
                }
                item {
                    Text(
                        stringResource(R.string.structured_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(state: StructuredUiState, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.structured_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.structured_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.loadError?.let {
            Text(stringResource(R.string.structured_load_error, it), color = MaterialTheme.colorScheme.error)
        }
        state.runError?.let {
            Text(stringResource(R.string.structured_run_error, it), color = MaterialTheme.colorScheme.error)
        }
        if (state.names.isNotEmpty()) FixturePicker(state.groups, state.selected.orEmpty(), onSelect)
    }
}

@Composable
private fun groupLabel(group: StructuredGroup): String = when (group) {
    StructuredGroup.EXTRACT -> stringResource(R.string.structured_group_extract)
    StructuredGroup.VALIDATE -> stringResource(R.string.structured_group_validate)
    StructuredGroup.LOOP -> stringResource(R.string.structured_group_loop)
    StructuredGroup.OTHER -> stringResource(R.string.structured_group_other)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixturePicker(groups: List<Pair<StructuredGroup, List<String>>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.structured_fixture_picker, selected)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.structured_fixture)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { contentDescription = description },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            groups.forEach { (group, names) ->
                Text(
                    groupLabel(group),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                )
                names.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelect(name)
                            expanded = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InputsCard(fixture: Fixture) {
    Card(stringResource(R.string.structured_schema)) {
        CodeBlock(prettyJson(fixture.schema))
        Section(stringResource(R.string.structured_request))
        fixture.request.messages.forEach { message ->
            KeyValue(message.role.name.lowercase(), message.content)
        }
    }
}

@Composable
private fun SetupCard(fixture: Fixture) {
    Card(stringResource(R.string.structured_setup)) {
        KeyValue(stringResource(R.string.structured_max_repairs), fixture.setup.maxRepairs.toString())
        KeyValue(stringResource(R.string.structured_provider), fixture.provider.id)
        Section(stringResource(R.string.structured_scripts))
        Text(
            stringResource(R.string.structured_script_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fixture.provider.script.isEmpty()) {
            Text(stringResource(R.string.structured_empty_script), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        fixture.provider.script.forEachIndexed { i, entry -> ScriptRow(i + 1, entry) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptRow(number: Int, entry: ScriptEntry) {
    val response = entry.response
    val error = entry.error
    val spoken = when {
        response != null -> stringResource(R.string.structured_script_entry_response, number, response)
        error != null -> stringResource(R.string.structured_script_entry_error, number, outcomeLabel(error.kind.wire))
        else -> ""
    }
    Column(
        Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("$number.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (response != null) {
                Text(stringResource(R.string.structured_entry_response))
            } else if (error != null) {
                Text(stringResource(R.string.structured_entry_error))
                OutcomeBadge(error.kind.wire)
                error.retryAfterMs?.let {
                    Text(
                        "(${stringResource(R.string.structured_retry_after, it)})",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (response != null) CodeBlock(response.ifEmpty { stringResource(R.string.structured_reply_empty) })
    }
}

@Composable
private fun ResultCard(outcome: FixtureOutcome) {
    val attempts = pluralStringResource(R.plurals.structured_attempts_count, outcome.attempts, outcome.attempts)
    Card(stringResource(R.string.structured_result)) {
        when (val result = outcome.result) {
            is FixtureResult.Success -> {
                val spoken = stringResource(R.string.structured_result_success, attempts)
                Column(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutcomeBadge("ok")
                    Text(stringResource(R.string.structured_final_value), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CodeBlock(prettyJson(result.value))
            }
            is FixtureResult.Failure -> {
                val code = result.kind.wire
                val spoken = stringResource(R.string.structured_result_failure, outcomeLabel(code))
                Column(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.structured_failed_with), style = MaterialTheme.typography.bodyLarge)
                    OutcomeBadge(code)
                }
                result.issues?.takeIf { it.isNotEmpty() }?.let {
                    Section(stringResource(R.string.structured_issues))
                    IssueList(it)
                }
            }
        }
        KeyValue(stringResource(R.string.structured_attempts), attempts)
        KeyValue(
            stringResource(R.string.structured_appended),
            outcome.appended.joinToString(", ") { it.name.lowercase() }.ifEmpty { stringResource(R.string.structured_appended_none) },
        )
    }
}
