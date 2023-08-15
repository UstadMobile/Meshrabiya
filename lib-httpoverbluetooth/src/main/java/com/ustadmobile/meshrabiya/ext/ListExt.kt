package com.ustadmobile.meshrabiya.ext

inline fun <T> List<T>.appendOrReplace(
    item: T,
    replace: (T) -> Boolean
): List<T> {
    val indexOfItemToReplace = indexOfFirst(replace)
    return if(indexOfItemToReplace == -1) {
        buildList {
            addAll(this@appendOrReplace)
            add(item)
        }
    }else {
        toMutableList().also {
            it[indexOfItemToReplace] = item
        }
    }
}

fun <T> List<T>.trimIfExceeds(numItems: Int): List<T> {
    return if(size > numItems)
        subList(0, numItems)
    else
        this
}
