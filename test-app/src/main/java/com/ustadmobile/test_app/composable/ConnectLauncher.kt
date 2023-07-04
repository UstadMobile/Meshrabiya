package com.ustadmobile.test_app.composable

import android.bluetooth.BluetoothDevice
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.ustadmobile.meshrabiya.vnet.MeshrabiyaConnectLink
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ustadmobile.meshrabiya.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotConfig

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

    var pendingHotspotConnect: HotspotConfig? by remember {
        mutableStateOf(null)
    }

    val context = LocalContext.current

    val connectWifiLauncher = rememberConnectWifiLauncher {
        val pendingHotspotConnectVal = pendingHotspotConnect ?: return@rememberConnectWifiLauncher
        val hotspotConfigVal = it.hotspotConfig
        if(hotspotConfigVal != null) {
            onConnectWifi(hotspotConfigVal)
            pendingBluetoothConfig = null
        }else {
            Toast.makeText(context, "Companion Device Manager: network not found", Toast.LENGTH_LONG).show()
        }
    }

    val connectBluetoothLauncher = rememberBluetoothConnectLauncher {
        it.device?.also { device ->
            onConnectBluetooth(device)
        }
        pendingBluetoothConfig = null

        pendingHotspotConnect = pendingConnectLink?.hotspotConfig
    }

    LaunchedEffect(pendingBluetoothConfig) {
        val btConfig = pendingBluetoothConfig ?: return@LaunchedEffect
        Log.d(LOG_TAG, "ConnectLauncher: launch bluetooth connector")
        connectBluetoothLauncher.launch(btConfig)
    }

    LaunchedEffect(pendingHotspotConnect) {
        val hotspotConfig = pendingHotspotConnect ?: return@LaunchedEffect
        connectWifiLauncher.launch(hotspotConfig)
    }

    LaunchedEffect(pendingConnectLink) {
        Log.d(LOG_TAG, "ConnectLauncher: Pending Connect Link: ${pendingConnectLink} " +
                "bluetooth=${pendingConnectLink?.bluetoothConfig}")
        val linkVal = pendingConnectLink ?: return@LaunchedEffect

        //pendingBluetoothConfig = linkVal.bluetoothConfig
        pendingHotspotConnect = linkVal.hotspotConfig
    }

    return ConnectLauncher {
        Log.d(LOG_TAG, "ConnectLauncher: ${it.uri}")
        pendingConnectLink = it
    }
}