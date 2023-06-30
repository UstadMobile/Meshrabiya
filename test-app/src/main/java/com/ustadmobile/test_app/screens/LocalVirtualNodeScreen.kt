package com.ustadmobile.test_app.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.unit.dp
import com.ustadmobile.test_app.viewmodel.LocalVirtualNodeViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ustadmobile.test_app.ViewModelFactory
import com.ustadmobile.test_app.viewmodel.LocalVirtualNodeUiState
import org.kodein.di.compose.localDI

@Composable
fun LocalVirtualNodeScreen(
    viewModel: LocalVirtualNodeViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                LocalVirtualNodeViewModel(it)
            },
            defaultArgs = null,
        )
    )
){
    val uiState: LocalVirtualNodeUiState by viewModel.uiState.collectAsState(
        initial = LocalVirtualNodeUiState("")
    )

    LocalVirtualNodeScreen(uiState)

    //QR code, port forwarding rules
}

@Composable
fun LocalVirtualNodeScreen(
    uiState: LocalVirtualNodeUiState,
){
    Text(
        text = uiState.address,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
