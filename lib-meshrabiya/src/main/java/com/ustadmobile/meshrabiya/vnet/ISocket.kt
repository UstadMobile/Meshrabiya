package com.ustadmobile.meshrabiya.vnet

import java.io.InputStream
import java.io.OutputStream

/**
 * Common interface that can represent any socket e.g. Bluetooth, IP, etc.
 */
interface ISocket {

    val inStream: InputStream

    val outputStream: OutputStream

    fun close()

}