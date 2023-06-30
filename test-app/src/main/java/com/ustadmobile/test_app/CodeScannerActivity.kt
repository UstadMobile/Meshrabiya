package com.ustadmobile.test_app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.budiyev.android.codescanner.CodeScanner
import com.budiyev.android.codescanner.CodeScannerView
import com.ustadmobile.test_app.ui.theme.HttpOverBluetoothTheme

class CodeScannerActivity: ComponentActivity() {

    private fun onCodeDetected(text: String) {
        val intent = Intent().apply {
            putExtra(KEY_QR_TEXT, text)
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HttpOverBluetoothTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CodeScanner(
                        onQRCodeDetected = this::onCodeDetected
                    )
                }
            }
        }

    }

    companion object {

        const val KEY_QR_TEXT = "qrtext"
    }
}


@Composable
fun CodeScanner(
    onQRCodeDetected: (String) -> Unit = {},
) {

    var codeScanner: CodeScanner? by remember {
        mutableStateOf(null)
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = object: DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                codeScanner?.startPreview()
            }

            override fun onPause(owner: LifecycleOwner) {
                codeScanner?.releaseResources()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        factory = { context ->
            CodeScannerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )

                codeScanner = CodeScanner(context.getActivityContext(), this).also {
                    it.setDecodeCallback { result ->
                        onQRCodeDetected(result.text)
                    }
                }

                setOnClickListener {
                    codeScanner?.startPreview()
                }

                if(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    codeScanner?.startPreview()
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = {
            codeScanner?.setDecodeCallback {
                onQRCodeDetected(it.text)
            }
        }
    )

}