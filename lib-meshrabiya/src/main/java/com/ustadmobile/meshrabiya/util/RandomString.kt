package com.ustadmobile.meshrabiya.util

import kotlin.random.Random

private val CHAR_POOL_DEFAULT = "abcdefghikjmnpqrstuvxwyz23456789"


/**
 * Generate a random string (e.g. default password, class code, etc.
 */
fun randomString(length: Int, charPool: String = CHAR_POOL_DEFAULT): String {
    return (1 .. length).map { i -> charPool.get(Random.nextInt(0, charPool.length)) }
        .joinToString(separator = "")
}

