package com.ustadmobile.meshrabiya.testapp

import android.util.Log
import com.ustadmobile.meshrabiya.HttpOverBluetoothConstants
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.ext.trimIfExceeds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MNetLoggerAndroid(
    private val minLogLevel: Int = Log.VERBOSE
): MNetLogger() {

    val _recentLogs = MutableStateFlow(emptyList<LogLine>())

    val recentLogs: Flow<List<LogLine>> = _recentLogs.asStateFlow()

    private fun doLog(priority: Int, message: String, exception: Exception?) {
        when (priority) {
            Log.VERBOSE -> Log.v(HttpOverBluetoothConstants.LOG_TAG, message, exception)
            Log.DEBUG -> Log.d(HttpOverBluetoothConstants.LOG_TAG, message, exception)
            Log.INFO -> Log.i(HttpOverBluetoothConstants.LOG_TAG, message, exception)
            Log.WARN -> Log.w(HttpOverBluetoothConstants.LOG_TAG, message, exception)
            Log.ERROR -> Log.e(HttpOverBluetoothConstants.LOG_TAG, message, exception)
            Log.ASSERT -> Log.wtf(HttpOverBluetoothConstants.LOG_TAG, message, exception)
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
                add(LogLine(logDisplay))
                addAll(prev.trimIfExceeds(100))
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
}
