package com.ustadmobile.meshrabiya.vnet.netinterface

import java.io.Closeable

/**
 * Simplified Socket
 */
interface VSocket : Closeable{

    fun inputStream()

    fun outputStream()

}