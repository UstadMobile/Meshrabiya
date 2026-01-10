
package com.ustadmobile.meshrabiya.service.ml

import java.io.Serializable

/**
 * MLServiceResult represents the outcome of an ML inference operation.
 * It contains a success flag, result map, and optional error message.
 */
data class MLServiceResult(
    /** True if inference succeeded */
    val success: Boolean,
    /** Result data from inference (labels, text, etc.) */
    val result: Map<String, Any>? = null,
    /** Error message if inference failed */
    val error: String? = null
) : Serializable {
    companion object {
        /** Factory for successful result */
        fun success(result: Map<String, Any>) = MLServiceResult(true, result, null)

        /** Factory for error result */
        fun error(message: String) = MLServiceResult(false, null, message)
    }
}
