package com.ustadmobile.meshrabiya.vnet.wifi

import com.ustadmobile.meshrabiya.ext.getBoolean
import com.ustadmobile.meshrabiya.ext.getStringOrThrow
import com.ustadmobile.meshrabiya.ext.putBoolean
import com.ustadmobile.meshrabiya.ext.putStringFromBytes
import com.ustadmobile.meshrabiya.ext.requireHostAddress
import inet.ipaddr.IPAddressString
import inet.ipaddr.mac.MACAddress
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.lang.IllegalStateException
import java.net.Inet6Address
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * This data class represents the information needed to connect to the WiFi of a given node.
 *
 * @param nodeVirtualAddr The virtual address of the node itself
 * @param ssid the Wifi ssid to connect to
 * @param passphrase the passphrase for the Wifi to connect to
 * @param linkLocalAddr the ipv6 link local address of the node on the interface that provides the
 *                      hotspot, if known. This is required when using WiFi direct hotspots because
 *                      the system will assign the same ipv4 address (192.168.49.1) to all devices.
 * @param port the UDP port that is used for virtualpackets. The same port will be used for socket
 *             chains on TCP.
 * @param hotspotType the type of connection being offered - currently only Wifi Direct group and
 *                    LocalOnlyHotspot is supported. Wifi Aware may be added in future.
 * @param persistenceType the expected persistence of this config. This helps the client decide
 *        whether or not to prompt the user to save the config via companiondevicemanager.
 * @param bssid the expected bssid (if known), optional
 *
 * Node virtual address should be known before connecting. This is required to lookup previously
 * stored BSSIDs for the network (to see if we need to use companiondevicemanager dialog or not).
 */
@Serializable
data class WifiConnectConfig(
    val nodeVirtualAddr: Int,
    val ssid: String,
    val passphrase: String,
    @Serializable(with = Inet6AddressSerializer::class)
    val linkLocalAddr: Inet6Address?,
    val port: Int,
    val hotspotType: HotspotType,
    val persistenceType: HotspotPersistenceType = HotspotPersistenceType.NONE,
    val band: ConnectBand = ConnectBand.BAND_UNKNOWN,
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
     * hotspotType: 1 byte
     * band: 1 byte
     * persistenceType: 1 byte
     * linkLocalAddrPresent: 1 byte (0 or 1)
     * linkLocalAddr: 16 bytes (IPv6 address)
     *
     */
    val sizeInBytes: Int
        get() = 4 + (4 + (ssidBytes?.size ?: 0) ) + (4 + (passphraseBytes?.size ?: 0)) + 4 + 1 + 1 + 1 + 1 + 16

    /**
     * We need to provide the BSSID when connecting on Android 10+ to avoid a dialog on
     * subsequent connection attempts.
     *
     * 90%+ of the time, the IPv6 link local address contains the Mac Address (BSSID) - hence we can
     * use this to avoid the need to use CompanionDeviceManager or a WiFi scan.
     *
     * This is done using the IPAddress lib - as per:
     * https://github.com/seancfoley/IPAddress/wiki/Code-Examples-4:-Converting-to-and-from-Other-Formats#convert-tofrom-ipv6-address-fromto-mac-address
     *
     * This is known to work successfully to get the Mac Address for the Wifi Direct Group owner on:
     * Nokia 1 (Android 10),
     * Nokia 5 Plus (Android 10),
     * OnePlus N10 (Android 11),
     * Samsung SM-X200 (Android 13)
     * Nokia 5.4 (Android 12)
     * Nothing
     *
     * Does not work on:
     * Xiaomi Redmi A3 (Android 11)
     */
    val linkLocalToMacAddress: MACAddress?
        get() = linkLocalAddr?.let {
            IPAddressString(it.hostAddress).getAddress().toIPv6().toEUI(false)
        }

    fun toBytes(
        byteArray: ByteArray,
        offset: Int,
    ): Int {
        if(linkLocalAddr != null && linkLocalAddr.address.size != 16)
            throw IllegalStateException("Inet6Address is not 16 bytes!")

        ByteBuffer.wrap(byteArray, offset, sizeInBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(nodeVirtualAddr)
            .putStringFromBytes(ssidBytes)
            .putStringFromBytes(passphraseBytes)
            .putInt(port)
            .put(hotspotType.flag)
            .put(band.flag)
            .put(persistenceType.flag)
            .putBoolean(linkLocalAddr != null)
            .apply {
                linkLocalAddr?.also {
                    put(it.address)
                }
            }

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
            val hotspotType = HotspotType.fromFlag(byteBuf.get())
            val connectBand = ConnectBand.fromFlag(byteBuf.get())
            val persistenceType = HotspotPersistenceType.fromFlag(byteBuf.get())
            val hasLinkLocalAddr = byteBuf.getBoolean()
            val linkLocalAddr = if(hasLinkLocalAddr) {
                Inet6Address.getByAddress(ByteArray(16).also { byteBuf.get(it) }) as Inet6Address
            }else {
                null
            }

            return WifiConnectConfig(
                nodeVirtualAddr = nodeVirtualAddr,
                ssid = ssid,
                passphrase = passphrase,
                port = port,
                hotspotType = hotspotType,
                band = connectBand,
                persistenceType = persistenceType,
                linkLocalAddr = linkLocalAddr
            )

        }

    }

}

object Inet6AddressSerializer: KSerializer<Inet6Address> {

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("Inet6Address", PrimitiveKind.STRING)


    override fun deserialize(decoder: Decoder): Inet6Address {
        return Inet6Address.getByName(decoder.decodeString()) as Inet6Address
    }

    override fun serialize(encoder: Encoder, value: Inet6Address) {
        encoder.encodeString(value.requireHostAddress())
    }
}

