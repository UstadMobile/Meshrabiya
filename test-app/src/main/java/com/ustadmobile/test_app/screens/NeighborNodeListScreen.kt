package com.ustadmobile.test_app.screens

import android.Manifest

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
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

    NeighborNodeListScreen(uiState)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeighborNodeListScreen(
    uiState: NeighborNodeListUiState
) {
    LazyColumn() {
        items(
            items = uiState.neighborNodes,
            key = { it.remoteAddress }
        ) { node ->
            ListItem(
                headlineText = {
                    Text(node.remoteAddress.addressToDotNotation())
                },
                supportingText = {
                    Column {
                        Row {
                            if(node.hasBluetoothConnection) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = "Bluetooth"
                                )
                            }

                            if(node.hasWifiConnection) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = "Wifi"
                                )
                            }
                        }
                        Text("Ping: ${node.pingTime}ms Received ${node.pingsReceived}/${node.pingsSent}")
                    }

                },
                trailingContent = {
                    var expanded by remember { mutableStateOf(false) }

                    IconButton(
                        onClick = { expanded = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options"
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = {  expanded = false }
                        ) {
                            DropdownMenuItem(
                                onClick = {
                                    expanded = false
                                },
                                text = {
                                    Text("Request Wifi Hotspot")
                                }
                            )
                        }
                    }
                }
            )
        }
    }

}
