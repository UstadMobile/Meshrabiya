package com.ustadmobile.meshrabiya.testapp.composable

import android.app.Activity
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.IntentSender
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
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
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.testapp.NEARBY_WIFI_PERMISSION_NAME
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectException
import java.util.regex.Pattern


fun interface ConnectWifiLauncher {

    fun launch(config: WifiConnectConfig)

}

data class ConnectRequest(
    val receivedTime: Long = 0,
    val connectConfig: WifiConnectConfig,
)

data class ConnectWifiLauncherResult(
    val hotspotConfig: WifiConnectConfig?,
    val exception: Exception? = null,
)

enum class ConnectWifiLauncherStatus {

    INACTIVE, REQUESTING_PERMISSION, LOOKING_FOR_NETWORK, REQUESTING_LINK,

}

/**
 * Handle asking for permission and using companion device manager (if needed) to connect to a hotspot.
 *
 * This is one-at-a-time : the intent sender launcher and permission launcher do not allow
 * tracking which request is being answered.
 */
@Composable
fun rememberMeshrabiyaConnectLauncher(
    node: AndroidVirtualNode,
    logger: MNetLogger? = null,
    onStatusChange: ((ConnectWifiLauncherStatus) -> Unit)? = null,
    onResult: (ConnectWifiLauncherResult) -> Unit,
): ConnectWifiLauncher {
    val context = LocalContext.current

    val wifiManager: WifiManager = remember {
        context.getSystemService(WifiManager::class.java)
    }

    var pendingPermissionRequest: ConnectRequest? by remember {
        mutableStateOf(null)
    }

    var pendingAssociationRequest: ConnectRequest? by remember {
        mutableStateOf(null)
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val deviceManager : CompanionDeviceManager =
            context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        val associations = deviceManager.associations
        logger?.invoke(Log.INFO, "associations = ${associations.joinToString()}")
        val request = pendingAssociationRequest ?: return@rememberLauncherForActivityResult
        pendingAssociationRequest = null

        onStatusChange?.invoke(ConnectWifiLauncherStatus.INACTIVE)
        if(result.resultCode == Activity.RESULT_OK) {
            val scanResult: ScanResult? = result.data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_DEVICE
            )

            logger?.invoke(Log.INFO, "rememberConnectWifiLauncher: Got scan result: bssid = ${scanResult?.BSSID}")
            node.storeBssid(request.connectConfig.ssid, scanResult?.BSSID)

            onResult(
                ConnectWifiLauncherResult(
                    hotspotConfig = request.connectConfig.copy(
                        bssid = scanResult?.BSSID
                    )
                )
            )
        }else {
            onResult(
                ConnectWifiLauncherResult(
                    hotspotConfig = null,
                    exception = WifiConnectException("CompanionDeviceManager: device not found / not selected")
                )
            )
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val connectRequestVal = pendingPermissionRequest ?: return@rememberLauncherForActivityResult
        pendingPermissionRequest = null
        if(granted) {
            logger?.invoke(Log.DEBUG, "ConnectWifiLauncher: permission granted")
            pendingAssociationRequest = connectRequestVal
        }else {
            onResult(
                ConnectWifiLauncherResult(
                    hotspotConfig = null,
                    exception = WifiConnectException("Permission denied: permission not granted"),
                )
            )
        }
    }

    LaunchedEffect(pendingAssociationRequest) {
        val connectRequestVal = pendingAssociationRequest ?: return@LaunchedEffect
        val ssid = connectRequestVal.connectConfig.ssid
        logger?.invoke(Log.DEBUG, "ConnectWifiLauncher: check for assocation with $ssid")

        val macAddr = connectRequestVal.connectConfig.linkLocalToMacAddress
        logger?.invoke(Log.DEBUG, "ConnectWifiLauncher: Calculated mac addr = $macAddr")

        val knownBssid: String? = connectRequestVal.connectConfig.bssid ?: macAddr?.toString()
            ?: node.lookupStoredBssid(connectRequestVal.connectConfig.ssid)

        val deviceManager : CompanionDeviceManager =
            context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        if(knownBssid != null) {
            logger?.invoke(Log.DEBUG, "ConnectWifiLauncher: already associated with $ssid (bssid=$knownBssid)")
            onStatusChange?.invoke(ConnectWifiLauncherStatus.INACTIVE)
            onResult(
                ConnectWifiLauncherResult(
                    hotspotConfig = connectRequestVal.connectConfig.copy(
                        bssid = knownBssid
                    )
                )
            )
        }else {
            logger?.invoke(Log.DEBUG,
                "ConnectWifiLauncher: requesting association for $ssid"
            )
            onStatusChange?.invoke(ConnectWifiLauncherStatus.LOOKING_FOR_NETWORK)
            val deviceFilter = WifiDeviceFilter.Builder()
                .setNamePattern(Pattern.compile(Pattern.quote(connectRequestVal.connectConfig.ssid)))
                .build()

            val associationRequest: AssociationRequest = AssociationRequest.Builder()
                .addDeviceFilter(deviceFilter)
                .setSingleDevice(true)
                .build()


            deviceManager.associate(
                associationRequest,
                object: CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(intentSender: IntentSender) {
                        logger?.invoke(Log.DEBUG, "ConnectWifiLauncher: onDeviceFound for $ssid")
                        onStatusChange?.invoke(ConnectWifiLauncherStatus.REQUESTING_LINK)
                        intentSenderLauncher.launch(
                            IntentSenderRequest.Builder(intentSender).build()
                        )
                    }

                    override fun onFailure(reason: CharSequence?) {
                        logger?.invoke(Log.DEBUG, "ConnectWifiLauncher: onFailure for $ssid - $reason")
                        onStatusChange?.invoke(ConnectWifiLauncherStatus.INACTIVE)
                        pendingAssociationRequest = null
                        onResult(
                            ConnectWifiLauncherResult(
                                hotspotConfig = null,
                                exception = WifiConnectException("CompanionDeviceManager: onFailure: $reason")
                            )
                        )
                    }
                },
                null
            )
        }
    }

    LaunchedEffect(pendingPermissionRequest) {
        if(pendingPermissionRequest == null)
            return@LaunchedEffect

        onStatusChange?.invoke(ConnectWifiLauncherStatus.REQUESTING_PERMISSION)
        requestPermissionLauncher.launch(NEARBY_WIFI_PERMISSION_NAME)
    }


    return ConnectWifiLauncher {
        if(it.band == ConnectBand.BAND_5GHZ && !wifiManager.is5GHzBandSupported) {
            //we cannot connect to this
            onResult(
                ConnectWifiLauncherResult(
                    hotspotConfig = null,
                    exception = WifiConnectException("5Ghz not supported: ${it.ssid} is a 5Ghz network")
                )
            )
        }else {
            //Note: If the permission is already granted, requestPermission can call back immediately
            // synchronously to the launcher's onResult. This would cause a problem because the mutable
            // state wouldn't be updated until the next function invocation.
            pendingAssociationRequest = null
            pendingPermissionRequest = ConnectRequest(
                receivedTime = System.currentTimeMillis(),
                connectConfig = it,
            )
        }
    }
}
