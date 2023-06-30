package com.ustadmobile.test_app.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.kodein.di.DI

data class LocalVirtualNodeUiState(
    val address: String = "1.2.3.4",
)

class LocalVirtualNodeViewModel(
    di: DI
) : ViewModel(){

    private val _uiState = MutableStateFlow(LocalVirtualNodeUiState())

    val uiState: Flow<LocalVirtualNodeUiState> = _uiState.asStateFlow()

}