package com.ustadmobile.meshrabiya.testapp.ext

inline fun <T> List<T>.updateItem(
    updatePredicate: (T) -> Boolean,
    function: (T) -> T,
): List<T> {
    val indexToUpdate = indexOfFirst(updatePredicate)
    return if(indexToUpdate == -1) {
        this
    }else {
        toMutableList().also {newList ->
            newList[indexToUpdate] = function(this[indexToUpdate])
        }.toList()
    }
}
