package com.ustadmobile.meshrabiya.vnet.wifi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Flag that indicates if a Hotspot is going to be persistent (e.g. will keep the same BSSID, SSID,
 * etc. for future connect attempts).
 *
 * If the BSSID is probably persistent, but other details are not, we might want to use
 * CompanionDeviceManager on Android 11+ to avoid a prompt for the user when reconnecting.
 */
@Serializable(with = HotspotPersistenceTypeSerializer::class)
enum class HotspotPersistenceType(val flag: Byte) {

    /**
     * The hotspot is not persistent at all. This is the behavior of LocalOnlyHotspot on
     * Android 11 and 12 where the BSSID is randomized each time it is created and (probably) for
     * WifiDirect groups on Android 9 and below.
     */
    NONE(0),

    /**
     * The BSSID is probably going to stay the same. This is the behavior of LocalOnlyHotspot on
     * Android 10 and prior. The BSSID will stay the same unless the device is restarted.
     */
    PROBABLY_BSSID(1),

    /**
     * Everything is fully persistent (e.g. we set it). This is the behavior of LocalOnlyHotspot on
     * Android 13+ and for WifiDirect Groups on Android 10+
     */
    FULL(2);


    companion object {
        fun fromFlag(flag: Byte): HotspotPersistenceType {
            return HotspotPersistenceType.values().first { it.flag == flag }
        }
    }
}

object HotspotPersistenceTypeSerializer: KSerializer<HotspotPersistenceType> {
    override fun deserialize(decoder: Decoder): HotspotPersistenceType {
        return HotspotPersistenceType.fromFlag(decoder.decodeByte())
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("hotspotPersistenceType", PrimitiveKind.BYTE)

    override fun serialize(encoder: Encoder, value: HotspotPersistenceType) {
        encoder.encodeByte(value.flag)
    }
}
