package com.ustadmobile.meshrabiya.vnet

import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream

class BluetoothSocketISocketAdapter(
    private val bluetoothSocket: BluetoothSocket
): ISocket {

    override val inStream: InputStream
        get() = bluetoothSocket.inputStream

    override val outputStream: OutputStream
        get() = bluetoothSocket.outputStream

    override fun close() {
        bluetoothSocket.close()
    }

}

fun BluetoothSocket.asISocket(): ISocket {
    return BluetoothSocketISocketAdapter(this)
}
