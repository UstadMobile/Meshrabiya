package com.ustadmobile.test_app.composable

import android.app.Activity
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.IntentSender
import android.net.wifi.ScanResult
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotConfig
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.ustadmobile.meshrabiya.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.test_app.NEARBY_WIFI_PERMISSION_NAME
import java.util.regex.Pattern


fun interface ConnectWifiLauncher {

    fun launch(hotspotConfig: HotspotConfig)

}

data class ConnectWifiLauncherResult(
    val scanResult: ScanResult?
) {

}

/**
 * Handle asking for permission and using companion device manager to connect to a hotspot.
 */
@Composable
fun rememberConnectWifiLauncher(
    onResult: (ConnectWifiLauncherResult) -> Unit,
): ConnectWifiLauncher {

    val context = LocalContext.current

    var pendingPermissionHotspot: HotspotConfig? by remember {
        mutableStateOf(null)
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val deviceManager : CompanionDeviceManager =
            context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        val associations = deviceManager.associations
        Log.i(LOG_TAG, "associations = ${associations.joinToString()}")


        if(result.resultCode == Activity.RESULT_OK) {
            val scanResult: ScanResult? = result.data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_DEVICE
            )


            Log.i(LOG_TAG, "rememberConnectWifiLauncher: Got scan result: bssid = ${scanResult?.BSSID}")

            onResult(ConnectWifiLauncherResult(
                scanResult = scanResult
            ))
        }else {
            onResult(ConnectWifiLauncherResult(
                scanResult = null
            ))
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val hotspotConfigVal = pendingPermissionHotspot ?: return@rememberLauncherForActivityResult
        if(granted) {
            val deviceFilter = WifiDeviceFilter.Builder()
                .setNamePattern(Pattern.compile(Pattern.quote(hotspotConfigVal.ssid)))
                .build()

            val associationRequest: AssociationRequest = AssociationRequest.Builder()
                .addDeviceFilter(deviceFilter)
                .setSingleDevice(true)
                .build()

            val deviceManager : CompanionDeviceManager =
                context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

            deviceManager.associate(
                associationRequest,
                object: CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(intentSender: IntentSender) {
                        intentSenderLauncher.launch(
                            IntentSenderRequest.Builder(intentSender).build()
                        )
                    }

                    override fun onFailure(reason: CharSequence?) {
                        onResult(ConnectWifiLauncherResult(scanResult = null))
                    }
                },
                null
            )
        }else {
            onResult(ConnectWifiLauncherResult(
                scanResult = null
            ))
        }

        pendingPermissionHotspot = null
    }

    LaunchedEffect(pendingPermissionHotspot) {
        if(pendingPermissionHotspot == null)
            return@LaunchedEffect

        requestPermissionLauncher.launch(NEARBY_WIFI_PERMISSION_NAME)
    }


    return ConnectWifiLauncher {
        //Note: setting this using a state variable ensures that when the permission callback
        // returns the correct value is already set.

        pendingPermissionHotspot = it
    }
}