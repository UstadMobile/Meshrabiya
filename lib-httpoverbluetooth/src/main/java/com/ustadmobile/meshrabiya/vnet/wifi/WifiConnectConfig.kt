package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.WifiManager
import android.os.Build
import com.ustadmobile.meshrabiya.ext.getStringOrThrow
import com.ustadmobile.meshrabiya.ext.putStringFromBytes
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Node virtual address should be known before connecting. This is required to lookup previously
 * stored BSSIDs for the network (to see if we need to use companiondevicemanager dialog or not).
 */
@Serializable
data class WifiConnectConfig(
    val nodeVirtualAddr: Int,
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

    /* Size =
     * nodeVirtualAddress: 4 bytes
     * ssid: 4 bytes (string length int) plus string bytes length
     * passphrase: 4 bytes ( string length int) plus string bytes length
     * port: 4 bytes
     * hotspotType: 4 bytes
     *

     */
    val sizeInBytes: Int
        get() = 4 + (4 + (ssidBytes?.size ?: 0) ) + (4 + (passphraseBytes?.size ?: 0)) + 4 + 4

    fun toBytes(
        byteArray: ByteArray,
        offset: Int,
    ): Int {

        ByteBuffer.wrap(byteArray, offset, sizeInBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(nodeVirtualAddr)
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
        ): WifiConnectConfig {
            val byteBuf = ByteBuffer.wrap(byteArray, offset, byteArray.size - offset)
                .order(ByteOrder.BIG_ENDIAN)
            val nodeVirtualAddr = byteBuf.int
            val ssid = byteBuf.getStringOrThrow()
            val passphrase = byteBuf.getStringOrThrow()
            val port = byteBuf.int
            val hotspotType = HotspotType.fromFlag(byteBuf.int)

            return WifiConnectConfig(
                nodeVirtualAddr = nodeVirtualAddr,
                ssid = ssid,
                passphrase = passphrase,
                port = port,
                hotspotType = hotspotType,
            )

        }

    }

}

fun WifiManager.LocalOnlyHotspotReservation.toLocalHotspotConfig(
    nodeVirtualAddr: Int,
    port: Int,
): WifiConnectConfig? {
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
            WifiConnectConfig(
                nodeVirtualAddr = nodeVirtualAddr,
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
            WifiConnectConfig(
                nodeVirtualAddr = nodeVirtualAddr,
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
