package com.ustadmobile.meshrabiya

fun interface MNetLogger {

    operator fun invoke(priority: Int, message: String, exception: Exception?)

}