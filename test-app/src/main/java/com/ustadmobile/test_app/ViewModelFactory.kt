package com.ustadmobile.test_app

import android.os.Bundle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner
import org.kodein.di.DI

class ViewModelFactory<T: ViewModel>(
    private val di: DI,
    owner: SavedStateRegistryOwner,
    defaultArgs: Bundle?,
    private val vmFactory: (DI) -> T,
): AbstractSavedStateViewModelFactory(owner, defaultArgs)  {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {
        return vmFactory(di) as T
    }
}