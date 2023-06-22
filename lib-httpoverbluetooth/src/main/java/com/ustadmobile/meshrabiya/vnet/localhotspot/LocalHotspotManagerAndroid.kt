package com.ustadmobile.meshrabiya.vnet.localhotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.groupToHotspotConfigCompat
import com.ustadmobile.meshrabiya.ext.toPrettyString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

data class WifiDirectState(
    val state: Int,
) {
    val stateEnabled: Boolean
        get() = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
}

/**
 * Close the hotspot when there are no connections depending on it
 */
class LocalHotspotManagerAndroid(
    private val appContext: Context,
    private val logger: com.ustadmobile.meshrabiya.MNetLogger,
    private val localNodeAddr: Int,
) : Closeable, LocalHotspotManager, WifiP2pManager.ChannelListener {

    private val logPrefix = "[LocalHotspotManagerAndroid: ${localNodeAddr.addressToDotNotation()}] "

    private val wifiDirectBroadcastReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    logger(
                        Log.DEBUG, "$logPrefix p2p state changed: " +
                            "enabled=${state == WifiP2pManager.WIFI_P2P_STATE_ENABLED} ", null
                    )

                    //If Wifi has been disabled, make sure to
                    _state.takeIf { state == WifiP2pManager.WIFI_P2P_STATE_DISABLED }?.update { prev ->
                        prev.copy(
                            status = LocalHotspotStatus.STOPPED,
                            config = null,
                        )
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val extraGroup: WifiP2pGroup? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    logger(Log.DEBUG, "wifi p2p connection changed action: group=${extraGroup?.toPrettyString()}", null)
                }
            }
        }
    }



    private val wifiP2pGroupInfoListener = WifiP2pManager.GroupInfoListener { group: WifiP2pGroup? ->
        logger(Log.DEBUG, "P2P Group Info Available: ${group?.toPrettyString()}", null)

        //maybe group.networkId can help us get the network object?
        _state.update { prev ->
            prev.copy(
                status = LocalHotspotStatus.STARTED,
                errorCode = 0,
                config = group?.groupToHotspotConfigCompat()
            )
        }
    }

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)

    val wifiP2pManager: WifiP2pManager? by lazy {
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    }

    var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(LocalHotspotState(LocalHotspotStatus.STOPPED))

    override val state: Flow<LocalHotspotState> = _state.asStateFlow()

    private val requestMutex = Mutex()

    init {
        logger(Log.DEBUG, "$logPrefix init", null)
        channel = wifiP2pManager?.initialize(appContext, Looper.getMainLooper(), this)
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        appContext.registerReceiver(wifiDirectBroadcastReceiver, intentFilter)
    }

    override fun onChannelDisconnected() {
        channel = null
    }

    override val is5GhzSupported: Boolean
        get() = wifiManager.is5GHzBandSupported

    override suspend fun request(request: LocalHotspotRequest): LocalHotspotRequestResult {
        requestMutex.withLock {
            withContext(Dispatchers.Main) {
                if(_state.value.status == LocalHotspotStatus.STOPPED) {
                    _state.update { prev ->
                        prev.copy(status = LocalHotspotStatus.STARTING)
                    }

                    logger(Log.DEBUG, "$logPrefix Requesting WifiP2PGroup", null)
                    wifiP2pManager?.createGroup(channel, object: WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            logger(Log.DEBUG, "$logPrefix WifiP2PGroup:onSuccess", null)
                            wifiP2pManager?.requestGroupInfo(channel, wifiP2pGroupInfoListener)
                        }

                        override fun onFailure(reason: Int) {
                            logger(
                                Log.ERROR,
                                "WifiP2pGroup: ONFailure ${WifiP2pFailure.reasonToString(reason)}",
                                null
                            )

                            _state.update { prev ->
                                prev.copy(
                                    status = LocalHotspotStatus.STOPPED,
                                    errorCode = reason,
                                    config = null
                                )
                            }
                        }
                    })
                }
            }
        }

        val configResult = _state.filter {
            it.status == LocalHotspotStatus.STARTED || it.errorCode != 0
        }.first()

        return LocalHotspotRequestResult(configResult.errorCode, configResult.config)
    }

    override fun close() {
        if(Build.VERSION.SDK_INT >= 27) {
            channel?.close()
        }
        appContext.unregisterReceiver(wifiDirectBroadcastReceiver)
        channel = null
    }


}