package de.astral.smbbackup.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_STATUS = 0
        const val TAB_SETTINGS = 1
        const val TAB_LOG = 2
    }

    private var tab by mutableIntStateOf(TAB_STATUS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) tab = intent.getIntExtra(EXTRA_TAB, TAB_STATUS)
        setContent {
            SmbBackupTheme {
                AppRoot(tab = tab, onTab = { tab = it })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab = intent.getIntExtra(EXTRA_TAB, tab)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(tab: Int, onTab: (Int) -> Unit, vm: MainViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SMB Backup") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_STATUS,
                    onClick = { onTab(MainActivity.TAB_STATUS) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Status") },
                )
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_SETTINGS,
                    onClick = { onTab(MainActivity.TAB_SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Einstellungen") },
                )
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_LOG,
                    onClick = { onTab(MainActivity.TAB_LOG) },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Protokoll") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                MainActivity.TAB_SETTINGS -> SettingsScreen(vm)
                MainActivity.TAB_LOG -> LogScreen(vm)
                else -> StatusScreen(vm, openSettings = { onTab(MainActivity.TAB_SETTINGS) })
            }
        }
    }
}
