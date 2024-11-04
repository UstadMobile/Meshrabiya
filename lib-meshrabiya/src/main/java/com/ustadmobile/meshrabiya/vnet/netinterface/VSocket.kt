package com.ustadmobile.meshrabiya.vnet.netinterface

import java.io.InputStream
import java.io.OutputStream

/**
 * Simplified Socket
 */
interface VSocket {
    fun inputStream(): InputStream
    fun outputStream(): OutputStream
    fun close()
}