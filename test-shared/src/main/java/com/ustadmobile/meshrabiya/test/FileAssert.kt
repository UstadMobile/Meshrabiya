package com.ustadmobile.meshrabiya.test

import com.ustadmobile.meshrabiya.md5sum
import org.junit.Assert
import java.io.File

fun assertFileContentsAreEqual(
    expected: File,
    actual: File,
) {
    Assert.assertEquals(expected.length(), actual.length())
    val expectedMd5  = expected.md5sum()
    assertByteArrayEquals(expectedMd5, 0, actual.md5sum(), 0, expectedMd5.size)
}
