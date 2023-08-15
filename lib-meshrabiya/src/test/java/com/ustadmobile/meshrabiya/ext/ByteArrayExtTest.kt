package com.ustadmobile.meshrabiya.ext

import org.junit.Assert
import org.junit.Test

class ByteArrayExtTest {

    @Test
    fun givenTwoAddresses_whenCheckPrefixMatches_thenShouldCalculateCorrectly() {
        val addr1 = byteArrayOf(169.toByte(),  254.toByte(), 1.toByte(), 1.toByte())
        val addr2 = byteArrayOf(169.toByte(),  254.toByte(), 128.toByte(), 64.toByte())

        Assert.assertTrue(addr1.prefixMatches(16, addr2))
        Assert.assertTrue(addr1.prefixMatches(8, addr2))
        Assert.assertTrue(addr1.prefixMatches(12, addr2))

        Assert.assertFalse(addr1.prefixMatches(24, addr2))
        Assert.assertFalse(addr1.prefixMatches(17, addr2))
    }

}