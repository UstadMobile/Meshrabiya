package com.ustadmobile.meshrabiya.ext

import java.util.Enumeration


/**
 * Same as firstNotNullOfOrNull as per Kotlin Collections extensions
 */
inline fun <T, R> Enumeration<T>.firstNotNullOfOrNull(
    transform: (T) -> R?
): R? {
    while(hasMoreElements()) {
        val transformed = transform(nextElement())
        if(transformed != null)
            return transformed
    }

    return null
}
