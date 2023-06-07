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
import android.content.Context
import android.util.Log
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.UUID_BUSY
import com.ustadmobile.httpoverbluetooth.toBytes
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
    private val allocationServiceUuid: UUID,
    private val allocationCharacteristicUuid: UUID,
    private val maxSimultaneousClients: Int = 4,
    private val onUuidAllocated: OnUuidAllocatedListener,
) : Closeable {

    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val allocatedUuids = ConcurrentHashMap<UUID, BluetoothDevice>()

    private val allocatedUuidLock = ReentrantLock()

    private var gattServer: BluetoothGattServer? = null

    private val useUuidExecutor = Executors.newFixedThreadPool(maxSimultaneousClients)

    private val gattServerCallback = object: BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            Log.i(LOG_TAG , "Service added: ${service?.uuid} characteristics " +
                    "= ${service?.characteristics?.joinToString { it.uuid.toString() }}")
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.i(LOG_TAG, "onConnectionChanged: ${device?.address}: status = $newState")
            if(status == BluetoothGatt.STATE_CONNECTED && device != null) {
                try {
                    Log.i(LOG_TAG, "onConnectionChanged: connecting to ${device.address}")
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
            Log.i(LOG_TAG, "onDescriptorReadRequest")
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
                Log.i(LOG_TAG, "onCharacteristicReadRequest: not our service")
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
        val service = BluetoothGattService(allocationServiceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val characteristic = BluetoothGattCharacteristic(
            allocationCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        try {
            gattServer = bluetoothManager.openGattServer(appContext, gattServerCallback)
            service.addCharacteristic(characteristic)

            gattServer?.addService(service)
            Log.i(LOG_TAG, "Opened Gatt server")
            Log.i(LOG_TAG, "Opened Gatt server manager")

        }catch(e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun close() {

    }

}