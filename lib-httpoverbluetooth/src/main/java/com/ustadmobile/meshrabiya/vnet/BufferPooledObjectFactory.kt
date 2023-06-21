package com.ustadmobile.meshrabiya.vnet

import org.apache.commons.pool2.BasePooledObjectFactory
import org.apache.commons.pool2.PooledObject
import org.apache.commons.pool2.impl.DefaultPooledObject

class BufferPooledObjectFactory(
    private val bufSize: Int
): BasePooledObjectFactory<ByteArray>() {

    override fun create(): ByteArray {
        return ByteArray(bufSize)
    }

    override fun wrap(obj: ByteArray): PooledObject<ByteArray> {
        return DefaultPooledObject(obj)
    }
}
