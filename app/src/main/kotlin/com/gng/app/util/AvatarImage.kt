package com.gng.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Turns a picked photo into a small centre-cropped square JPEG, ready to upload as an avatar. */
object AvatarImage {
    private const val MAX_DECODE_EDGE = 2048

    suspend fun squareJpeg(context: Context, uri: Uri, edge: Int = 512, quality: Int = 88): ByteArray =
        withContext(Dispatchers.IO) {
            val source = decode(context, uri)
            val side = min(source.width, source.height)
            val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(cropped, edge, edge, true)
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        }

    /** A picked photo, upright and at most [maxEdge] pixels on its longest side. */
    fun decode(context: Context, uri: Uri, maxEdge: Int = MAX_DECODE_EDGE): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the photo's EXIF rotation for us.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > maxEdge) decoder.setTargetSampleSize(longest / maxEdge + 1)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = max(1, max(bounds.outWidth, bounds.outHeight) / maxEdge)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("Couldn't read that photo")
    }
}
