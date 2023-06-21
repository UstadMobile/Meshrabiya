package com.ustadmobile.meshrabiya

import android.util.Log

fun interface MNetLogger {

    operator fun invoke(priority: Int, message: String, exception: Exception?)

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