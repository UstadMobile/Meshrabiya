package com.ustadmobile.test_app

import java.util.concurrent.atomic.AtomicInteger

val LOG_LINE_ID_ATOMIC = AtomicInteger(0)

data class LogLine(
    val line: String,
    val lineId: Int = LOG_LINE_ID_ATOMIC.getAndIncrement(),
)
