package com.ustadmobile.meshrabiya.testapp

import android.content.Context
import android.util.Log
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.ext.trimIfExceeds
import com.ustadmobile.meshrabiya.log.LogLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MNetLoggerAndroid(
    private val minLogLevel: Int = Log.VERBOSE,
    private val logHistoryLines: Int = 300,
): MNetLogger() {

    val epochTime = System.currentTimeMillis()

    private val _recentLogs = MutableStateFlow(emptyList<LogLine>())

    val recentLogs: Flow<List<LogLine>> = _recentLogs.asStateFlow()

    private fun doLog(priority: Int, message: String, exception: Exception?) {
        when (priority) {
            Log.VERBOSE -> Log.v(MeshrabiyaConstants.LOG_TAG, message, exception)
            Log.DEBUG -> Log.d(MeshrabiyaConstants.LOG_TAG, message, exception)
            Log.INFO -> Log.i(MeshrabiyaConstants.LOG_TAG, message, exception)
            Log.WARN -> Log.w(MeshrabiyaConstants.LOG_TAG, message, exception)
            Log.ERROR -> Log.e(MeshrabiyaConstants.LOG_TAG, message, exception)
            Log.ASSERT -> Log.wtf(MeshrabiyaConstants.LOG_TAG, message, exception)
        }

        val logDisplay = buildString {
            append(message)
            if (exception != null) {
                append(" Exception: ")
                append(exception.toString())
            }
        }

        _recentLogs.update { prev ->
            buildList {
                add(LogLine(logDisplay, priority, System.currentTimeMillis()))
                addAll(prev.trimIfExceeds(logHistoryLines - 1))
            }
        }
    }

    override fun invoke(priority: Int, message: () -> String, exception: Exception?) {
        if(priority >= minLogLevel)
            doLog(priority, message(), exception)
    }

    override fun invoke(priority: Int, message: String, exception: Exception?) {
        if(priority >= minLogLevel)
            doLog(priority, message, exception)
    }

    /**
     * Export logs with time/date stamp, device info, etc.
     */
    fun exportAsString(context: Context): String {
        return buildString {
            append(context.meshrabiyaDeviceInfoStr())
            append("==Logs==\n")

            _recentLogs.value.reversed().forEach {
                append(it.toString(epochTime))
                append("\n")
            }
        }
    }
}
