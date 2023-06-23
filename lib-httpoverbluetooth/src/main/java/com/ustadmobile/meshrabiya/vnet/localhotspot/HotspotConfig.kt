package com.ustadmobile.meshrabiya.vnet.localhotspot

import android.net.wifi.WifiManager
import android.os.Build
import com.ustadmobile.meshrabiya.ext.getString
import com.ustadmobile.meshrabiya.ext.putStringFromBytes
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HotspotConfig(
    val ssid: String?,
    val passphrase: String?,
) {

    private val ssidBytes: ByteArray? by lazy {
        ssid?.encodeToByteArray()
    }

    private val passphraseBytes: ByteArray? by lazy {
        passphrase?.encodeToByteArray()
    }

    //Add 4 bytes for each string ... where the length is encoded stored
    val sizeInBytes: Int
        get() = (ssidBytes?.size ?: 0) + (passphraseBytes?.size ?: 0) + 8

    fun toBytes(
        byteArray: ByteArray,
        offset: Int,
    ): Int {

        ByteBuffer.wrap(byteArray, offset, sizeInBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .putStringFromBytes(ssidBytes)
            .putStringFromBytes(passphraseBytes)

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
            val ssid = byteBuf.getString()
            val passphrase = byteBuf.getString()
            return HotspotConfig(
                ssid = ssid,
                passphrase = passphrase,
            )

        }

    }

}

fun WifiManager.LocalOnlyHotspotReservation.toLocalHotspotConfig(): HotspotConfig {
    return if(Build.VERSION.SDK_INT >= 30) {
        val softApConfig = softApConfiguration
        HotspotConfig(
            ssid = softApConfig.ssid,
            passphrase = softApConfig.passphrase,
        )
    }else {
        val wifiConfig = wifiConfiguration
        HotspotConfig(
            ssid = wifiConfig?.SSID,
            passphrase = wifiConfig?.preSharedKey?.removeSurrounding("\"")
        )
    }
}
