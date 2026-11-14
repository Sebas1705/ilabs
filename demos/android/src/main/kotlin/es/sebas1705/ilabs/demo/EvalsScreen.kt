package es.sebas1705.ilabs.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Everything the screen can ask of the view model. */
class EvalsActions(
    val onSelect: (String) -> Unit,
    val onCell: (String, String) -> Unit,
    val onClearCell: () -> Unit,
    val playground: PlaygroundActions,
) {
    companion object {
        fun of(vm: EvalsViewModel) = EvalsActions(
            onSelect = vm::select,
            onCell = vm::selectCell,
            onClearCell = vm::clearCell,
            playground = PlaygroundActions(
                onKind = vm::setGraderKind,
                onParam = vm::setParam,
                onMin = vm::setMin,
                onMax = vm::setMax,
                onTrim = vm::setTrim,
                onIgnoreCase = vm::setIgnoreCase,
                onReply = vm::setReply,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvalsScreen(state: EvalsUiState, actions: EvalsActions, onBack: (() -> Unit)? = null) {
    val layoutDirection = LocalLayoutDirection.current
    val listState = rememberLazyListState()
    // Opening a cell scrolls its detail into view; a cell restored after rotation or process death keeps the scroll position.
    val cellKey = state.cell?.let { "${it.caseId}\u0000${it.targetId}" }
    var shownCell by rememberSaveable { mutableStateOf(cellKey) }
    LaunchedEffect(cellKey) {
        if (cellKey != null && cellKey != shownCell && state.cellResult != null) listState.animateScrollToItem(DETAIL_INDEX)
        shownCell = cellKey
    }
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
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp + padding.calculateLeftPadding(layoutDirection),
                    end = 16.dp + padding.calculateRightPadding(layoutDirection),
                    top = 12.dp,
                    bottom = 12.dp + padding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Item order matters: DETAIL_INDEX is the position of the detail when a run is shown.
                item { Header(state, actions.onSelect) }
                val run = state.run
                if (run != null) {
                    item(key = "summary-${state.selected}") { SummaryCard(run) }
                    item(key = "matrix-${state.selected}") { MatrixCard(run, state.cell, actions.onCell) }
                    state.cellResult?.let { result ->
                        item(key = "detail-${state.selected}") { DetailCard(state.cellCase, result, run.graderParams[result.caseId].orEmpty(), actions.onClearCell) }
                    }
                }
                state.invalidSuite?.let { reason ->
                    item(key = "invalid-${state.selected}") { InvalidCard(reason, state.outcome?.recordedCalls.orEmpty().values.sum()) }
                }
                item(key = "playground") { PlaygroundCard(state.playground, actions.playground) }
                item {
                    Text(
                        stringResource(R.string.evals_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Header, summary and matrix come first, so the detail is the fourth item. */
private const val DETAIL_INDEX = 3

@Composable
private fun Header(state: EvalsUiState, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.evals_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.evals_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.loadError?.let { ErrorNote(stringResource(R.string.evals_load_error, it), LiveRegionMode.Polite) }
        state.runError?.let { ErrorNote(stringResource(R.string.evals_run_error, it), LiveRegionMode.Assertive) }
        if (state.names.isNotEmpty()) FixturePicker(state.groups, state.selected.orEmpty(), onSelect)
        RunStatus(state)
    }
}

/** An error line with a glyph (never colour alone) that screen readers announce when it appears. */
@Composable
private fun ErrorNote(text: String, mode: LiveRegionMode) {
    Row(
        Modifier.semantics(mergeDescendants = true) {
            liveRegion = mode
            contentDescription = text
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("✕", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        Text(text, color = MaterialTheme.colorScheme.error)
    }
}

/** One polite sentence about the selected fixture's run, announced when the selection changes. */
@Composable
private fun RunStatus(state: EvalsUiState) {
    val run = state.run
    val text = when {
        state.invalidSuite != null -> stringResource(R.string.evals_status_invalid)
        run != null -> stringResource(
            R.string.evals_status_run,
            pluralStringResource(R.plurals.evals_cases, run.cases.size, run.cases.size),
            pluralStringResource(R.plurals.evals_targets, run.targets.size, run.targets.size),
        )
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun groupLabel(group: EvalsGroup): String = when (group) {
    EvalsGroup.STRING -> stringResource(R.string.evals_group_string)
    EvalsGroup.LENGTH -> stringResource(R.string.evals_group_length)
    EvalsGroup.JSON -> stringResource(R.string.evals_group_json)
    EvalsGroup.JUDGE -> stringResource(R.string.evals_group_judge)
    EvalsGroup.RUN -> stringResource(R.string.evals_group_run)
    EvalsGroup.INVALID -> stringResource(R.string.evals_group_invalid)
    EvalsGroup.OTHER -> stringResource(R.string.evals_group_other)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixturePicker(groups: List<Pair<EvalsGroup, List<String>>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.evals_fixture_picker, selected)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.evals_fixture)) },
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

/** The suite was rejected before any call: the reason (raw, not pinned by the contract) and the zero call count. */
@Composable
private fun InvalidCard(reason: String, calls: Int) {
    Card(stringResource(R.string.evals_invalid_title)) {
        Text(stringResource(R.string.evals_invalid_body))
        Section(stringResource(R.string.evals_invalid_reason))
        CodeBlock(reason)
        KeyValue(stringResource(R.string.evals_calls_title), pluralStringResource(R.plurals.evals_calls, calls, calls))
    }
}
