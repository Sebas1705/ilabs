package es.sebas1705.ilabs.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextScreen(
    state: ContextUiState,
    onSelect: (String) -> Unit,
    onBudget: (Int) -> Unit,
    onBack: (() -> Unit)? = null,
) {
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
                val view = state.view
                if (state.fixture != null && view != null) {
                    item(key = "budget-${state.selected}") { BudgetCard(state, view, onBudget) }
                    item(key = "conversation-${state.selected}") { ConversationCard(view) }
                    if (state.fixture.setup.kind != "window") {
                        item(key = "summarizer-${state.selected}") { SummarizerCard(view) }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.context_footer),
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
private fun Header(state: ContextUiState, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.context_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.context_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.loadError?.let {
            Text(stringResource(R.string.context_load_error, it), color = MaterialTheme.colorScheme.error)
        }
        state.runError?.let {
            Text(stringResource(R.string.context_run_error, it), color = MaterialTheme.colorScheme.error)
        }
        if (state.names.isNotEmpty()) FixturePicker(state.groups, state.selected.orEmpty(), onSelect)
    }
}

@Composable
private fun groupLabel(group: ContextGroup): String = when (group) {
    ContextGroup.WINDOW -> stringResource(R.string.context_group_window)
    ContextGroup.SUMMARIZE -> stringResource(R.string.context_group_summarize)
    ContextGroup.OTHER -> stringResource(R.string.context_group_other)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixturePicker(groups: List<Pair<ContextGroup, List<String>>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.context_fixture_picker, selected)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.context_fixture)) },
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
private fun ConversationCard(view: ContextView) {
    Card(stringResource(R.string.context_conversation)) {
        if (view.rows.isEmpty()) {
            Text(stringResource(R.string.context_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ContextRows(view.rows)
        }
    }
}

@Composable
private fun SummarizerCard(view: ContextView) {
    Card(stringResource(R.string.context_summarizer)) {
        val request = view.summarizerRequest
        if (request == null) {
            Text(stringResource(R.string.context_no_call), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Section(stringResource(R.string.context_request))
            Text(
                stringResource(R.string.context_request_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            request.messages.forEach { message ->
                Text(
                    stringResource(R.string.context_label_code, roleLabel(message.role), message.role.name.lowercase()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CodeBlock(message.content)
            }
        }
        view.summary?.let {
            Section(stringResource(R.string.context_summary))
            CodeBlock(it.content)
        }
    }
}
