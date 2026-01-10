
package com.ustadmobile.meshrabiya.service.ml

import android.graphics.Bitmap
import java.io.Serializable

/**
 * MLServiceInput represents the input to an ML service. It can contain an image (bitmap)
 * and/or structured data for inference tasks.
 */
data class MLServiceInput(
    /** Optional bitmap for image-based ML tasks (e.g., OCR, classification) */
    val bitmap: Bitmap? = null,
    /** Optional structured data for non-image ML tasks */
    val data: Map<String, Any>? = null,
    /** Optional text input for NLP or translation tasks */
    val text: String? = null
) : Serializable
