package com.ustadmobile.httpoverbluetooth.ext

inline fun <T> List<T>.appendOrReplace(
    item: T,
    replace: (T) -> Boolean
): List<T> {
    val indexOfItemToReplace = indexOfFirst(replace)
    return if(indexOfItemToReplace < 0) {
        buildList {
            addAll(this)
            add(item)
        }
    }else {
        toMutableList().also {
            it[indexOfItemToReplace] = item
        }
    }
}