package com.ustadmobile.meshrabiya.log

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

class MNetLoggerStdout(
    private val minLogLevel: Int = Log.VERBOSE,
): MNetLogger() {

    private val lineIdAtomic = AtomicInteger()

    private val epochTime = System.currentTimeMillis()

    private fun doLog(priority: Int, message: String, exception: Exception?) {
        val line = LogLine(message, priority, System.currentTimeMillis(), lineIdAtomic.incrementAndGet())
        println(buildString {
            append(line.toString(epochTime))
            if(exception != null) {
                append(" ")
                append(exception.stackTraceToString())
            }
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
