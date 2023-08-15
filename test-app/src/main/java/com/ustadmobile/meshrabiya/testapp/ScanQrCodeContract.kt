package com.ustadmobile.meshrabiya.testapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

class ScanQrCodeContract: ActivityResultContract<Unit, String?>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(context, CodeScannerActivity::class.java)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        return if(resultCode == Activity.RESULT_OK) {
            intent?.getStringExtra(CodeScannerActivity.KEY_QR_TEXT)
        }else {
            null
        }
    }
}
