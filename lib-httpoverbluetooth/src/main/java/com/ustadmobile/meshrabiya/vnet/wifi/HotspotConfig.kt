package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.WifiManager
import android.os.Build
import com.ustadmobile.meshrabiya.ext.getStringOrThrow
import com.ustadmobile.meshrabiya.ext.putStringFromBytes
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HotspotConfig(
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val hotspotType: HotspotType
) {

    private val ssidBytes: ByteArray? by lazy {
        ssid.encodeToByteArray()
    }

    private val passphraseBytes: ByteArray? by lazy {
        passphrase.encodeToByteArray()
    }

    //Add 4 bytes for each string ... where the length is encoded stored and 4 bytes for the port
    val sizeInBytes: Int
        get() = (ssidBytes?.size ?: 0) + (passphraseBytes?.size ?: 0) + 16

    fun toBytes(
        byteArray: ByteArray,
        offset: Int,
    ): Int {

        ByteBuffer.wrap(byteArray, offset, sizeInBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .putStringFromBytes(ssidBytes)
            .putStringFromBytes(passphraseBytes)
            .putInt(port)
            .putInt(hotspotType.flag)

        return sizeInBytes
    }

    fun toBytes(): ByteArray {
        return ByteArray(sizeInBytes).also {
            toBytes(it, 0)
        }
    }


    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int
        ): HotspotConfig {
            val byteBuf = ByteBuffer.wrap(byteArray, offset, byteArray.size - offset)
                .order(ByteOrder.BIG_ENDIAN)
            val ssid = byteBuf.getStringOrThrow()
            val passphrase = byteBuf.getStringOrThrow()
            val port = byteBuf.int
            val hotspotType = HotspotType.fromFlag(byteBuf.int)

            return HotspotConfig(
                ssid = ssid,
                passphrase = passphrase,
                port = port,
                hotspotType = hotspotType,
            )

        }

    }

}

fun WifiManager.LocalOnlyHotspotReservation.toLocalHotspotConfig(
    port: Int,
): HotspotConfig? {
    return if(Build.VERSION.SDK_INT >= 30) {
        val softApConfig = softApConfiguration
        val ssid = softApConfig.ssid
        val passphrase = softApConfig.passphrase
        if(ssid != null && passphrase != null) {
            HotspotConfig(
                ssid = ssid,
                passphrase = passphrase,
                port = port,
                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
            )
        }else {
            null
        }
    }else {
        val wifiConfig = wifiConfiguration
        val ssid = wifiConfig?.SSID
        val passphrase = wifiConfig?.preSharedKey?.removeSurrounding("\"")
        if(ssid != null && passphrase != null) {
            HotspotConfig(
                ssid = ssid,
                passphrase = passphrase,
                port = port,
                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
            )
        }else {
            null
        }
    }
}
