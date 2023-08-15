package com.ustadmobile.meshrabiya.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.InetAddress

object InetAddressSerializer: KSerializer<InetAddress> {

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("inetaddr", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): InetAddress {
        return InetAddress.getByName(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: InetAddress) {
        encoder.encodeString(value.hostAddress ?: throw IllegalArgumentException("no host addr"))
    }
}