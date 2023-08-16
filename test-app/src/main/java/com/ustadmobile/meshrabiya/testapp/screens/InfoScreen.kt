package com.ustadmobile.meshrabiya.testapp.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material3.Icon
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.testapp.MNetLoggerAndroid
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.meshrabiyaDeviceInfoStr
import com.ustadmobile.meshrabiya.testapp.viewmodel.InfoUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.InfoViewModel
import org.kodein.di.compose.localDI
import org.kodein.di.instance


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
    onClickLicenses: () -> Unit = { },
) {
    val uiState by viewModel.uiState.collectAsState(initial = InfoUiState())
    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState)
    }

    InfoScreen(uiState, onClickLicenses)
}

@Composable
fun InfoScreen(
    uiState: InfoUiState,
    onClickLicenses: () -> Unit,
) {
    val context = LocalContext.current

    val deviceInfo = remember {
        context.meshrabiyaDeviceInfoStr()
    }

    val localDi = localDI()
    val logger: MNetLogger by localDi.instance()
    val androidLogger = logger as MNetLoggerAndroid

    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        item("copyright") {
            ListItem(
                headlineContent = {
                    Text(text = "Meshrabiya - ${MeshrabiyaConstants.VERSION}")
                },
                supportingContent = {
                    Text("Copyright 2023 UstadMobile FZ-LLC. This software is free and open " +
                            "source, licensed under the LGPLv3.0 license " +
                            "as per https://www.gnu.org/licenses/lgpl-3.0.en.html")
                }
            )
        }

        item("opensourcelicenses") {
            ListItem(
                modifier = Modifier.clickable {
                    onClickLicenses()
                },
                headlineContent = {
                    Text("View open source component licenses")
                }
            )
        }

        item("deviceinfo") {
            ListItem(
                headlineContent = {
                    Text("Device Info")
                },
                supportingContent = {
                    Text(deviceInfo)
                }
            )
        }

        item("logheader") {
            ListItem(
                modifier = Modifier.clickable {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    val clip: ClipData = ClipData.newPlainText("Logs",
                        androidLogger.exportAsString(context)
                    )

                    clipboard.setPrimaryClip(clip)
                    Toast
                        .makeText(context, "Copied logs!", Toast.LENGTH_LONG)
                        .show()

                },
                headlineContent = {
                    Text("Logs")
                },
                trailingContent = {
                    Icon(imageVector = Icons.Default.CopyAll, contentDescription = "Copy")
                }
            )
        }

        items(
            items = uiState.recentLogs,
            key = { it.lineId }
        ) {
            ListItem(
                headlineContent = {
                    Text(it.toString(androidLogger.epochTime))
                }
            )
        }
    }
}
