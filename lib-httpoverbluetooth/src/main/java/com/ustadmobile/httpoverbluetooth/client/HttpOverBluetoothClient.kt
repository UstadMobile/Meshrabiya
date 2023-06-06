package com.ustadmobile.httpoverbluetooth.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ustadmobile.httpoverbluetooth.UuidUtil
import com.ustadmobile.httpoverbluetooth.server.AbstractHttpOverBluetoothServer.Companion.CHARACTERISTIC_UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * As per https://developer.android.com/guide/topics/connectivity/bluetooth/connect-bluetooth-devices#connect-server
 * point 3 "Unlike TCP/IP, RFCOMM allows only one connected client per channel at a time,"
 */
class HttpOverBluetoothClient(
    private val appContext: Context,
    //private val rawHttp: RawHttp,
) {

    var fromUuid: UUID = UUID(0, 0)

    data class RemoteEndpoint(
        val remoteAddress: String,
        val remoteControlUuid: UUID,
    )

    private val mapLock = ReentrantLock()

    private val lockByRemote = ConcurrentHashMap<RemoteEndpoint, Mutex>()

    val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter



    private class GetDataUuidGattCallback(
        private val remoteAddress: String,
        private val remoteControlUuid: UUID
    ) : BluetoothGattCallback() {

        val dataPortUuid = CompletableDeferred<UUID>()

        private val disconnected = AtomicBoolean(false)

        override fun onConnectionStateChange(
            gatt: BluetoothGatt?,
            status: Int,
            newState: Int
        ) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.i("HttpOverBluetoothTag", "onConnectionStateChange state=$newState status=$status")
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
            Log.i("HttpOverBluetooth", "services discovered: ${gatt.services.size}")
            gatt.services.forEach { service ->
                Log.i("HttpOverBluetooth", "Service ${service.uuid} characteristics: " +
                        service.characteristics.joinToString { it.uuid.toString() })
            }

            val characteristic = services.firstOrNull { it.uuid == remoteControlUuid }
                ?.characteristics?.firstOrNull { it.uuid == CHARACTERISTIC_UUID }

            if(characteristic != null) {
                Log.i("HttpOverBluetoothTag", "permissions = ${characteristic.permissions}, properties=${characteristic.properties}")
                try {
                    val requestedRead = gatt.readCharacteristic(characteristic)
                    Log.i("HttpOverBluetoothTag", "found target characteristic - attempt read submitted=$requestedRead")
                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }else {
                Log.w("HttpOverBluetoothTag", "services discovered, but target characteristic not found")
            }
        }


        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.i("HttpOverBluetoothTag", "Characteristic changed: ${characteristic.uuid}")
        }


        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.i("HttpOverBluetooth", "onCharacteristicRead")

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
            Log.i("HttpOverBluetooth", "onCharacteristicRead (dep)")
            if(status == BluetoothGatt.GATT_SUCCESS && characteristic != null &&
                characteristic.uuid == CHARACTERISTIC_UUID
            ) {
                try {
                    val uuid = UuidUtil.uuidFromBytes(characteristic.value)
                    Log.i("HttpOverBluetoothTag", "Got UID: $uuid")
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


    suspend fun sendRequest(
        remoteAddress: String,
        remoteControlUuid: UUID,
//        request: RawHttpRequest
    )  {
        val adapter = bluetoothAdapter ?: return /*rawHttp.parseResponse(
            "HTTP/1.1 500 Internal Server Error\n" +
                    "Content-Type: text/plain\n"
        ).withBody(StringBody("HttpOverBluetooth: No  bluetooth adapter"))*/

        val remoteDevice = adapter.getRemoteDevice(remoteAddress)
        val getDataPortMutex = mapLock.withLock {
            lockByRemote.getOrPut(RemoteEndpoint(remoteAddress, remoteControlUuid)) {
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
                val getDataUuidGattCallback = GetDataUuidGattCallback(remoteAddress, remoteControlUuid)
                val gatt = remoteDevice.connectGatt(appContext, false, getDataUuidGattCallback)
                gatt.connect()
                val uuid = getDataUuidGattCallback.getDataPortUuid()
                Log.i("HttpOverBluetoothLog",
                    "Got uuid $uuid in ${System.currentTimeMillis() - startTime}ms")
                uuid
            }catch(e: SecurityException) {
                e.printStackTrace()
                null
            }
        } ?: throw IOException("Unable to get data port")

        withContext(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            var inStream: InputStream? = null
            var outStream: OutputStream? = null

            try {
                val startTime = System.currentTimeMillis()
                try {
                    socket = remoteDevice.createInsecureRfcommSocketToServiceRecord(
                        dataUuid
                    )
                    Log.i("HttpOverBluetoothTag", "Connecting to server on $dataUuid")
                    socket.connect()
                    Log.i("HttpOverBluetoothTag", "Socket connected $dataUuid")

                    inStream = socket.inputStream
                    outStream = socket.outputStream
                    val inReader = BufferedReader(inStream.reader())
                    val serverSays = inReader.readLine()
                    Log.i("HttpOverBluetoothTag", "Got message from server: $serverSays")
                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }catch(e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
            }
        }
    }
}