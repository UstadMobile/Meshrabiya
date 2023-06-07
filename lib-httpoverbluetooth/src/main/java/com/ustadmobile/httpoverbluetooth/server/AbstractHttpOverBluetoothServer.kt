package com.ustadmobile.httpoverbluetooth.server

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.LOG_TAG
import java.io.IOException
import java.lang.Exception
import java.util.UUID
import java.util.concurrent.Executors

class AbstractHttpOverBluetoothServer(
    private val appContext: Context,
    private val allocationServiceUuid: UUID,
    private val allocationCharacteristicUuid: UUID,
    private val maxClients: Int,
    private val uuidAllocationServerFactory: (
        appContext: Context,
        allocationServiceUuid: UUID,
        allocationCharacteristicUuid: UUID,
        maxClients: Int,
        onUuidAllocated: OnUuidAllocatedListener,
    ) -> UuidAllocationServer = ::UuidAllocationServer,
) {

    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val onUuidAllocatedListener = OnUuidAllocatedListener { uuid ->
        val serverSocket: BluetoothServerSocket? = try {
            bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("ustad", uuid)
        }catch(e: SecurityException) {
            null
        }

        val clientSocket: BluetoothSocket? = try {
            Log.d(LOG_TAG, "Listening for data request on $uuid")
            serverSocket?.accept() //Can add timeout here
        }catch (e: IOException) {
            Log.e(LOG_TAG, "Exception accepting socket", e)
            null
        }

        clientSocket?.also { socket ->
            Log.i(LOG_TAG, "client connected")
            try {
                val inStream = socket.inputStream
                val outStream = socket.outputStream

                outStream.write("Hello World\n".toByteArray())
                outStream.flush()
            }catch(e: Exception) {
                e.printStackTrace()
            }
        }

        //client should close socket on its end...
        // TODO: Make sure it is 100% closed
        //we need to close the server socket.
    }

    private val uuidAllocationServer = uuidAllocationServerFactory(
        appContext,
        allocationServiceUuid,
        allocationCharacteristicUuid,
        maxClients,
        onUuidAllocatedListener,
    )

    init {

    }

    /**
     * Separate executor to cancel anything that times out
     */
    private val timeoutExecutor = Executors.newScheduledThreadPool(1)


    fun close() {
        uuidAllocationServer.close()
    }

    companion object {
        val DEFAULT_ALLOCATION_SERVICE_UUID = UUID.fromString("0661d55f-bc07-4996-b9a6-303d453a8a20")
        val DEFAULT_CHARACTERISTIC_UUID = UUID.fromString("0661d55f-bc07-4996-b9a6-303d453a8a19")
    }
}