package com.ustadmobile.meshrabiya.vnet.wifi

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LocalHotspotResponse(
    val responseToMessageId: Int,
    val errorCode: Int,
    val config: HotspotConfig?,
    val redirectAddr: Int,
) {

    val sizeInBytes: Int
        //Size = responseToMessageId + errorCode + (1 byte indicating if there is a config) + config bytes + redirect addr
        get() = 4 + 4 + 1 + (config?.sizeInBytes ?: 0) + 4

    fun toBytes(): ByteArray {
        return ByteArray(sizeInBytes).also {
            toBytes(it, 0)
        }
    }

    fun toBytes(
        byteArray: ByteArray,
        offset: Int
    ) {
        val byteBuf = ByteBuffer.wrap(byteArray, offset, byteArray.size - offset)
            .order(ByteOrder.BIG_ENDIAN)
        byteBuf.putInt(responseToMessageId)
        byteBuf.putInt(errorCode)
        byteBuf.put(if(config != null) 1.toByte() else 0.toByte())
        if(config != null) {
            val configOffset = offset + CONFIG_OFFSET
            val configSize = config.toBytes(byteArray, configOffset)
            byteBuf.position(byteBuf.position() + configSize)
        }
        byteBuf.putInt(redirectAddr)
    }



    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int
        ): LocalHotspotResponse {
            val byteBuf = ByteBuffer.wrap(byteArray, offset, byteArray.size - offset)
                .order(ByteOrder.BIG_ENDIAN)
            val responseToMessageId = byteBuf.int
            val errorCode = byteBuf.int
            val hasHotspotConfig = byteBuf.get() != 0.toByte()
            val config = if(hasHotspotConfig) {
                HotspotConfig.fromBytes(byteArray, offset + CONFIG_OFFSET).also {
                    byteBuf.position(byteBuf.position() + it.sizeInBytes)
                }
            }else {
                null
            }
            val redirectAddr = byteBuf.int

            return LocalHotspotResponse(
                responseToMessageId = responseToMessageId,
                errorCode = errorCode,
                config = config,
                redirectAddr = redirectAddr,
            )
        }

        //offset = 9 (4 bytes for responseToMessageId, 4 bytes for errorcode, 1 byte indicating if there is or is not a config)
        //This must be incremented if other content is added before the config
        private const val CONFIG_OFFSET = 9

    }

}