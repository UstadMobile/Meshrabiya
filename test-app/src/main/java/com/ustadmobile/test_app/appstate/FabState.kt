package com.ustadmobile.test_app.appstate

import androidx.compose.ui.graphics.vector.ImageVector

data class FabState(
    val visible: Boolean = false,
    val label: String? = null,
    val icon: ImageVector? = null,
    val onClick: () -> Unit = { },
) {
}