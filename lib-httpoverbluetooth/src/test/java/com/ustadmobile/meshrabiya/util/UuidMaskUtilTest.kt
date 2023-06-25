package com.ustadmobile.meshrabiya.util

import org.junit.Assert
import org.junit.Test
import java.util.UUID

class UuidMaskUtilTest {

    @Test
    fun givenUuidMaskAndPort_whenMaskedAndPortExtracted_thenShouldMatch() {
        val uuidMask = UUID.randomUUID()
        val port = 50000
        val uuidForMaskAndPort = uuidForMaskAndPort(uuidMask, port)

        Assert.assertEquals(port, uuidForMaskAndPort.maskedPort())
        Assert.assertTrue(uuidForMaskAndPort.matchesMask(uuidMask))
        Assert.assertFalse(UUID.randomUUID().matchesMask(uuidMask))
    }

}