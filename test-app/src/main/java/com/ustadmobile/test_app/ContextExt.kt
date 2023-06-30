package com.ustadmobile.test_app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

fun Context.getActivityContext(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> this.baseContext.getActivityContext()
    else -> throw IllegalArgumentException("Not an activity context")
}
