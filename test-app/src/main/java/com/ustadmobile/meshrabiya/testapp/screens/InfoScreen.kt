package com.ustadmobile.meshrabiya.testapp.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.InfoUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.InfoViewModel
import org.kodein.di.compose.localDI


@Composable
fun InfoScreen(
    viewModel: InfoViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                InfoViewModel(it)
            },
            defaultArgs = null,
        )
    ),
    onSetAppUiState: (AppUiState) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState(initial = InfoUiState())
    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState)
    }

    InfoScreen(uiState)
}

@Composable
fun InfoScreen(
    uiState: InfoUiState
) {
    val context = LocalContext.current
    val wifiManager = remember {
        context.getSystemService(WifiManager::class.java)
    }
    val is5GhzSupported = remember(wifiManager) {
        wifiManager.is5GHzBandSupported
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        item("5ghz") {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                text= "5Ghz supported: $is5GhzSupported"
            )
        }

        item("logheader") {
            Text(
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    val clip: ClipData = ClipData.newPlainText("Logs",
                        "===HttpOverBluetooth===\n" +
                                uiState.recentLogs.joinToString(separator = ",\n") { it.line }
                    )
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied logs!", Toast.LENGTH_LONG).show()
                }
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
                text = "Logs"
            )
        }

        items(
            items = uiState.recentLogs,
            key = { it.lineId }
        ) {
            ListItem(
                headlineContent = {
                    Text(it.line)
                }
            )
        }
    }
}
