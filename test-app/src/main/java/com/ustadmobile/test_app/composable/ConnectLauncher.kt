package com.ustadmobile.test_app.composable

import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.ustadmobile.meshrabiya.vnet.MeshrabiyaConnectLink
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.ustadmobile.meshrabiya.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotConfig
import com.ustadmobile.test_app.NEARBY_WIFI_PERMISSION_NAME

fun interface ConnectLauncher {
    fun launch(connectLink: MeshrabiyaConnectLink)
}

/**
 * Attempt to manage the callback hell required to request permissions and associate via
 * companion device manager, etc for Bluetooth and WiFi,
 *
 * val connectLauncher = rememberConnectLauncher(
 *   onConnectBluetooth = { device ->
 *       .. connect via bluetooth
 *   },
 *   onConnectWifi = { hotspotConfig ->
 *       .. connect via wifi
 *   }
 * )
 *
 * connectLauncher.launch(link)
 *
 */
@Composable
fun rememberConnectLauncher(
    onConnectBluetooth: (BluetoothDevice) -> Unit,
    onConnectWifi: (HotspotConfig) -> Unit,
) : ConnectLauncher {
    var pendingConnectLink: MeshrabiyaConnectLink? by remember {
        mutableStateOf(null)
    }

    var pendingBluetoothConfig: MeshrabiyaBluetoothState? by remember {
        mutableStateOf(null)
    }

    var pendingHotspotConfig: HotspotConfig? by remember {
        mutableStateOf(null)
    }

    val requestWifiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val hotspotConfigVal = pendingHotspotConfig
        if(granted) {
            hotspotConfigVal?.also(onConnectWifi)
        }
        pendingHotspotConfig = null
        pendingConnectLink = null
    }

    val connectBluetoothLauncher = rememberBluetoothConnectLauncher {
        it.device?.also { device ->
            onConnectBluetooth(device)
        }
        pendingBluetoothConfig = null

        pendingHotspotConfig = pendingConnectLink?.hotspotConfig
    }

    LaunchedEffect(pendingBluetoothConfig) {
        val btConfig = pendingBluetoothConfig ?: return@LaunchedEffect
        Log.d(LOG_TAG, "ConnectLauncher: launch bluetooth connector")
        connectBluetoothLauncher.launch(btConfig)
    }

    LaunchedEffect(pendingHotspotConfig) {
        if(pendingHotspotConfig == null)
            return@LaunchedEffect

        requestWifiPermissionLauncher.launch(NEARBY_WIFI_PERMISSION_NAME)
    }

    LaunchedEffect(pendingConnectLink) {
        Log.d(LOG_TAG, "ConnectLauncher: Pending Connect Link: ${pendingConnectLink} " +
                "bluetooth=${pendingConnectLink?.bluetoothConfig}")
        val linkVal = pendingConnectLink ?: return@LaunchedEffect

        pendingBluetoothConfig = linkVal.bluetoothConfig

    }

    return ConnectLauncher {
        Log.d(LOG_TAG, "ConnectLauncher: ${it.uri}")
        pendingConnectLink = it
    }
}