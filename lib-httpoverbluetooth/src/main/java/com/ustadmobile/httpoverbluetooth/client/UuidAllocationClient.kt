package com.ustadmobile.httpoverbluetooth.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.ustadmobile.httpoverbluetooth.RemoteEndpoint
import com.ustadmobile.httpoverbluetooth.UuidUtil
import com.ustadmobile.httpoverbluetooth.MNetLogger
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
import java.util.concurrent.atomic.AtomicInteger
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
    private val onLog: MNetLogger,
) : Closeable{

    private val lockByRemote = ConcurrentHashMap<RemoteEndpoint, Mutex>()

    private val mapLock = ReentrantLock()

    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())



    /**
     * Bluetooth GATT error messages: see
     * https://github.com/NordicSemiconductor/Android-BLE-Library/blob/5e0e2f08c309a6de2376d9b8705c83f9e9a80d56/ble/src/main/java/no/nordicsemi/android/ble/error/GattError.java#L38
     */
    private class GetDataUuidGattCallback(
        private val remoteServiceUuid: UUID,
        private val remoteCharacteristicUuid: UUID,
        coroutineScope: CoroutineScope,
        private val timeout: Long = DEFAULT_TIMEOUT,
        private val onLog: MNetLogger
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

        private val startTime = System.currentTimeMillis()

        private val clientId = CALLBACK_ID_ATOMIC.getAndIncrement()

        private val logPrefix: String
            get() = "UuidAllocationClient #$clientId (t+${System.currentTimeMillis()-startTime}ms)"

        fun callbackLog(priority: Int, message: String, exception: Exception?) {
            onLog(priority, "$logPrefix: $message", exception)
        }

        val timeoutJob = coroutineScope.launch {
            delay(timeout)
            callbackLog(Log.DEBUG, "Timeout after ${timeout}ms: calling disconnectAndCloseIfRequired", null)
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
            callbackLog(Log.INFO, "onConnectionStateChange state=$newState status=$status", null)

            if(gatt == null) {
                callbackLog(Log.WARN, "onConnectionStateChange: gatt is null, can't do anything", null)
                return
            }

            connectionState = newState


            //We have requested to connect and the state is now connected, Hooray! Lets go!
            if(status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                connectionEstablished = true
                if(timeoutJob.isCancelled) {
                    callbackLog(Log.DEBUG, "onConnectionStateChange: already cancelled",null)
                    return
                }

                try {
                    if(!gatt.discoverServices()) {
                        callbackLog(Log.WARN,
                            "onConnectionStateChange: connected, but failed to submit discover services request",
                        null)
                        disconnectAndCloseIfRequired(gatt, IllegalStateException("Failed to submit discover services request"))
                    }
                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }

            //If we have now been disconnected as requested, but have not closed the gatt, now is the
            //time to close.
            if(newState == BluetoothGatt.STATE_DISCONNECTED && disconnectCalled.get() &&
                !closed.getAndSet(true)
            ) {
                try {
                    gatt.close()
                    callbackLog(Log.DEBUG, "Closed client Gatt callback.", null)
                }catch(e: SecurityException) {
                    callbackLog(Log.ERROR, "Security exception closing Gatt", e)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            callbackLog(Log.DEBUG, "onServicesDiscovered: status = $status services discovered: " +
                    "${gatt?.services?.size}", null)

            if(gatt == null) {
                callbackLog(Log.WARN, "onServicesDiscovered: gatt is null, can't do anything", null)
                return
            }

            val services = gatt.services
            gatt.services.forEach { service ->
                callbackLog(Log.DEBUG, "Service ${service.uuid} characteristics: " +
                        service.characteristics.joinToString { it.uuid.toString() }, null)
            }

            if(timeoutJob.isCancelled) {
                callbackLog(Log.DEBUG, "onServicesDiscovered: already cancelled", null)
                return
            }


            val characteristic = services.firstOrNull { it.uuid == remoteServiceUuid }
                ?.characteristics?.firstOrNull { it.uuid == remoteCharacteristicUuid }

            if(characteristic != null) {
                callbackLog(Log.DEBUG, "permissions = ${characteristic.permissions}, properties=${characteristic.properties}", null)
                try {
                    val requestedRead = gatt.readCharacteristic(characteristic)
                    if(!requestedRead) {
                        callbackLog(Log.WARN, "Found UUID allocation service/characteristic, but request to read submission failed", null)

                        disconnectAndCloseIfRequired(gatt,
                            IllegalStateException("Found UUID allocation service/characteristic, but request to read submission failed")
                        )
                    }else {
                        callbackLog(Log.DEBUG, "found target characteristic - submitted request to read OK.", null)
                    }

                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }else {
                callbackLog(Log.WARN, "services discovered, but target characteristic not found", null)
            }
        }

        private fun onCharacteristicReadCompat(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            value: ByteArray?,
            status: Int
        ) {
            callbackLog(Log.DEBUG, "onCharacteristicReadCompat status=$status characteristic " +
                    "uuid=${characteristic?.uuid} value=${value?.size} bytes", null)
            if(gatt == null) {
                callbackLog(Log.WARN, "onCharacteristicReadCompat: gatt is null, can't do anything", null)
                return
            }

            if(characteristic != null && characteristic.uuid == remoteCharacteristicUuid) {
                callbackLog(Log.DEBUG, "onCharacteristicReadCompat: for target characteristic", null)
                if(value != null && status == BluetoothGatt.GATT_SUCCESS) {
                    try {
                        val uuid = UuidUtil.uuidFromBytes(value)
                        callbackLog(Log.INFO, "Got allocated uuid: $uuid", null)
                        timeoutJob.cancel()
                        dataPortUuid.complete(uuid)
                        disconnectAndCloseIfRequired(gatt)
                    }catch(e: Exception) {
                        e.printStackTrace()
                        disconnectAndCloseIfRequired(gatt, e)
                    }
                }else {
                    callbackLog(Log.ERROR, "onCharacteristicRead: Characteristic is null or status ($status) != GATT_SUCCESS", null)
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

        private fun disconnectAndCloseIfRequired(gatt: BluetoothGatt, exception: Exception? = null) {
            callbackLog(Log.DEBUG, "disconnectAndCloseIfRequired exception=$exception", null)

            if(!disconnectCalled.getAndSet(true)) {
                callbackLog(Log.DEBUG,
                    "disconnectAndCloseIfRequired - disconnect not called before - cancel timeout",
                null
                )
                timeoutJob.cancel()

                try {
                    val alreadyDisconnected = connectionState == BluetoothGatt.STATE_DISCONNECTED
                    gatt.disconnect()
                    callbackLog(Log.DEBUG, "UuidAllocationClient: submitted GATT disconnect request",
                        null)

                    //If we are already disconnected, then we will call close now. Otherwise we will
                    //wait for the onConnectionStateChange callback
                    if(alreadyDisconnected) {
                        //we are already disconnected, so close now.
                        callbackLog(Log.DEBUG, "UuidAllocationClient: disconnectAndClose: " +
                                "already disconnected, so will close now", null)
                        closed.set(true)
                        gatt.close()
                    }
                }catch(e: SecurityException){
                    callbackLog(Log.ERROR, "Security exception on disconnect/close", e)
                }

                if(exception != null && !dataPortUuid.isCompleted) {
                    callbackLog(Log.WARN, "disconnectAndCloseIfRequired: complete exceptionally", exception)
                    dataPortUuid.completeExceptionally(exception)
                }
            }
        }

        suspend fun getDataPortUuid(gatt: BluetoothGatt): UUID {
            callbackLog(Log.DEBUG, "getDataPortUuid", null)
            callbackGatt = gatt
            try {
                callbackLog(Log.DEBUG, "getDataPortUid: request connect",null)

                if(!gatt.connect()) {
                    callbackLog(Log.ERROR, "getDataPortUid: request to connect not submitted", null)
                    dataPortUuid.completeExceptionally(IOException("Failed to submit connect request"))
                }
            }catch(e: SecurityException) {
                callbackLog(Log.ERROR, "SecurityException on GATT connect", e)
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
                    onLog = onLog
                )
                val gatt = remoteDevice.connectGatt(appContext, false, getDataUuidGattCallback)
                val uuid = getDataUuidGattCallback.getDataPortUuid(gatt)
                onLog(Log.DEBUG, "Got allocated uuid $uuid in ${System.currentTimeMillis() - startTime}ms", null)
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

        val CALLBACK_ID_ATOMIC = AtomicInteger(1)

        const val DEFAULT_TIMEOUT = 12000L

    }


}