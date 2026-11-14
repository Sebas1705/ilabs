package es.sebas1705.ilabs.demo

import android.content.res.AssetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Reads every fixture of one module from the assets, where the copyContracts task puts the contracts folder. */
private fun AssetManager.loaderFor(module: String) = FixtureLoader {
    val dir = "contracts/$module"
    list(dir).orEmpty()
        .filter { it.endsWith(".json") }
        .map { name -> open("$dir/$name").bufferedReader().use { it.readText() } }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val providersLoader = assets.loaderFor("providers")
        val structuredLoader = assets.loaderFor("structured")
        val contextLoader = assets.loaderFor("context")
        val evalsLoader = assets.loaderFor("evals")
        setContent {
            ILabsTheme {
                // Saved state, so the open module survives rotation, process death and "Don't keep activities".
                var module by rememberSaveable { mutableStateOf<DemoModule?>(null) }
                BackHandler(enabled = module != null) { module = null }
                when (module) {
                    null -> ModuleHome(onOpen = { module = it })
                    DemoModule.PROVIDERS -> {
                        val vm: ProvidersViewModel = viewModel(factory = ProvidersViewModel.factory(providersLoader))
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        ProvidersScreen(state, vm::select, onBack = { module = null })
                    }
                    DemoModule.STRUCTURED -> {
                        val vm: StructuredViewModel = viewModel(factory = StructuredViewModel.factory(structuredLoader))
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        StructuredScreen(state, vm::select, onBack = { module = null })
                    }
                    DemoModule.CONTEXT -> {
                        val vm: ContextViewModel = viewModel(factory = ContextViewModel.factory(contextLoader))
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        ContextScreen(state, vm::select, vm::setBudget, onBack = { module = null })
                    }
                    DemoModule.EVALS -> {
                        val vm: EvalsViewModel = viewModel(factory = EvalsViewModel.factory(evalsLoader))
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        EvalsScreen(state, EvalsActions.of(vm), onBack = { module = null })
                    }
                }
            }
        }
    }
}
