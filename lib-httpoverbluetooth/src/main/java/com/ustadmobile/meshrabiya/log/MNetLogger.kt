package com.ustadmobile.meshrabiya.log

import android.util.Log

abstract class MNetLogger {

    abstract operator fun invoke(priority: Int, message: String, exception: Exception? = null)

    abstract operator fun invoke(priority: Int, message: () -> String, exception: Exception? = null)

    companion object {

        fun priorityLabel(priority: Int) = when(priority) {
            Log.DEBUG -> "Debug"
            Log.ERROR -> "Error"
            Log.WARN -> "Warn"
            Log.ASSERT -> "Assert"
            Log.VERBOSE -> "Verbose"
            Log.INFO -> "Info"
            else -> "Err-Priority-unknown"
        }

    }

}