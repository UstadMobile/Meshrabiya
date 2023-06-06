package com.ustadmobile.httpoverbluetooth.server

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.ServiceConnection
import android.os.ParcelUuid
import android.util.Log
import com.ustadmobile.httpoverbluetooth.toBytes
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.BleServerManager
import no.nordicsemi.android.ble.BuildConfig
import no.nordicsemi.android.ble.observer.ServerObserver
import java.io.IOException
import java.lang.Exception
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AbstractHttpOverBluetoothServer(
    private val appContext: Context,
    val controlUuid: UUID,
    private val maxSimultaneousClients: Int = 4,
) {

    private val clientExecutor = Executors.newFixedThreadPool(maxSimultaneousClients)

    /**
     * Separate executor to cancel anything that times out
     */
    private val timeoutExecutor = Executors.newScheduledThreadPool(1)

    val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val allocatedUuids = ConcurrentHashMap<UUID, BluetoothDevice>()

    private var gattServer: BluetoothGattServer? = null

    private val gattServerCallback = object: BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            Log.i("BluetoothOverHttpTag", "Service added: ${service?.uuid} characteristics " +
                    "= ${service?.characteristics?.joinToString { it.uuid.toString() }}")
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.i("BluetoothOverHttpTag", "onConnectionChanged: ${device?.address}: status = $newState")
            if(status == BluetoothGatt.STATE_CONNECTED && device != null) {
                try {
                    Log.i("BluetoothOverHttpTag", "onConnectionChanged: connecting to ${device.address}")
                    gattServer?.connect(device, false)
                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            Log.i("BluetoothOverHttpTag", "onDescriptorReadRequest")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if(characteristic.uuid == CHARACTERISTIC_UUID) {
                try {
                    val randomUuid = UUID.randomUUID()
                    if(allocatedUuids.size < maxSimultaneousClients) {
                        allocatedUuids[randomUuid] = device
                        clientExecutor.submit(DataAcceptRunnable(randomUuid))

                        val responseSent = gattServer?.sendResponse(device, requestId,
                            BluetoothGatt.GATT_SUCCESS, 0, randomUuid.toBytes())
                        Log.i("BluetoothOverHttpTag", "Send uuid $randomUuid to ${device.address} : sent=$responseSent")
                    }
                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }else {
                Log.i("BluetoothOverHttpTag", "onCharacteristicReadRequest: not our service")
            }
        }

    }

    init {
        val service = BluetoothGattService(controlUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val characteristic = BluetoothGattCharacteristic(
            ParcelUuid(CHARACTERISTIC_UUID).uuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        try {
            gattServer = bluetoothManager.openGattServer(appContext, gattServerCallback)
            service.addCharacteristic(characteristic)

            gattServer?.addService(service)
            Log.i("BluetoothOverHttpTag", "Opened Gatt server")
            Log.i("BluetoothOverHttpTag", "Opened Gatt server manager")

        }catch(e: SecurityException) {
            e.printStackTrace()
        }
    }

    private inner class DataAcceptRunnable(
        val allocatedUuid: UUID,
    ): Runnable {
        private val controlServerSocket: BluetoothServerSocket? by lazy {
            try {
                bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("ustad", allocatedUuid)
            }catch(e: SecurityException) {
                null
            }
        }

        override fun run() {
            val clientSocket: BluetoothSocket? = try {
                Log.d("HttpOverBluetoothTag", "Listening for data request on $allocatedUuid")
                controlServerSocket?.accept() //Can add timeout here
            }catch (e: IOException) {
                Log.e("HttpOverBluetoothTag", "Exception accepting socket", e)
                null
            }

            clientSocket?.also { socket ->
                Log.i("HttpOverBluetoothTag", "client connected")
                try {
                    val inStream = socket.inputStream
                    val outStream = socket.outputStream

                    outStream.write("Hello World\n".toByteArray())
                    outStream.flush()
                }catch(e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private inner class ControlUuidAcceptThread: Thread() {
        private val controlServerSocket: BluetoothServerSocket? by lazy {
            try {
                bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("ustad", controlUuid)
            }catch(e: SecurityException) {
                null
            }
        }

        var shouldLoop = true

        override fun run() {
            while(shouldLoop) {
                val clientSocket: BluetoothSocket? = try {
                    Log.d("HttpOverBluetoothTag", "Listening for control request")
                    controlServerSocket?.accept() //Can add timeout here
                }catch (e: IOException) {
                    Log.e("HttpOverBluetoothTag", "Exception accepting socket", e)
                    null
                }

                clientSocket?.also { socket ->
                    Log.i("HttpOverBluetoothTag", "client connected")
                    try {
                        val inStream = socket.inputStream
                        val outStream = socket.outputStream

                        val randomUuid = UUID.randomUUID()
                        outStream.write((randomUuid.toString() + "\n").toByteArray())
                        outStream.flush()
                        Log.i("HttpOverBluetoothTag", "Sent UUID to client: $randomUuid")

                        Log.i("HttpOverBluetoothTag", "Got and closed in stream")
                        //note : dont close the streams, as per Google samples, close only the socket.

                        //val handler = clientExecutor.submit()
                    }finally {
                        //client socket should be closed on client side
                        //clientSocket.close()
                    }
                }
            }
        }
    }

    fun close() {
        //controlThread.shouldLoop = false
    }

    companion object {
        val CHARACTERISTIC_UUID = UUID.fromString("0661d55f-bc07-4996-b9a6-303d453a8a19")
    }
}