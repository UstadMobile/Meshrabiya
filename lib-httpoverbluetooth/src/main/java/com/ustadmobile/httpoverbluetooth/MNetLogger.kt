package com.ustadmobile.httpoverbluetooth

fun interface MNetLogger {

    operator fun invoke(priority: Int, message: String, exception: Exception?)

}