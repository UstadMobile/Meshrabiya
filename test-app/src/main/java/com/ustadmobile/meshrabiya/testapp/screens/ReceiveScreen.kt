package com.ustadmobile.meshrabiya.testapp.screens

import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.server.TestAppServer
import com.ustadmobile.meshrabiya.testapp.viewmodel.ReceiveUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.ReceiveViewModel
import org.kodein.di.compose.localDI

@Composable
fun ReceiveScreen(
    onSetAppUiState: (AppUiState) -> Unit,
    viewModel: ReceiveViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                ReceiveViewModel(it)
            },
            defaultArgs = null,
        )
    ),
) {
    val uiState by viewModel.uiState.collectAsState(ReceiveUiState())

    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState)
    }

    ReceiveScreen(
        uiState = uiState,
        onClickAccept = viewModel::onClickAcceptIncomingTransfer,
        onClickDecline = viewModel::onClickDeclineIncomingTransfer,
        onClickDelete = viewModel::onClickDeleteTransfer,
    )
}

@Composable
fun ReceiveScreen(
    uiState: ReceiveUiState,
    onClickAccept: (TestAppServer.IncomingTransfer) -> Unit =  { },
    onClickDecline: (TestAppServer.IncomingTransfer) -> Unit = { },
    onClickDelete: (TestAppServer.IncomingTransfer) -> Unit = { },
) {
    val context = LocalContext.current

    fun openTransfer(transfer: TestAppServer.IncomingTransfer) {
        val file = transfer.file
        if(file != null && transfer.status == TestAppServer.Status.COMPLETED) {
            val uri = FileProvider.getUriForFile(
                context, "com.ustadmobile.meshrabiya.testapp.fileprovider", file
            )

            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                file.extension
            ) ?: "*/*"
            intent.setDataAndType(uri, mimeType)
            if(intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            }else {
                Toast.makeText(context, "No app found to open file", Toast.LENGTH_LONG).show()
            }

        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = uiState.incomingTransfers,
            key = { Triple(it.fromHost, it.id, it.requestReceivedTime) }
        ) { transfer ->
            ListItem(
                modifier = Modifier
                    .clickable {
                        openTransfer(transfer)
                    }
                    .fillMaxWidth(),
                headlineContent = {
                    Text(transfer.name)
                },
                supportingContent = {
                    Column {
                        Text("From ${transfer.fromHost.hostAddress} (${transfer.status})")
                        Text(buildString {
                            append("${transfer.transferred} / ${transfer.size} bytes")
                            if(transfer.status == TestAppServer.Status.COMPLETED) {
                                append(" @ ${transfer.size / transfer.transferTime}KB/s (${transfer.transferTime})ms")
                            }
                        })

                        if(transfer.status == TestAppServer.Status.PENDING) {
                            Row {
                                OutlinedButton(
                                    modifier = Modifier.padding(start = 0.dp, top = 8.dp, bottom = 8.dp),
                                    onClick = {
                                        onClickAccept(transfer)
                                    }
                                ) {
                                    Text("Accept")
                                }

                                OutlinedButton(
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                                    onClick = {
                                        onClickDecline(transfer)
                                    }
                                ) {
                                    Text("Decline")
                                }
                            }
                        }

                        if(transfer.status == TestAppServer.Status.COMPLETED) {
                            OutlinedButton(
                                onClick = {
                                    openTransfer(transfer)
                                }
                            ) {
                                Text("Open")
                            }
                        }
                    }
                },
                trailingContent = {
                    if(transfer.status == TestAppServer.Status.COMPLETED) {
                        IconButton(
                            onClick = {
                                onClickDelete(transfer)
                            }
                        ) {
                            Icon(
                                imageVector  = Icons.Default.Delete,
                                contentDescription = "Delete",
                            )
                        }
                    }
                },
            )
        }
    }
}
