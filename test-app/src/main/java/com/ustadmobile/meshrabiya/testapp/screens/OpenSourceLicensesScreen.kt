package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState

@Composable
fun OpenSourceLicensesScreen(){
    val state = rememberWebViewState("file:///android_asset/open_source_licenses.html")

    WebView(
        modifier = Modifier.fillMaxSize(),
        state = state
    )
}
