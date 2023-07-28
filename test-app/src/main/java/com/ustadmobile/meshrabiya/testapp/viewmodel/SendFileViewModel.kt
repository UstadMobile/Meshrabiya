package com.ustadmobile.meshrabiya.testapp.viewmodel


data class SendFileUiState(
    val pendingId: Int,
)

//Screen is essentially a list of pending transfers with a FAB to send a file. Clicking the fab triggers
//the file selector, then selecting a recipient.
class SendFileViewModel {
}