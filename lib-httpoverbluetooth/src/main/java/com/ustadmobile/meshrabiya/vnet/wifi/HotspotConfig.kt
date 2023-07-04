package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.WifiManager
import android.os.Build
import com.ustadmobile.meshrabiya.ext.getStringOrThrow
import com.ustadmobile.meshrabiya.ext.putStringFromBytes
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Serializable
data class HotspotConfig(
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val hotspotType: HotspotType,
    val bssid: String? = null,
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
        val ssid = if(Build.VERSION.SDK_INT >= 33) {
            //As per https://developer.android.com/reference/android/net/wifi/WifiSsid#toString()
            // Any WiFi ssid that is in UTF-8 will be as a string with quotes.
            // No support for ssid with non UTF-8 SSID.
            softApConfig.wifiSsid.toString().removeSurrounding("\"")
        }else {
            softApConfig.ssid
        }
        val passphrase = softApConfig.passphrase
        val bssid = softApConfig.bssid
        if(ssid != null && passphrase != null) {
            HotspotConfig(
                ssid = ssid,
                passphrase = passphrase,
                port = port,
                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                bssid = bssid?.toString()
            )
        }else {
            null
        }
    }else {
        val wifiConfig = wifiConfiguration
        val ssid = wifiConfig?.SSID
        val passphrase = wifiConfig?.preSharedKey?.removeSurrounding("\"")
        val bssid = wifiConfig?.BSSID
        if(ssid != null && passphrase != null) {
            HotspotConfig(
                ssid = ssid,
                passphrase = passphrase,
                port = port,
                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                bssid = bssid,
            )
        }else {
            null
        }
    }
}
