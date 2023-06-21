package com.ustadmobile.meshrabiya.test

import com.ustadmobile.meshrabiya.MNetLogger

class TestLogger: MNetLogger {
    override fun invoke(priority: Int, message: String, exception: Exception?) {
        println(buildString {
            append("[${MNetLogger.priorityLabel(priority)}]: $message")
            if(exception != null)
                append(exception.stackTraceToString())
        })
    }
}
