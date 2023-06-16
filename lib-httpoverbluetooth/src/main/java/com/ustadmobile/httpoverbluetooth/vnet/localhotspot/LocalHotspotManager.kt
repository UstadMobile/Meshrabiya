package com.ustadmobile.httpoverbluetooth.vnet.localhotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.ustadmobile.httpoverbluetooth.MNetLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

data class LocalHotspotState(
    val status: LocalHotspotStatus,
    val config: LocalHotspotConfigCompat? = null,
    val errorCode: Int = 0,
)

data class LocalHotspotSubReservation(
    val id: Int,
    val hotspotState: LocalHotspotState,
)

class LocalHotspotManager(
    private val appContext: Context,
    private val logger: MNetLogger,
) {

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)

    private val _state = MutableStateFlow(LocalHotspotState(LocalHotspotStatus.STOPPED))

    val state: Flow<LocalHotspotState> = _state.asStateFlow()

    private val requestMutex = Mutex()

    private val reservationIdAtomic = AtomicInteger(1)

    private val mCallback = object: WifiManager.LocalOnlyHotspotCallback() {

        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
            logger(Log.DEBUG, "LocalHotspotManager.onStarted ", null)
            _state.value = LocalHotspotState(
                status = LocalHotspotStatus.STARTED,
                config = reservation?.toLocalHotspotConfig(),
                errorCode = 0,
            )
        }

        override fun onStopped() {
            logger(Log.DEBUG, "LocalHotspotManager.onStopped ", null)
            _state.value = LocalHotspotState(
                status = LocalHotspotStatus.STOPPED
            )
        }

        override fun onFailed(reason: Int) {
            logger(Log.DEBUG, "LocalHotspotManager.onFailed : reason=$reason", null)
            _state.value = LocalHotspotState(
                status = LocalHotspotStatus.STOPPED,
                errorCode = reason,
            )
        }
    }

    suspend fun request(): LocalHotspotSubReservation {
        requestMutex.withLock {
            if(_state.value.status == LocalHotspotStatus.STOPPED) {
                _state.update { prev ->
                    prev.copy(status = LocalHotspotStatus.STARTING)
                }

                wifiManager.startLocalOnlyHotspot(mCallback, null)
            }
        }

        val configResult = _state.filter {
            it.status == LocalHotspotStatus.STARTED || it.errorCode != 0
        }.first()

        if(configResult.errorCode != 0)
            throw IllegalStateException("Failed to create local only hotspot: ${configResult.errorCode}")

        return LocalHotspotSubReservation(reservationIdAtomic.getAndIncrement(), configResult)
    }

    fun close() {

    }


}