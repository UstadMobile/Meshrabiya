package com.ustadmobile.meshrabiya.ext

import org.junit.Assert
import org.junit.Test
import java.net.InetAddress

class IntExtTest {

    @Test
    fun givenAddr_whenConvertedToFromInt_thenShouldMatch() {
        val inetAddress = InetAddress.getByName("192.168.49.1")
        val addressToInt = inetAddress.address.ip4AddressToInt()
        val inetAddressFromInt = InetAddress.getByAddress(addressToInt.addressToByteArray())
        Assert.assertEquals(inetAddress, inetAddressFromInt)
        Assert.assertEquals("192.168.49.1", addressToInt.addressToDotNotation())
    }

}