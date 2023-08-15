package com.ustadmobile.meshrabiya.log

import android.util.Log

class MNetLoggerStdout(
    private val minLogLevel: Int = Log.VERBOSE,
): MNetLogger() {

    private fun doLog(priority: Int, message: String, exception: Exception?) {
        println(buildString {
            append("[${priorityLabel(priority)}]: $message")
            if(exception != null)
                append(exception.stackTraceToString())
        })
    }

    override fun invoke(priority: Int, message: String, exception: Exception?) {
        if(priority >= minLogLevel)
            doLog(priority, message, exception)
    }

    override fun invoke(priority: Int, message: () -> String, exception: Exception?) {
        if(priority >= minLogLevel)
            doLog(priority, message(), exception)
    }
}
