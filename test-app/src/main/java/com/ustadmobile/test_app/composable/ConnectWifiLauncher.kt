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
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.ustadmobile.meshrabiya.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.test_app.NEARBY_WIFI_PERMISSION_NAME
import org.kodein.di.DI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import java.util.regex.Pattern


fun interface ConnectWifiLauncher {

    fun launch(config: WifiConnectConfig)

}

data class ConnectWifiLauncherResult(
    val hotspotConfig: WifiConnectConfig?
) {

}

/**
 * Handle asking for permission and using companion device manager (if needed) to connect to a hotspot.
 */
@Composable
fun rememberConnectWifiLauncher(
    onResult: (ConnectWifiLauncherResult) -> Unit,
): ConnectWifiLauncher {

    val context = LocalContext.current
    val di: DI = localDI()


    var pendingPermissionHotspotConfig: WifiConnectConfig? by remember {
        mutableStateOf(null)
    }

    var pendingAssociateHotspotConfig: WifiConnectConfig? by remember {
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
            val bssid = scanResult?.BSSID

            if(bssid != null) {
                onResult(ConnectWifiLauncherResult(
                    hotspotConfig = pendingAssociateHotspotConfig?.copy(
                        bssid = bssid
                    )
                ))
            }else {
                onResult(ConnectWifiLauncherResult(
                    hotspotConfig = null
                ))
            }
        }else {
            onResult(ConnectWifiLauncherResult(
                hotspotConfig = null
            ))
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val hotspotConfigVal = pendingPermissionHotspotConfig ?: return@rememberLauncherForActivityResult
        if(granted) {
            pendingAssociateHotspotConfig = hotspotConfigVal
        }else {
            onResult(ConnectWifiLauncherResult(
                hotspotConfig = null
            ))
        }
    }

    LaunchedEffect(pendingAssociateHotspotConfig) {
        val hotspotConfigVal = pendingPermissionHotspotConfig ?: return@LaunchedEffect
        val node: AndroidVirtualNode = di.direct.instance()
        val storedBssid = node.lookupStoredBssid(hotspotConfigVal.nodeVirtualAddr)

        if(storedBssid != null) {
            onResult(
                ConnectWifiLauncherResult(
                    hotspotConfig = hotspotConfigVal.copy(
                        bssid = storedBssid
                    )
                )
            )
        }else {
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
                        onResult(ConnectWifiLauncherResult(hotspotConfig = null))
                    }
                },
                null
            )
        }
    }

    LaunchedEffect(pendingPermissionHotspotConfig) {
        if(pendingPermissionHotspotConfig == null)
            return@LaunchedEffect

        requestPermissionLauncher.launch(NEARBY_WIFI_PERMISSION_NAME)
    }


    return ConnectWifiLauncher {
        //Note: If the permission is already granted, requestPermission can call back immediately
        // synchronously to the launcher's onResult. This would cause a problem because the mutable
        // state wouldn't be updated until the next function invocation.


        pendingPermissionHotspotConfig = it
    }
}