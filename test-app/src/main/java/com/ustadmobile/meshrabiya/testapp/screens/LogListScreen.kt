package com.ustadmobile.meshrabiya.testapp.screens

import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.LogListUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.LogListViewModel
import org.kodein.di.compose.localDI
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.ustadmobile.meshrabiya.testapp.viewmodel.LogFile
import java.io.File

@Composable
fun LogListScreen(
    viewModel: LogListViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                LogListViewModel(it)
            },
            defaultArgs = null
        )
    ),
    onSetAppUiState: (AppUiState) -> Unit,
) {

    val uiState by viewModel.uiState.collectAsState(initial = LogListUiState())
    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState)
    }

    LogListScreen(
        uiState = uiState,
        onClickDelete = viewModel::onClickDelete
    )
}

@Composable
fun LogListScreen(
    uiState: LogListUiState,
    onClickDelete: (LogFile) -> Unit,
) {
    val context = LocalContext.current

    fun shareLogFile(logFile: File) {
        val uri = FileProvider.getUriForFile(
            context, "com.ustadmobile.meshrabiya.testapp.fileprovider", logFile
        )

        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.type = "text/plain"
        context.startActivity(Intent.createChooser(intent, null))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(
            items = uiState.logFiles,
            key = { _, logFile -> logFile.file.name },
        ) { index, logFile ->
            ListItem(
                headlineContent = { Text(logFile.file.name) },
                trailingContent = {
                    Row {
                        if(index != 0) {
                            IconButton(
                                onClick = {
                                    onClickDelete(logFile)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete"
                                )
                            }
                        }

                        IconButton(
                            onClick = { shareLogFile(logFile.file) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share"
                            )
                        }
                    }
                }
            )

        }
    }
}
