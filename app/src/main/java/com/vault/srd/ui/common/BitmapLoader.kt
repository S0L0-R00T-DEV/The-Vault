package com.vault.srd.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return max(1, inSampleSize)
}

private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    val file = File(path)
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
        inPreferredConfig = Bitmap.Config.RGB_565
        inDither = true
    }
    return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
}

@Composable
fun rememberBitmapFromFile(
    path: String?,
    reqWidth: Int = 1200,
    reqHeight: Int = 1200
): State<Bitmap?> {
    return produceState<Bitmap?>(initialValue = null, key1 = path, key2 = reqWidth, key3 = reqHeight) {
        if (path.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(path, reqWidth, reqHeight)
        }
    }
}
