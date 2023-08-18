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
    LOCALONLY_HOTSPOT(1), WIFIDIRECT_GROUP(2), AUTO(4);

    override fun toString(): String {
        return when(this) {
            LOCALONLY_HOTSPOT -> "Local Only"
            WIFIDIRECT_GROUP -> "WiFi Direct"
            AUTO -> "Auto"
        }
    }

    companion object {
        fun fromFlag(flag: Byte): HotspotType {
            return values().first { it.flag == flag }
        }

        /**
         * Normally the system will determine what type of hotspot should be created (wifi direct)
         * or local only. However the user might override this.
         */
        fun forceTypeIfSpecified(
            specifiedType: HotspotType,
            autoType: HotspotType?,
        ): HotspotType? {
            return if(specifiedType != AUTO) {
                specifiedType
            }else {
                autoType
            }
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
