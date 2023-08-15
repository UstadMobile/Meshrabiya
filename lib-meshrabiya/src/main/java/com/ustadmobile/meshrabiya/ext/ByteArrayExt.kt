package com.ustadmobile.meshrabiya.ext

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.and
import kotlin.math.min as mathMin

fun ByteArray.ip4AddressToInt() : Int{
    return ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN).int
}

/**
 * Check if two network addresses match up to the given network prefix length.
 *
 * @receiver a ByteArray that represents a network address
 * @param networkPrefixLength the netmask prefix length to check (in bits). e.g. to check a /16 match then 16, etc.
 * @param otherAddress a ByteArray that represents another network address
 *
 * @return true if the addresses are the same for the first networkPrefixLength bits, false otherwise
 */
fun ByteArray.prefixMatches(
    networkPrefixLength: Int,
    otherAddress: ByteArray
) : Boolean {
    var bitsCompared = 0
    var bitsToCompare = 0

    var index = 0
    var mask : Byte
    while(bitsCompared < networkPrefixLength) {
        bitsToCompare = mathMin(8, networkPrefixLength -  bitsCompared)

        if(bitsToCompare == 8) {
            if(this[index] != otherAddress[index])
                return false
        }else {
            for(b in 0 until bitsToCompare) {
                mask = 1.shl(b).toByte()

                if(this[index].and(mask) != otherAddress[index].and(mask))
                    return false
            }
        }

        bitsCompared += bitsToCompare
        index++
    }

    return true
}

