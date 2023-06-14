package com.ustadmobile.httpoverbluetooth.server

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.httpoverbluetooth.MNetLogger
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.io.IOException
import java.lang.Exception
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

abstract class AbstractHttpOverBluetoothServer(
    protected val appContext: Context,
    protected val rawHttp: RawHttp,
    allocationServiceUuid: UUID,
    allocationCharacteristicUuid: UUID,
    maxClients: Int,
    uuidAllocationServerFactory: (
        appContext: Context,
        allocationServiceUuid: UUID,
        allocationCharacteristicUuid: UUID,
        vnetLogger: MNetLogger,
        maxClients: Int,
        onUuidAllocated: OnUuidAllocatedListener,
    ) -> UuidAllocationServer = ::UuidAllocationServer,
) {

    private val isClosed = AtomicBoolean(false)

    private val isStarted = AtomicBoolean(false)

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
            serverSocket?.accept(SOCKET_ACCEPT_TIMEOUT) //Can add timeout here
        }catch (e: IOException) {
            Log.e(LOG_TAG, "Exception accepting socket", e)
            null
        }

        clientSocket?.also { socket ->
            Log.d(LOG_TAG, "client connected to $uuid")
            try {
                val inStream = socket.inputStream
                val outStream = socket.outputStream
                while(isStarted.get()) {
                    val request = rawHttp.parseRequest(inStream)
                    val response = handleRequest(socket.remoteDevice.address, request)
                    response.writeTo(outStream)
                    outStream.flush()
                }
            }catch(e: Exception) {
                Log.w(LOG_TAG, "Exception handling socket $uuid", e)
            } finally {
                Log.d(LOG_TAG, "Closing server socket on $uuid")
                serverSocket?.close()
            }
        }
    }

    private val uuidAllocationServer = uuidAllocationServerFactory(
        appContext,
        allocationServiceUuid,
        allocationCharacteristicUuid,
        {_, _, _, -> },
        maxClients,
        onUuidAllocatedListener,
    )

    init {

    }

    fun start() {
        if(isClosed.get())
            throw IllegalStateException("HttpOverBluetoothServer is closed.")
        isStarted.set(true)
        uuidAllocationServer.start()
    }

    fun stop() {
        isStarted.set(false)
        uuidAllocationServer.stop()
    }

    fun close() {
        if(!isClosed.getAndSet(true)) {
            Log.d(LOG_TAG, "Closing BluetoothOverHttpServer")
            uuidAllocationServer.close()
        }
    }

    abstract fun handleRequest(
        remoteDeviceAddress: String,
        request: RawHttpRequest,
    ): RawHttpResponse<*>

    companion object {

        const val SOCKET_ACCEPT_TIMEOUT = 12000

    }
}