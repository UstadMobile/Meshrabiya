package com.ustadmobile.meshrabiya.testapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.testapp.App.Companion.TAG_LOG_DIR
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import java.io.File

data class LogFile(
    val file: File,
    val size: Long,
    val lastModified: Long,
)

data class LogListUiState(
    val logFiles: List<LogFile> = emptyList(),
    val appUiState: AppUiState = AppUiState(),
)

class LogListViewModel(
    di: DI
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LogListUiState(
            appUiState = AppUiState(
                title = "Logs"
            )
        )
    )

    val uiState: Flow<LogListUiState> = _uiState.asStateFlow()

    val logDir: File by di.instance(tag = TAG_LOG_DIR)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val logFiles: List<LogFile> = (logDir.listFiles()?.toList() ?: emptyList())
                .map {
                    LogFile(
                        file = it,
                        size = it.length(),
                        lastModified = it.lastModified(),
                    )
                }.sortedByDescending { it.lastModified }

            _uiState.update { prev ->
                prev.copy(logFiles = logFiles)
            }
        }
    }

    fun onClickDelete(logFile: LogFile) {
        viewModelScope.launch(Dispatchers.IO) {
            if(logFile.file.delete()) {
                _uiState.update { prev ->
                    prev.copy(
                        logFiles = prev.logFiles.filter { it.file != logFile.file }
                    )
                }
            }
        }
    }

}