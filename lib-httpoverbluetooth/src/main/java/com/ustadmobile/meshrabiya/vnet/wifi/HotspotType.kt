package com.ustadmobile.meshrabiya.vnet.wifi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
@Serializable(with = HotspotTypeSerializer::class)
enum class HotspotType(val flag: Byte) {
    LOCALONLY_HOTSPOT(1), WIFIDIRECT_GROUP(2);

    companion object {
        fun fromFlag(flag: Byte): HotspotType {
            return values().first { it.flag == flag }
        }
    }
}

object HotspotTypeSerializer: KSerializer<HotspotType> {
    override fun deserialize(decoder: Decoder): HotspotType {
        return HotspotType.fromFlag(decoder.decodeByte())
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("hotspotType", PrimitiveKind.BYTE)

    override fun serialize(encoder: Encoder, value: HotspotType) {
        encoder.encodeByte(value.flag)
    }
}
