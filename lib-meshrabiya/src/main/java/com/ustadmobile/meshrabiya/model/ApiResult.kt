package com.ustadmobile.meshrabiya.model

/**
 * Represents the result of an API operation.
 */
sealed class ApiResult {
    object Success : ApiResult()
    data class Failure(val error: Throwable) : ApiResult()
}