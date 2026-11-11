package es.sebas1705.ilabs.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Fixtures come from the contracts folder, copied into the assets by the copyProvidersFixtures task.
        val loader = FixtureLoader {
            val dir = "fixtures"
            assets.list(dir).orEmpty()
                .filter { it.endsWith(".json") }
                .map { name -> assets.open("$dir/$name").bufferedReader().use { it.readText() } }
        }
        setContent {
            ILabsTheme {
                val vm: ProvidersViewModel = viewModel(factory = ProvidersViewModel.factory(loader))
                val state by vm.uiState.collectAsStateWithLifecycle()
                ProvidersScreen(state, vm::select)
            }
        }
    }
}
