package com.ustadmobile.meshrabiya.log

import android.util.Log

abstract class MNetLogger {

    abstract operator fun invoke(priority: Int, message: String, exception: Exception? = null)

    abstract operator fun invoke(priority: Int, message: () -> String, exception: Exception? = null)

    companion object {

        fun priorityLabel(priority: Int) = when(priority) {
            Log.DEBUG -> "D"
            Log.ERROR -> "E"
            Log.WARN -> "W"
            Log.ASSERT -> "A"
            Log.VERBOSE -> "V"
            Log.INFO -> "I"
            else -> "Err-Priority-unknown"
        }

    }

}