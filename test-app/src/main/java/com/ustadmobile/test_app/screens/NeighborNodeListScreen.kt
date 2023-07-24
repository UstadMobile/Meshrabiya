package com.ustadmobile.test_app.screens

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.vnet.MeshrabiyaConnectLink
import com.ustadmobile.test_app.ViewModelFactory
import com.ustadmobile.test_app.appstate.AppUiState
import com.ustadmobile.test_app.composable.rememberConnectLauncher
import com.ustadmobile.test_app.viewmodel.NeighborNodeListUiState
import com.ustadmobile.test_app.viewmodel.NeighborNodeListViewModel
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun NeighborNodeListScreen(
    viewModel: NeighborNodeListViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                NeighborNodeListViewModel(it)
            },
            defaultArgs = null,
        )
    ),
    onSetAppUiState: (AppUiState) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState(NeighborNodeListUiState())
    val di = localDI()

    val connectLauncher = rememberConnectLauncher(
        onConnectBluetooth = {
            viewModel.onConnectBluetooth(it.address)
        },
        onConnectWifi = {
            viewModel.onConnectWifi(it)
        }
    )

    val qrCodeScannerLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        val link = result.contents
        if(link != null) {
            try {
                val connectLink = MeshrabiyaConnectLink.parseUri(
                    uri = link,
                    json = di.direct.instance(),
                )

                connectLauncher.launch(connectLink)

            }catch(e: Exception) {
                Log.e("TestApp", "Exception", e)
            }
        }

    }

    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if(granted) {
            qrCodeScannerLauncher.launch(ScanOptions().apply {
                setOrientationLocked(false)
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            })
        }
    }

    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(
            uiState.appUiState.copy(
                fabState = uiState.appUiState.fabState.copy(
                    onClick = {
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    }
                )
            )
        )
    }

    NeighborNodeListScreen(
        uiState = uiState,
        onClickFilter = viewModel::onClickFilterChip
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeighborNodeListScreen(
    uiState: NeighborNodeListUiState,
    onClickFilter: (NeighborNodeListUiState.Companion.Filter) -> Unit = { },
) {
    LazyColumn() {
        item(key = "filterchips") {
            Row(modifier = Modifier.padding(horizontal = 8.dp)){
                NeighborNodeListUiState.Companion.Filter.values().forEach { filter ->
                    FilterChip(
                        modifier = Modifier.padding(8.dp),
                        selected = uiState.filter == filter,
                        onClick = {
                            onClickFilter(filter)
                        },
                        label = {
                            Text(filter.label)
                        }
                    )
                }
            }
        }

        items(
            items = uiState.nodes.entries.toList() ,
            key = { it.key }
        ) { nodeEntry ->
            ListItem(
                headlineContent = {
                    Text(nodeEntry.key.addressToDotNotation())
                },
                supportingContent = {
                    Text("Ping ${nodeEntry.value.originatorMessage.pingTimeSum}ms " +
                            " Hops: ${nodeEntry.value.hopCount} ")
                }
            )
        }

    }

}
