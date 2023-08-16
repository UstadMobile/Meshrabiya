package com.ustadmobile.meshrabiya.log

import com.ustadmobile.meshrabiya.log.MNetLogger.Companion.priorityLabel
import java.util.concurrent.atomic.AtomicInteger

val LOG_LINE_ID_ATOMIC = AtomicInteger(0)

data class LogLine(
    val line: String,
    val priority: Int,
    val time: Long,
    val lineId: Int = LOG_LINE_ID_ATOMIC.getAndIncrement(),
) {

    fun toString(epochTime: Long): String {
        val time = (time - epochTime) / 1000.toFloat()
        val rounded = (time * 100).toInt() / 100.toFloat()

        return "${priorityLabel(priority)}: t+${rounded}s : $line"
    }

}
