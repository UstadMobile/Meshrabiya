package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.MacAddress
import androidx.annotation.RequiresApi
import java.security.SecureRandom
import java.util.Arrays
import java.util.Random

/**
 * Android's own random mac address generation is called here:
 * https://cs.android.com/android/platform/superproject/+/android-13.0.0_r69:packages/modules/Wifi/service/java/com/android/server/wifi/WifiApConfigStore.java;l=506
 *
 * This calls the hidden APIL
 * MacAddressUtils.createRandomUnicastAddress();
 *
 * Which is implemented here:
 * https://cs.android.com/android/platform/superproject/+/android-13.0.0_r69:frameworks/libs/net/common/framework/com/android/net/module/util/MacAddressUtils.java;l=63
 *
 * This function is a copy: put here to avoid requiring additional hidden API usage.
 */
@RequiresApi(28)
object MacAddressUtils {

    private const val VALID_LONG_MASK = (1L shl 48) - 1
    private val LOCALLY_ASSIGNED_MASK = longAddrFromByteAddr(
        MacAddress.fromString("2:0:0:0:0:0").toByteArray()
    )
    private val MULTICAST_MASK = longAddrFromByteAddr(
        MacAddress.fromString("1:0:0:0:0:0").toByteArray()
    )
    private val OUI_MASK = longAddrFromByteAddr(
        MacAddress.fromString("ff:ff:ff:0:0:0").toByteArray()
    )
    private val NIC_MASK = longAddrFromByteAddr(
        MacAddress.fromString("0:0:0:ff:ff:ff").toByteArray()
    )

    // Matches WifiInfo.DEFAULT_MAC_ADDRESS
    private val DEFAULT_MAC_ADDRESS = MacAddress.fromString("02:00:00:00:00:00")
    private const val ETHER_ADDR_LEN = 6



    @RequiresApi(28)
    fun createRandomUnicastAddress(): MacAddress {
        return createRandomUnicastAddress(null, SecureRandom())
    }

    /**
     * Returns a randomly generated MAC address using the given Random object and the same
     * OUI values as the given MacAddress.
     *
     * The locally assigned bit is always set to 1. The multicast bit is always set to 0.
     *
     * @param base a base MacAddress whose OUI is used for generating the random address.
     * If base == null then the OUI will also be randomized.
     * @param r a standard Java Random object used for generating the random address.
     * @return a random locally assigned MacAddress.
     */
    fun createRandomUnicastAddress(
        base: MacAddress?,
        r: Random
    ): MacAddress {
        var addr: Long
        addr = if (base == null) {
            r.nextLong() and VALID_LONG_MASK
        } else {
            (longAddrFromByteAddr(base.toByteArray()) and OUI_MASK
                    or (NIC_MASK and r.nextLong()))
        }
        addr = addr or LOCALLY_ASSIGNED_MASK
        addr = addr and MULTICAST_MASK.inv()
        val mac = MacAddress.fromBytes(byteAddrFromLongAddr(addr))
        return if (mac == DEFAULT_MAC_ADDRESS) {
            createRandomUnicastAddress(base, r)
        } else mac
    }

    /**
     * Convert a long address to byte address.
     */
    fun byteAddrFromLongAddr(addr: Long): ByteArray {
        @Suppress("NAME_SHADOWING") //Copied from original java code and converted by IDE
        var addr = addr
        val bytes = ByteArray(ETHER_ADDR_LEN)
        var index = ETHER_ADDR_LEN
        while (index-- > 0) {
            bytes[index] = addr.toByte()
            addr = addr shr 8
        }
        return bytes
    }


    /**
     * Convert a byte address to long address.
     */
    fun longAddrFromByteAddr(addr: ByteArray): Long {
        if (!isMacAddress(addr)) {
            throw IllegalArgumentException(
                Arrays.toString(addr) + " was not a valid MAC address"
            )
        }
        var longAddr: Long = 0
        for (b in addr) {
            val uint8Byte = b.toInt() and 0xff
            longAddr = (longAddr shl 8) + uint8Byte
        }
        return longAddr
    }

    /**
     * Returns true if the given byte array is a valid MAC address.
     * A valid byte array representation for a MacAddress is a non-null array of length 6.
     *
     * @param addr a byte array.
     * @return true if the given byte array is not null and has the length of a MAC address.
     */
    fun isMacAddress(addr: ByteArray?): Boolean {
        return addr != null && addr.size == ETHER_ADDR_LEN
    }

}

