package es.sebas1705.ilabs.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import es.sebas1705.ilabs.providers.Fixture
import es.sebas1705.ilabs.providers.FixtureOutcome
import es.sebas1705.ilabs.providers.FixtureResult
import es.sebas1705.ilabs.providers.ScriptEntry

/** Readable width of the content column on tablets and foldables. */
internal val ContentMaxWidth: Dp = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(state: ProvidersUiState, onSelect: (String) -> Unit, onBack: (() -> Unit)? = null) {
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
                state.fixture?.let { item(key = "setup-${it.name}") { SetupCard(it) } }
                state.outcome?.let { outcome ->
                    item(key = "timeline-${state.selected}") { TimelineCard(outcome) }
                    item(key = "result-${state.selected}") { ResultCard(outcome) }
                }
                item {
                    Text(
                        stringResource(R.string.providers_footer),
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
private fun Header(state: ProvidersUiState, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.providers_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.providers_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.loadError?.let {
            Text(stringResource(R.string.providers_load_error, it), color = MaterialTheme.colorScheme.error)
        }
        state.runError?.let {
            Text(stringResource(R.string.providers_run_error, it), color = MaterialTheme.colorScheme.error)
        }
        if (state.names.isNotEmpty()) FixturePicker(state.names, state.selected.orEmpty(), onSelect)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixturePicker(names: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.providers_fixture_picker, selected)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.providers_fixture)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { contentDescription = description },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

@Composable
internal fun Card(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
internal fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
internal fun KeyValue(key: String, value: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
        Text(key, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SetupCard(fixture: Fixture) {
    val setup = fixture.setup
    Card(stringResource(R.string.providers_setup)) {
        KeyValue(
            stringResource(R.string.providers_kind),
            stringResource(if (setup.kind == "retry") R.string.providers_kind_retry else R.string.providers_kind_fallback),
        )
        setup.maxAttempts?.let { KeyValue(stringResource(R.string.providers_max_attempts), it.toString()) }
        setup.baseDelayMs?.let {
            KeyValue(stringResource(R.string.providers_base_delay), stringResource(R.string.providers_ms, it))
        }
        setup.maxDelayMs?.let {
            KeyValue(stringResource(R.string.providers_max_delay), stringResource(R.string.providers_ms, it))
        }
        KeyValue(
            stringResource(R.string.providers_request),
            fixture.request.messages.joinToString(" / ") { "${it.role.name.lowercase()}: ${it.content}" },
        )
        Section(stringResource(R.string.providers_scripts))
        Text(
            stringResource(R.string.providers_script_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fixture.providers.isEmpty()) Text(stringResource(R.string.providers_no_providers))
        fixture.providers.forEach { provider ->
            Text(provider.id, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            if (provider.script.isEmpty()) {
                Text(stringResource(R.string.providers_empty_script), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            provider.script.forEachIndexed { i, entry -> ScriptRow(provider.id, i + 1, entry) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptRow(providerId: String, number: Int, entry: ScriptEntry) {
    val response = entry.response
    val error = entry.error
    val spoken = when {
        response != null -> stringResource(R.string.providers_script_entry_response, providerId, number, response)
        error != null ->
            stringResource(R.string.providers_script_entry_error, providerId, number, outcomeLabel(error.kind.wire))
        else -> ""
    }
    FlowRow(
        Modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$number.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (response != null) {
            Text(stringResource(R.string.providers_entry_response))
            Text("\"$response\"", fontWeight = FontWeight.SemiBold)
        } else if (error != null) {
            Text(stringResource(R.string.providers_entry_error))
            OutcomeBadge(error.kind.wire)
            error.retryAfterMs?.let {
                Text(
                    "(${stringResource(R.string.providers_retry_after, it)})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TimelineCard(outcome: FixtureOutcome) {
    Card(stringResource(R.string.providers_timeline)) {
        Timeline(outcome.events, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ResultCard(outcome: FixtureOutcome) {
    Card(stringResource(R.string.providers_result)) {
        when (val result = outcome.result) {
            is FixtureResult.Success -> {
                val spoken = stringResource(R.string.providers_result_success, result.text, result.provider)
                Column(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutcomeBadge("ok")
                    SelectionContainer {
                        Text("\"${result.text}\"", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        stringResource(R.string.providers_answered_by, result.provider),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is FixtureResult.Failure -> {
                val code = result.kind.wire
                val spoken = stringResource(R.string.providers_result_failure, outcomeLabel(code))
                Column(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.providers_failed_with), style = MaterialTheme.typography.bodyLarge)
                    OutcomeBadge(code)
                }
            }
        }
        Section(stringResource(R.string.providers_calls))
        if (outcome.calls.isEmpty()) {
            Text(stringResource(R.string.providers_no_calls), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        outcome.calls.forEach { (id, count) ->
            val spoken = pluralStringResource(R.plurals.providers_calls_entry_spoken, count, id, count)
            Row(
                Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.providers_calls_entry, id, count))
            }
        }
    }
}
