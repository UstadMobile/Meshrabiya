package com.ustadmobile.meshrabiya.vnet.wifi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ConnectBandSerializer::class)
enum class ConnectBand(val flag: Byte) {
    //Ids are as per WifiP2pConfig
    BAND_2GHZ(1), BAND_5GHZ(2), BAND_UNKNOWN(0),
    ;

    override fun toString(): String {
        return when(this) {
            BAND_2GHZ -> "2Ghz"
            BAND_5GHZ -> "5Ghz"
            BAND_UNKNOWN -> "Band unknown"
        }
    }

    companion object {
        fun fromFlag(flag: Byte): ConnectBand {
            return values().first { it.flag == flag }
        }

    }
}

object ConnectBandSerializer: KSerializer<ConnectBand> {
    override fun deserialize(decoder: Decoder): ConnectBand {
        return ConnectBand.fromFlag(decoder.decodeByte())
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("connectBand", PrimitiveKind.BYTE)

    override fun serialize(encoder: Encoder, value: ConnectBand) {
        encoder.encodeByte(value.flag)
    }
}

