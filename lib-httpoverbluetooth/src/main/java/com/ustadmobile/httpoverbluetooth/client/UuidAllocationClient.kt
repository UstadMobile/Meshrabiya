package com.ustadmobile.httpoverbluetooth.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.httpoverbluetooth.RemoteEndpoint
import com.ustadmobile.httpoverbluetooth.UuidUtil
import com.ustadmobile.httpoverbluetooth.server.AbstractHttpOverBluetoothServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException
import java.lang.Exception
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Client that can request a UUID allocation for Bluetooth RFCOMM sockets using GATT (Bluetooth Low
 * Energy). The client can manage communication with multiple different servers simultaneously (e.g.
 * similar to the OKHTTPClient etc). It will ensure that we make only one allocation request at a time.
 */
class UuidAllocationClient(
    private val appContext: Context,
) : Closeable{

    private val lockByRemote = ConcurrentHashMap<RemoteEndpoint, Mutex>()

    private val mapLock = ReentrantLock()

    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private class GetDataUuidGattCallback(
        private val remoteServiceUuid: UUID,
        private val remoteCharacteristicUuid: UUID,
    ) : BluetoothGattCallback() {

        val dataPortUuid = CompletableDeferred<UUID>()

        private val disconnected = AtomicBoolean(false)

        override fun onConnectionStateChange(
            gatt: BluetoothGatt?,
            status: Int,
            newState: Int
        ) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.i(LOG_TAG, "onConnectionStateChange state=$newState status=$status")
            if(newState == BluetoothGatt.STATE_CONNECTED) {
                try {
                    gatt?.discoverServices()
                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val services = gatt.services
            Log.i(LOG_TAG, "services discovered: ${gatt.services.size}")
            gatt.services.forEach { service ->
                Log.i(LOG_TAG, "Service ${service.uuid} characteristics: " +
                        service.characteristics.joinToString { it.uuid.toString() })
            }

            val characteristic = services.firstOrNull { it.uuid == remoteServiceUuid }
                ?.characteristics?.firstOrNull { it.uuid == remoteCharacteristicUuid }

            if(characteristic != null) {
                Log.i(LOG_TAG, "permissions = ${characteristic.permissions}, properties=${characteristic.properties}")
                try {
                    val requestedRead = gatt.readCharacteristic(characteristic)
                    Log.i(LOG_TAG, "found target characteristic - attempt read submitted=$requestedRead")
                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }else {
                Log.w(LOG_TAG, "services discovered, but target characteristic not found")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.i(LOG_TAG, "onCharacteristicRead")

            super.onCharacteristicRead(gatt, characteristic, value, status)
        }

        @Suppress("DeprecatedCallableAddReplaceWith")
        @Deprecated("""
            Might be deprecated, but pre-SDK33 this is the function that gets called, so not much
            we can do about it until SDK32 is obsolete. Gonna be a while.
        """)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.i(LOG_TAG, "onCharacteristicRead (dep)")
            if(status == BluetoothGatt.GATT_SUCCESS && characteristic != null &&
                characteristic.uuid == remoteCharacteristicUuid
            ) {
                try {
                    val uuid = UuidUtil.uuidFromBytes(characteristic.value)
                    Log.i(LOG_TAG, "Got UID: $uuid")
                    dataPortUuid.complete(uuid)
                }catch(e: Exception) {
                    e.printStackTrace()
                }
            }

            disconnectIfRequired(gatt)
        }

        private fun disconnectIfRequired(gatt: BluetoothGatt?) {
            if(!disconnected.getAndSet(true)) {
                try {
                    gatt?.disconnect()
                }catch(e: SecurityException){
                    e.printStackTrace()
                }
            }
        }

        suspend fun getDataPortUuid(): UUID {
            return dataPortUuid.await()
        }
    }

    suspend fun requestUuidAllocation(
        remoteAddress: String,
        remoteServiceUuid: UUID,
        remoteCharacteristicUuid: UUID,
    ) : UUID {
        val bluetoothAdapterVal = bluetoothAdapter
            ?: throw IllegalStateException("Bluetooth not supported")
        val remoteDevice = bluetoothAdapterVal.getRemoteDevice(remoteAddress)
        val getDataPortMutex = mapLock.withLock {
            lockByRemote.getOrPut(
                RemoteEndpoint(remoteAddress, remoteServiceUuid)
            ) {
                Mutex()
            }
        }

        val dataUuid = getDataPortMutex.withLock {
            try {
                //Autoconnect must be false
                // https://devzone.nordicsemi.com/cfs-file/__key/communityserver-blogs-components-weblogfiles/00-00-00-00-04-DZ-1046/2604.BLE_5F00_on_5F00_Android_5F00_v1.0.1.pdf
                // see page 7: bottom (bug)
                //val gatt = remoteDevice.connectGatt(appContext, false, gattCallback)
                val startTime = System.currentTimeMillis()
                val getDataUuidGattCallback = GetDataUuidGattCallback(remoteServiceUuid,
                    remoteCharacteristicUuid)
                val gatt = remoteDevice.connectGatt(appContext, false, getDataUuidGattCallback)
                gatt.connect()
                val uuid = getDataUuidGattCallback.getDataPortUuid()
                Log.d(LOG_TAG,
                    "Got uuid $uuid in ${System.currentTimeMillis() - startTime}ms")
                uuid
            }catch(e: SecurityException) {
                e.printStackTrace()
                null
            }
        } ?: throw IOException("Unable to get data port")

        return dataUuid
    }

    override fun close() {

    }


}