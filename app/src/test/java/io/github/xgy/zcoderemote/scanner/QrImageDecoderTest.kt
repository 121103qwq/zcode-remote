package io.github.xgy.zcoderemote.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrImageDecoderTest {
    @Test
    fun `decodes one synthetic remote URL without changing signed bytes`() {
        val url = syntheticRemoteUrl("device-one", "a%2Bb%2Fc%3D", 1_800_000_000_001L)
        val image = qrImage(url)

        val result = QrImageDecoder.decodePixels(image.width, image.height, image.pixels)

        assertEquals(QrImageDecoder.Result.Success(listOf(url)), result)
    }

    @Test
    fun `returns every QR payload in one image`() {
        val first = syntheticRemoteUrl("device-left", "left-hash", 1_800_000_000_002L)
        val second = syntheticRemoteUrl("device-right", "right-hash", 1_800_000_000_003L)
        val image = combineHorizontally(qrImage(first, 320), qrImage(second, 320))

        val result = QrImageDecoder.decodePixels(image.width, image.height, image.pixels)

        assertTrue(result is QrImageDecoder.Result.Success)
        assertEquals(setOf(first, second), (result as QrImageDecoder.Result.Success).texts.toSet())
    }

    @Test
    fun `decodes an inverted QR image`() {
        val url = syntheticRemoteUrl("device-inverted", "inverted-hash", 1_800_000_000_004L)
        val image = qrImage(url).inverted()

        val result = QrImageDecoder.decodePixels(image.width, image.height, image.pixels)

        assertEquals(QrImageDecoder.Result.Success(listOf(url)), result)
    }

    @Test
    fun `returns no QR for a blank image`() {
        val width = 512
        val height = 512
        val pixels = IntArray(width * height) { WHITE }

        val result = QrImageDecoder.decodePixels(width, height, pixels)

        assertEquals(QrImageDecoder.Result.NoQrCode, result)
    }

    @Test
    fun `sampling keeps decoded bitmap within memory limits`() {
        assertEquals(1, QrImageDecoder.calculateInSampleSize(2_048, 2_048))
        assertEquals(2, QrImageDecoder.calculateInSampleSize(4_000, 3_000))
        assertEquals(8, QrImageDecoder.calculateInSampleSize(12_000, 9_000))
    }

    private fun syntheticRemoteUrl(device: String, hash: String, timestamp: Long): String =
        "https://zcode.z.ai/remote/v4?sid=$device&hash=$hash&t=$timestamp&name=test-$device"

    private fun qrImage(text: String, size: Int = 512): PixelImage {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 4,
            ),
        )
        return PixelImage(
            width = matrix.width,
            height = matrix.height,
            pixels = IntArray(matrix.width * matrix.height) { index ->
                val x = index % matrix.width
                val y = index / matrix.width
                if (matrix[x, y]) BLACK else WHITE
            },
        )
    }

    private fun combineHorizontally(left: PixelImage, right: PixelImage): PixelImage {
        val gap = 64
        val width = left.width + gap + right.width
        val height = maxOf(left.height, right.height)
        val pixels = IntArray(width * height) { WHITE }
        copyIntoCanvas(left, pixels, width, 0, 0)
        copyIntoCanvas(right, pixels, width, left.width + gap, 0)
        return PixelImage(width, height, pixels)
    }

    private fun copyIntoCanvas(
        image: PixelImage,
        canvas: IntArray,
        canvasWidth: Int,
        left: Int,
        top: Int,
    ) {
        for (y in 0 until image.height) {
            image.pixels.copyInto(
                destination = canvas,
                destinationOffset = (top + y) * canvasWidth + left,
                startIndex = y * image.width,
                endIndex = (y + 1) * image.width,
            )
        }
    }

    private data class PixelImage(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    ) {
        fun inverted(): PixelImage = copy(
            pixels = IntArray(pixels.size) { index ->
                if (pixels[index] == BLACK) WHITE else BLACK
            },
        )
    }

    companion object {
        private const val BLACK = -0x1000000
        private const val WHITE = -0x1
    }
}
