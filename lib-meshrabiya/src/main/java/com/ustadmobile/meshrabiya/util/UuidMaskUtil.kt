package com.ustadmobile.meshrabiya.util

import java.util.UUID


/**
 * Using a UUID mask all clients share the same UUID mask (the first 112 bits of the UUID) and the
 * remaining 16 bits are randomly allocated. This allows a preset to be shared without any two clients
 * using exactly the same UUID.
 *
 * This also allows each node to have a single port that can then be used to determine the final UUID
 * e.g. if the mask and port are known, we know the UUID that will be used for a particular service.
 */
fun uuidForMaskAndPort(mask: UUID, port: Int): UUID{
    val newLeastSigBits = mask.leastSignificantBits.shl(16)
        .or(port.toLong())
    return UUID(mask.mostSignificantBits, newLeastSigBits)
}

/**
 * Return the port portion of this UUID if it was created using uuidForMaskAndPort
 */
fun UUID.maskedPort(): Int {
    return leastSignificantBits.and(0xffff).toInt()
}

/**
 * Check if given UUID matches a mask (e.g. as used in uuidForMaskAndPort)
 */
fun UUID.matchesMask(mask: UUID): Boolean {
    if(mask.mostSignificantBits != mostSignificantBits)
        return false

    //Cut off the rightmost 16 bits (e.g. where the port is stored)
    val uuidLeastSigBitsWithoutPort = leastSignificantBits.shr(16).shl(16)
    return mask.leastSignificantBits.shl(16) == uuidLeastSigBitsWithoutPort
}
