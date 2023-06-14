package com.ustadmobile.httpoverbluetooth.server

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.UUID_BUSY
import com.ustadmobile.httpoverbluetooth.MNetLogger
import com.ustadmobile.httpoverbluetooth.toBytes
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs a Bluetooth Low Energy GATT server that allocates random UUIDs for clients to use for
 * actual data transfer requests.
 *
 * @param maxSimultaneousClients the maximum number of clients to serve simultaneously.
 * @param onUuidAllocated listener that will use the given UUID allocation. When the function is
 * finished the UUID is de-allocated.
 */
class UuidAllocationServer(
    private val appContext: Context,
    allocationServiceUuid: UUID,
    private val allocationCharacteristicUuid: UUID,
    private val vNetLogger: MNetLogger = MNetLogger { _, _, _ -> },
    private val maxSimultaneousClients: Int = 4,
    private val onUuidAllocated: OnUuidAllocatedListener,
) : Closeable {

    private val isClosed = AtomicBoolean(false)

    private val service = BluetoothGattService(allocationServiceUuid,
        BluetoothGattService.SERVICE_TYPE_PRIMARY)

    private val characteristic = BluetoothGattCharacteristic(
        allocationCharacteristicUuid,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
    )


    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val allocatedUuids = ConcurrentHashMap<UUID, BluetoothDevice>()

    private val allocatedUuidLock = ReentrantLock()

    @Volatile
    private var gattServer: BluetoothGattServer? = null

    private val useUuidExecutor = Executors.newFixedThreadPool(maxSimultaneousClients)

    /**
     * Started will track calls to start/stop. The Gatt server won't open if bluetooth is
     * not enabled. If start is called and bluetooth is enabled later, then the gatt server will
     * open automatically.
     */
    private var started: Boolean = false

    private var receiverRegistered: Boolean = false

    private val gattServerCallback = object: BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            Log.d(LOG_TAG , "Service added: ${service?.uuid} characteristics " +
                    "= ${service?.characteristics?.joinToString { it.uuid.toString() }}")
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d(LOG_TAG, "onConnectionChanged: ${device?.address}: status = $newState")
            if(status == BluetoothGatt.STATE_CONNECTED && device != null) {
                try {
                    Log.i(LOG_TAG, "onConnectionChanged: connecting to ${device.address}")
                    gattServer?.connect(device, false)
                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }else if(status == BluetoothGatt.STATE_DISCONNECTED) {
                try {
                    device?.also {
                        gattServer?.cancelConnection(it)
                        Log.d(LOG_TAG, "onConnectionChange: disconnected. cancelConnection called.")
                    }
                }catch(e: SecurityException) {
                    Log.e(LOG_TAG, "Security exception on cancelConnection: permission revoked?", e)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if(characteristic.uuid == allocationCharacteristicUuid) {
                try {
                    val allocatedUuid = allocatedUuidLock.withLock {
                        if(allocatedUuids.size < maxSimultaneousClients) {
                            UUID.randomUUID().also {
                                allocatedUuids[it] = device
                            }
                        }else {
                            UUID_BUSY
                        }
                    }

                    val responseSent = gattServer?.sendResponse(device, requestId,
                        BluetoothGatt.GATT_SUCCESS, 0, allocatedUuid.toBytes())

                    if(allocatedUuid != UUID_BUSY && responseSent == true){
                        useUuidExecutor.submit(DataAcceptRunnable(allocatedUuid, onUuidAllocated))
                        Log.i(LOG_TAG, "Send allocated uuid $allocatedUuid to ${device.address} : sent=$responseSent")
                    }else if(allocatedUuid != UUID_BUSY && responseSent != true) {
                        //We had a UUID to allocate, but we could not send it to the client. Don't
                        //start the thread, and remove the allocation from the list to avoid tying it up
                        allocatedUuids.remove(allocatedUuid)
                    }

                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }else {
                Log.d(LOG_TAG, "onCharacteristicReadRequest: not our service")
            }
        }
    }

    private val broadcastReceiver: BroadcastReceiver = object: BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            if(intent != null && intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when(state) {
                    BluetoothAdapter.STATE_ON -> {
                        if(started)
                            openGattServer()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        if(started)
                            closeGattServer()
                    }
                }
            }
        }
    }


    private inner class DataAcceptRunnable(
        private val allocatedUuid: UUID,
        private val useUuid: OnUuidAllocatedListener,
    ) : Runnable{
        override fun run() {
            try {
                Log.d(LOG_TAG, "Run allocated UUID runnable for $allocatedUuid")
                useUuid(allocatedUuid)
            }finally {
                allocatedUuids.remove(allocatedUuid)
            }
        }
    }

    init {
        service.addCharacteristic(characteristic)
    }


    fun start() {
        started = true
        openGattServer()
        appContext.takeIf { !receiverRegistered }?.registerReceiver(
            broadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        receiverRegistered = true
    }

    /**
     * Open the Gatt server if not already open. If the server is already open, this will have no
     * effect. If the adapter is not enabled, it will also have no effect.
     */
    private fun openGattServer() {
        if(isClosed.get())
            throw IllegalStateException("Cannot start/open gatt server: UuidAllocationServer closed!")

        if(gattServer == null && bluetoothAdapter?.isEnabled == true) {
            try {
                bluetoothManager.openGattServer(appContext, gattServerCallback).also {
                    Log.d(LOG_TAG, "Opened Gatt server")
                    if(it.addService(service)) {
                        gattServer = it
                        Log.d(LOG_TAG, "Add service request submitted")
                    }else {
                        Log.e(LOG_TAG, "Add service request submission failed, close")
                        it.close()
                    }
                }
            }catch(e: SecurityException) {
                Log.e(LOG_TAG, "Security exception opening gatt server. No permission?", e)
            }catch(e: Exception) {
                Log.e(LOG_TAG, "Other exception opening gatt server.", e)
            }
        }
    }

    /**
     * Close the gatt server. If the server is not running, it will have no effect
     */
    private fun closeGattServer() {
        gattServer?.also { server ->
            try {
                server.close()
                Log.d(LOG_TAG, "Uuid Allocation gatt server closed")
                allocatedUuids.clear()
            }catch(e: SecurityException) {
                Log.e(LOG_TAG, "Security exception closing gatt server. No permission?", e)
            }catch(e: Exception) {
                Log.e(LOG_TAG, "Other exception closing gatt server.", e)
            }finally {
                gattServer = null
            }
        }
    }

    fun stop() {
        started = false
        closeGattServer()
        appContext.takeIf { receiverRegistered }?.unregisterReceiver(broadcastReceiver)
        receiverRegistered = false
    }

    override fun close() {
        if(!isClosed.getAndSet(true)) {
            stop()
            useUuidExecutor.shutdown()
        }
    }

}