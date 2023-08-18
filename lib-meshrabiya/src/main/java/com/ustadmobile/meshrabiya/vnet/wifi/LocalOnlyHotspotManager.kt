package com.ustadmobile.meshrabiya.vnet.wifi

import android.content.Context
import android.net.MacAddress
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ustadmobile.meshrabiya.ext.encodeAsHex
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.wifi.UnhiddenSoftApConfigurationBuilder.Companion.RANDOMIZATION_NONE
import com.ustadmobile.meshrabiya.vnet.wifi.UnhiddenSoftApConfigurationBuilder.Companion.SECURITY_TYPE_WPA2_PSK
import com.ustadmobile.meshrabiya.vnet.wifi.state.LocalOnlyHotspotState
import inet.ipaddr.mac.MACAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.net.NetworkInterface

class LocalOnlyHotspotManager(
    appContext: Context,
    private val logger: MNetLogger,
    name: String,
    private val localNodeAddr: Int,
    private val router: VirtualRouter,
    private val dataStore: DataStore<Preferences>,
) {

    private val logPrefix: String = "[LocalOnlyHotspotManager: $name]"

    private val _state = MutableStateFlow(LocalOnlyHotspotState())

    val state: Flow<LocalOnlyHotspotState> = _state.asStateFlow()

    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val macAddrPrefKey = stringPreferencesKey("localonly_macaddr")

    private val localOnlyHotspotCallback = object: WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
            logger(Log.DEBUG, "$logPrefix localonlyhotspotcallback: onStarted", null)
            localOnlyHotspotReservation = reservation
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            logger(Log.DEBUG, "$logPrefix - Mac Addresses are : ${interfaces.joinToString { netIf ->
                netIf.name + ": " + netIf.hardwareAddress?.let { MACAddress(it).toString() } }
            }")

            _state.takeIf { reservation != null }?.update { prev ->
                prev.copy(
                    status = HotspotStatus.STARTED,
                    config = reservation?.toLocalHotspotConfig(
                                nodeVirtualAddr = localNodeAddr,
                                port = router.localDatagramPort,
                                logger = logger,
                            ),
                )
            }
        }

        override fun onStopped() {
            logger(Log.DEBUG, "$logPrefix localonlyhotspotcallback: onStopped", null)
            localOnlyHotspotReservation = null
            _state.update { prev ->
                prev.copy(
                    status = HotspotStatus.STOPPED,
                    config = null,
                )
            }
        }

        override fun onFailed(reason: Int) {
            logger(Log.ERROR, "$logPrefix localOnlyhotspotcallback : onFailed: " +
                    LocalOnlyHotspotState.errorCodeToString(reason), null
            )

            _state.update { prev ->
                prev.copy(
                    status = HotspotStatus.STOPPED,
                    error = reason,
                )
            }
        }
    }

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)

    suspend fun startLocalOnlyHotspot(
        preferredBand: ConnectBand,
    ) {
        logger(Log.INFO, "$logPrefix startLocalOnlyHotspot: band=$preferredBand")
        if(Build.VERSION.SDK_INT >= 33) {
            val macAddr = dataStore.data.map {
                it[macAddrPrefKey]
            }.first()?.let { MacAddress.fromString(it )} ?: generateRandomMacAddress().also { newMac ->
                dataStore.edit {
                    it[macAddrPrefKey] = newMac.toString()
                }
            }

            val config = UnhiddenSoftApConfigurationBuilder()
                .setAutoshutdownEnabled(false)
                .apply {
                    if(preferredBand == ConnectBand.BAND_5GHZ) {
                        setBand(ScanResult.WIFI_BAND_5_GHZ)
                    }else if(preferredBand == ConnectBand.BAND_2GHZ) {
                        setBand(ScanResult.WIFI_BAND_24_GHZ)
                    }
                }
                .setSsid("meshr-${localNodeAddr.encodeAsHex()}")
                .setPassphrase("meshtest12", SECURITY_TYPE_WPA2_PSK)
                .setBssid(MacAddress.fromString("a4:64:83:68:c2:76"))
                .setMacRandomizationSetting(RANDOMIZATION_NONE)
                .build()
            _state.update { prev ->
                prev.copy(
                    status = HotspotStatus.STARTING
                )
            }
            wifiManager.startLocalOnlyHotspotWithConfig(config, null, localOnlyHotspotCallback)
            logger(Log.INFO, "$logPrefix startLocalOnlyHotspot: request submitted")
            _state.filter { it.status.isSettled() }.first()
        }else {
            _state.update { prev ->
                prev.copy(
                    status = HotspotStatus.STARTING
                )
            }

            wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
        }
    }


}