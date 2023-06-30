package com.ustadmobile.test_app.screens

import android.Manifest
import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.min as mathmin
import com.ustadmobile.test_app.viewmodel.LocalVirtualNodeViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.test_app.NEARBY_WIFI_PERMISSION_NAME
import com.ustadmobile.test_app.ViewModelFactory
import com.ustadmobile.test_app.appstate.AppUiState
import com.ustadmobile.test_app.hasBluetoothConnectPermission
import com.ustadmobile.test_app.hasNearbyWifiDevicesOrLocationPermission
import com.ustadmobile.test_app.viewmodel.LocalVirtualNodeUiState
import org.kodein.di.compose.localDI

@Composable
fun LocalVirtualNodeScreen(
    viewModel: LocalVirtualNodeViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                LocalVirtualNodeViewModel(it)
            },
            defaultArgs = null,
        )
    ),
    onSetAppUiState: (AppUiState) -> Unit,
){
    val uiState: LocalVirtualNodeUiState by viewModel.uiState.collectAsState(
        initial = LocalVirtualNodeUiState()
    )

    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState)
    }

    val context = LocalContext.current

    val requestBluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if(granted) {
            viewModel.onSetIncomingConnectionsEnabled(true)
        }
    }

    val requestNearbyWifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if(granted) {
            if(context.hasBluetoothConnectPermission()) {
                viewModel.onSetIncomingConnectionsEnabled(true)
            }else if(Build.VERSION.SDK_INT >= 31) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    LocalVirtualNodeScreen(
        uiState = uiState,
        onSetIncomingConnectionsEnabled = { enabled ->
            if(enabled && !context.hasNearbyWifiDevicesOrLocationPermission()) {
                requestNearbyWifiPermissionLauncher.launch(NEARBY_WIFI_PERMISSION_NAME)
            }else if(enabled && !context.hasBluetoothConnectPermission() && Build.VERSION.SDK_INT >= 31) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }else {
                viewModel.onSetIncomingConnectionsEnabled(enabled)
            }
        },
    )
}

@Composable
fun LocalVirtualNodeScreen(
    uiState: LocalVirtualNodeUiState,
    onSetIncomingConnectionsEnabled: (Boolean) -> Unit = { },
){

    val barcodeEncoder = remember {
        BarcodeEncoder()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Text(
                text = uiState.localAddress.addressToDotNotation(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item(key = "hotspotswitch") {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .toggleable(
                        role = Role.Switch,
                        value = uiState.incomingConnectionsEnabled,
                        onValueChange = onSetIncomingConnectionsEnabled,
                    )
            ) {
                Switch(checked = uiState.incomingConnectionsEnabled, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text("Allow incoming connections")
            }
        }

        val connectUri = uiState.connectUri
        if(connectUri != null && uiState.incomingConnectionsEnabled) {
            item("qrcode") {
                val config = LocalConfiguration.current
                val screenWidth = config.screenWidthDp
                val screenWidthPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    screenWidth.toFloat(),
                    Resources.getSystem().displayMetrics,
                )
                val width = mathmin(screenWidthPx.toInt(), 600)

                val qrCodeBitmap = remember(connectUri) {
                    barcodeEncoder.encodeBitmap(
                        connectUri, BarcodeFormat.QR_CODE, width, width
                    ).asImageBitmap()
                }

                Image(
                    bitmap = qrCodeBitmap,
                    contentDescription = null
                )
            }
        }

        item(key = "hotspotstate") {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                text = "Local Hotspot: ${uiState.wifiState?.config?.ssid}\n" +
                        "Passphrase: ${uiState.wifiState?.config?.passphrase}\n" +
                        "Port: ${uiState.wifiState?.config?.port}"
            )
        }
    }

}
