package com.ustadmobile.httpoverbluetooth.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.httpoverbluetooth.RemoteEndpoint
import com.ustadmobile.httpoverbluetooth.UuidUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.Exception
import kotlin.concurrent.withLock

/**
 * Client that can request a UUID allocation for Bluetooth RFCOMM sockets using GATT (Bluetooth Low
 * Energy). The client can manage communication with multiple different servers simultaneously (e.g.
 * similar to the OKHTTPClient etc). It will ensure that we make only one allocation request at a time.
 */
class UuidAllocationClient(
    private val appContext: Context,
    private val onLogError: (message: String, exception: Exception?) -> Unit,
) : Closeable{

    private val lockByRemote = ConcurrentHashMap<RemoteEndpoint, Mutex>()

    private val mapLock = ReentrantLock()

    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private class GetDataUuidGattCallback(
        private val remoteServiceUuid: UUID,
        private val remoteCharacteristicUuid: UUID,
        coroutineScope: CoroutineScope,
        private val timeout: Long = DEFAULT_TIMEOUT,
        private val onLogError: (message: String, exception: Exception?) -> Unit
    ) : BluetoothGattCallback() {

        val dataPortUuid = CompletableDeferred<UUID>()

        @Volatile
        private lateinit var callbackGatt: BluetoothGatt

        private val disconnectCalled = AtomicBoolean(false)

        private val closed = AtomicBoolean(false)

        private var connectionState = BluetoothGatt.STATE_DISCONNECTED

        /**
         * Tracks whether or not any connection has been established. If no connection was ever
         * established by the callback, then onConnectionChange with status != success will trigger
         * giving up.
         */
        private var connectionEstablished = false

        val timeoutJob = coroutineScope.launch {
            delay(timeout)
            disconnectAndCloseIfRequired(
                callbackGatt,
                TimeoutException("GetDataUuidGattCallback for $remoteServiceUuid/$remoteCharacteristicUuid timed out after ${timeout}ms")
            )
        }

        override fun onConnectionStateChange(
            gatt: BluetoothGatt?,
            status: Int,
            newState: Int
        ) {
            super.onConnectionStateChange(gatt, status, newState)
            connectionState = newState

            Log.i(LOG_TAG, "onConnectionStateChange state=$newState status=$status")

            //We have requested to connect and the state is now connected, Hooray! Lets go!
            if(status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                connectionEstablished = true
                if(timeoutJob.isCancelled) {
                    Log.d(LOG_TAG, "onConnectionStateChange: already cancelled")
                    return
                }

                try {
                    if(gatt?.discoverServices() != true)
                        disconnectAndCloseIfRequired(gatt, IllegalStateException("Failed to submit discover services request"))

                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }

            //We have already disconnected, now we need to close the callback
            if(newState == BluetoothGatt.STATE_DISCONNECTED && disconnectCalled.get() &&
                !closed.getAndSet(true)
            ) {
                try {
                    gatt?.close()
                    Log.d(LOG_TAG, "Closed client Gatt callback.")
                }catch(e: SecurityException) {
                    Log.e(LOG_TAG, "Security exception closing Gatt", e)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if(gatt == null)
                return

            if(timeoutJob.isCancelled) {
                Log.d(LOG_TAG, "onServicesDiscovered: already cancelled")
                return
            }

            val services = gatt.services
            Log.d(LOG_TAG, "services discovered: ${gatt.services.size}")
            gatt.services.forEach { service ->
                Log.d(LOG_TAG, "Service ${service.uuid} characteristics: " +
                        service.characteristics.joinToString { it.uuid.toString() })
            }

            val characteristic = services.firstOrNull { it.uuid == remoteServiceUuid }
                ?.characteristics?.firstOrNull { it.uuid == remoteCharacteristicUuid }

            if(characteristic != null) {
                Log.d(LOG_TAG, "permissions = ${characteristic.permissions}, properties=${characteristic.properties}")
                try {
                    val requestedRead = gatt.readCharacteristic(characteristic)
                    if(!requestedRead) {
                        Log.w(LOG_TAG, "Found UUID allocation service/characteristic, but request to read submission failed")
                        onLogError("Found UUID allocation service/characteristic, but request to read submission failed", null)


                        disconnectAndCloseIfRequired(gatt,
                            IllegalStateException("Found UUID allocation service/characteristic, but request to read submission failed")
                        )
                    }
                    Log.i(LOG_TAG, "found target characteristic - attempt read submitted=$requestedRead")
                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }else {
                Log.w(LOG_TAG, "services discovered, but target characteristic not found")
                onLogError("services discovered, but target characteristic not found", null)
            }
        }

        private fun onCharacteristicReadCompat(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            value: ByteArray?,
            status: Int
        ) {
            Log.d(LOG_TAG, "onCharacteristicReadCompat")
            if(characteristic != null && characteristic.uuid == remoteCharacteristicUuid) {
                Log.d(LOG_TAG, "onCharacteristicReadCompat: for target characteristic")
                if(value != null && status == BluetoothGatt.GATT_SUCCESS) {
                    try {
                        val uuid = UuidUtil.uuidFromBytes(value)
                        Log.i(LOG_TAG, "Got allocated uuid: $uuid")
                        timeoutJob.cancel()
                        dataPortUuid.complete(uuid)
                        disconnectAndCloseIfRequired(gatt)
                    }catch(e: Exception) {
                        e.printStackTrace()
                        disconnectAndCloseIfRequired(gatt, e)
                    }
                }else {
                    onLogError("onCharacteristicRead: Characteristic is null or status ($status) != GATT_SUCCESS", null)
                    disconnectAndCloseIfRequired(gatt, IOException("Characteristic is null or status ($status) != GATT_SUCCESS"))
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            onCharacteristicReadCompat(gatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        @Deprecated("""
            Might be deprecated, but pre-SDK33 this is the function that gets called, so not much
            we can do about it until SDK32 is obsolete. Gonna be a while.
        """)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            onCharacteristicReadCompat(gatt, characteristic, characteristic?.value, status)
        }

        private fun disconnectAndCloseIfRequired(gatt: BluetoothGatt?, exception: Exception? = null) {
            if(gatt == null) {
                Log.w(LOG_TAG, "UuidAllocationClient: NULL DISCONNECT")
                return //nothing we can do
            }

            if(!disconnectCalled.getAndSet(true)) {
                timeoutJob.cancel()

                try {
                    val alreadyDisconnected = connectionState == BluetoothGatt.STATE_DISCONNECTED
                    gatt.disconnect()
                    Log.d(LOG_TAG, "UuidAllocationClient: submitted GATT disconnect request")

                    //If we are already disconnected, then we will call close now. Otherwise we will
                    //wait for the onConnectionStateChange callback
                    if(alreadyDisconnected) {
                        //we are already disconnected, so close now.
                        Log.d(LOG_TAG, "UuidAllocationClient: disconnectAndClose: " +
                                "already disconnected, so will close now")
                        closed.set(true)
                        gatt.close()
                    }

                }catch(e: SecurityException){
                    e.printStackTrace()
                }

                if(exception != null)
                    dataPortUuid.completeExceptionally(exception)
            }
        }

        suspend fun getDataPortUuid(gatt: BluetoothGatt): UUID {
            callbackGatt = gatt
            try {
                if(!gatt.connect())
                    dataPortUuid.completeExceptionally(IOException("Failed to submit connect request"))

            }catch(e: SecurityException) {
                e.printStackTrace()
            }

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
        if(!bluetoothAdapterVal.isEnabled)
            throw IOException("requestUuidAllocation: bluetooth is not enabled")

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
                val getDataUuidGattCallback = GetDataUuidGattCallback(
                    remoteServiceUuid = remoteServiceUuid,
                    remoteCharacteristicUuid = remoteCharacteristicUuid,
                    coroutineScope = coroutineScope,
                    onLogError = onLogError
                )
                val gatt = remoteDevice.connectGatt(appContext, false, getDataUuidGattCallback)
                val uuid = getDataUuidGattCallback.getDataPortUuid(gatt)
                Log.d(LOG_TAG, "Got allocated uuid $uuid in ${System.currentTimeMillis() - startTime}ms")
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

    companion object {

        const val DEFAULT_TIMEOUT = 12000L

    }


}