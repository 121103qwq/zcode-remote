package io.github.xgy.zcoderemote.scanner

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.WorkerThread
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import java.io.IOException

/**
 * Reads one user-selected image and decodes QR payloads locally.
 *
 * This class is deliberately synchronous and stateless. Call [decode] from a worker thread; it
 * never retains the URI, image, or decoded text after returning.
 */
class QrImageDecoder(
    private val contentResolver: ContentResolver,
) {
    @WorkerThread
    fun decode(uri: Uri): Result {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            return Result.UnsupportedSource
        }

        val mimeType = try {
            contentResolver.getType(uri)
        } catch (_: SecurityException) {
            return Result.ReadFailed
        } catch (_: RuntimeException) {
            return Result.ReadFailed
        }
        // Some otherwise valid document providers omit the MIME type. If one is supplied, require
        // it to be an image; BitmapFactory remains the final format check when it is absent.
        if (mimeType != null && !mimeType.startsWith("image/", ignoreCase = true)) {
            return Result.UnsupportedImageType
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsRead = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
                true
            } ?: false
        } catch (_: SecurityException) {
            return Result.ReadFailed
        } catch (_: IOException) {
            return Result.ReadFailed
        } catch (_: OutOfMemoryError) {
            return Result.ImageTooLarge
        } catch (_: RuntimeException) {
            return Result.ReadFailed
        }
        if (!boundsRead || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return Result.UnsupportedImageType
        }

        val bitmap = try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return Result.ReadFailed
        } catch (_: SecurityException) {
            return Result.ReadFailed
        } catch (_: IOException) {
            return Result.ReadFailed
        } catch (_: OutOfMemoryError) {
            return Result.ImageTooLarge
        } catch (_: RuntimeException) {
            return Result.ReadFailed
        }
        if (bitmap.width.toLong() * bitmap.height.toLong() > MAX_DECODED_PIXELS) {
            bitmap.recycle()
            return Result.ImageTooLarge
        }

        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(
                pixels,
                0,
                bitmap.width,
                0,
                0,
                bitmap.width,
                bitmap.height,
            )
            decodePixels(bitmap.width, bitmap.height, pixels)
        } catch (_: OutOfMemoryError) {
            Result.ImageTooLarge
        } catch (_: RuntimeException) {
            Result.ReadFailed
        } finally {
            bitmap.recycle()
        }
    }

    sealed interface Result {
        data class Success(val texts: List<String>) : Result {
            init {
                require(texts.isNotEmpty()) { "A successful QR decode must contain text" }
            }
        }

        data object NoQrCode : Result

        data object UnsupportedSource : Result

        data object UnsupportedImageType : Result

        data object ImageTooLarge : Result

        data object ReadFailed : Result
    }

    companion object {
        private const val MAX_DECODE_EDGE = 2_048L
        private const val MAX_DECODED_PIXELS = 4_194_304L

        private val decodeHints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.ALSO_INVERTED to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )

        internal fun decodePixels(
            width: Int,
            height: Int,
            pixels: IntArray,
        ): Result {
            if (width <= 0 || height <= 0 || width.toLong() * height.toLong() != pixels.size.toLong()) {
                return Result.UnsupportedImageType
            }

            val source = RGBLuminanceSource(width, height, pixels)
            val texts = buildList {
                addAll(decodeSource(source))
                // Explicitly retry the inverted luminance source. This keeps inverted QR support
                // reliable even on decoder paths that do not consume ALSO_INVERTED themselves.
                addAll(decodeSource(source.invert()))
            }.map { text -> text.trim() }
                .filter { text -> text.isNotEmpty() }
                .distinct()

            return if (texts.isEmpty()) Result.NoQrCode else Result.Success(texts)
        }

        internal fun calculateInSampleSize(width: Int, height: Int): Int {
            if (width <= 0 || height <= 0) return 1

            var sampleSize = 1
            while (true) {
                val sampledWidth = ceilDivide(width.toLong(), sampleSize.toLong())
                val sampledHeight = ceilDivide(height.toLong(), sampleSize.toLong())
                val withinLimits = sampledWidth <= MAX_DECODE_EDGE &&
                    sampledHeight <= MAX_DECODE_EDGE &&
                    sampledWidth * sampledHeight <= MAX_DECODED_PIXELS
                if (withinLimits) return sampleSize
                sampleSize = sampleSize shl 1
            }
        }

        private fun decodeSource(source: com.google.zxing.LuminanceSource): List<String> = try {
            QRCodeMultiReader()
                .decodeMultiple(BinaryBitmap(HybridBinarizer(source)), decodeHints)
                .mapNotNull { result -> result.text }
        } catch (_: ReaderException) {
            emptyList()
        }

        private fun ceilDivide(value: Long, divisor: Long): Long =
            (value + divisor - 1L) / divisor
    }
}
