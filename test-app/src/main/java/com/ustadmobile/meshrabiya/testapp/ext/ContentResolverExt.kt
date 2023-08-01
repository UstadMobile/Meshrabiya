package com.ustadmobile.meshrabiya.testapp.ext

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toFile

data class UriNameAndSize(
    val name: String?,
    val size: Long,
)

fun ContentResolver.getUriNameAndSize(uri: Uri): UriNameAndSize {
    return if(uri.scheme == "file") {
        val uriFile = uri.toFile()
        UriNameAndSize(uriFile.name, uriFile.length())
    }else {
        query(
            uri, null, null, null, null
        )?.use { cursor ->
            var nameIndex = 0
            var sizeIndex = 0
            if(cursor.moveToFirst() &&
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).also { nameIndex = it } >= 1 &&
                cursor.getColumnIndex(OpenableColumns.SIZE).also { sizeIndex = it } >= 1
            ) {
                val size = if(cursor.isNull(sizeIndex)) { null } else {
                    cursor.getString(sizeIndex)
                }
                UriNameAndSize(cursor.getString(nameIndex), size?.toLong() ?: -1L)
            }else {
                null
            }
        } ?: UriNameAndSize(null, -1)
    }
}
