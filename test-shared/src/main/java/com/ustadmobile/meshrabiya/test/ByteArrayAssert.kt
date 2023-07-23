package com.ustadmobile.meshrabiya.test

import org.junit.Assert

fun assertByteArrayEquals(
    expected: ByteArray,
    expectedOffset: Int,
    actual: ByteArray,
    actualOffset: Int,
    length: Int,
) {
    for(i in 0 until length) {
        Assert.assertEquals("ByteArray expected[$i + $expectedOffset] == actual[$i + $actualOffset]",
            expected[expectedOffset + i], actual[actualOffset + i])
    }
}

fun ByteArray.contentRangeEqual(
    thisOffset: Int,
    other: ByteArray,
    otherOffset: Int,
    length: Int
) : Boolean {
    for(i in 0 until length) {
        if(this[i + thisOffset] != other[i + otherOffset])
            return false
    }

    return true
}
