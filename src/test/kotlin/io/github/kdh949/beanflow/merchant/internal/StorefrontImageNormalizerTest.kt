package io.github.kdh949.beanflow.merchant.internal

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

internal class StorefrontImageNormalizerTest {
    private val normalizer = StorefrontImageNormalizer()

    @Test
    fun `JPEG is re-encoded and centrally cropped to a square thumbnail`() {
        val source = image(width = 640, height = 480, format = "jpg")

        val result = normalizer.normalize(source, "image/jpeg")

        assertThat(ImageIO.read(ByteArrayInputStream(result.original))).extracting("width", "height").containsExactly(640, 480)
        assertThat(ImageIO.read(ByteArrayInputStream(result.thumbnail))).extracting("width", "height").containsExactly(512, 512)
        assertThat(result.contentType).isEqualTo("image/jpeg")
        assertThat(result.extension).isEqualTo("jpg")
        assertThat(result.sha256).matches("[0-9a-f]{64}")
    }

    @Test
    fun `PNG keeps its format while producing a square thumbnail`() {
        val result = normalizer.normalize(image(width = 300, height = 500, format = "png"), "image/png")

        assertThat(ImageIO.read(ByteArrayInputStream(result.original))).extracting("width", "height").containsExactly(300, 500)
        assertThat(ImageIO.read(ByteArrayInputStream(result.thumbnail))).extracting("width", "height").containsExactly(512, 512)
        assertThat(result.contentType).isEqualTo("image/png")
        assertThat(result.extension).isEqualTo("png")
    }

    @Test
    fun `EXIF orientation is applied and metadata is removed`() {
        val oriented = withExifOrientation(image(width = 400, height = 300, format = "jpg"), orientation = 6)

        val result = normalizer.normalize(oriented, "image/jpeg")

        assertThat(ImageIO.read(ByteArrayInputStream(result.original))).extracting("width", "height").containsExactly(300, 400)
        assertThat(
            ImageMetadataReader.readMetadata(ByteArrayInputStream(result.original)).getFirstDirectoryOfType(ExifIFD0Directory::class.java),
        ).isNull()
    }

    @Test
    fun `campaign banner is center cropped and re-encoded as a 1200 by 450 JPEG`() {
        val result = normalizer.normalizeCampaignBanner(image(width = 900, height = 900, format = "png"), "image/png")

        assertThat(ImageIO.read(ByteArrayInputStream(result.original))).extracting("width", "height").containsExactly(1200, 450)
        assertThat(result.thumbnail).isEqualTo(result.original)
        assertThat(result.contentType).isEqualTo("image/jpeg")
        assertThat(result.extension).isEqualTo("jpg")
    }

    @Test
    fun `oversized corrupt disguised and invalid resolution inputs are rejected`() {
        assertInvalid(ByteArray(StorefrontImageNormalizer.MAX_UPLOAD_BYTES + 1), "image/jpeg")
        assertInvalid("not-an-image".toByteArray(), "image/jpeg")
        assertInvalid(image(width = 300, height = 300, format = "png"), "image/jpeg")
        assertInvalid(image(width = 255, height = 300, format = "png"), "image/png")
        assertInvalid(image(width = 4097, height = 256, format = "png"), "image/png")
    }

    private fun assertInvalid(
        bytes: ByteArray,
        contentType: String,
    ) {
        assertThatThrownBy { normalizer.normalize(bytes, contentType) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.INVALID_IMAGE)
    }

    private fun image(
        width: Int,
        height: Int,
        format: String,
    ): ByteArray {
        val image = BufferedImage(width, height, if (format == "png") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(240, 120, 40)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, format, output))
            output.toByteArray()
        }
    }

    /** Adds a minimal little-endian EXIF APP1 segment immediately after the JPEG SOI marker. */
    private fun withExifOrientation(
        jpeg: ByteArray,
        orientation: Int,
    ): ByteArray {
        val tiff =
            ByteBuffer
                .allocate(26)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put('I'.code.toByte())
                    put('I'.code.toByte())
                    putShort(42)
                    putInt(8)
                    putShort(1)
                    putShort(0x0112)
                    putShort(3)
                    putInt(1)
                    putShort(orientation.toShort())
                    putShort(0)
                    putInt(0)
                }.array()
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val segmentLength = payload.size + 2
        return byteArrayOf(
            jpeg[0],
            jpeg[1],
            0xff.toByte(),
            0xe1.toByte(),
            (segmentLength ushr 8).toByte(),
            segmentLength.toByte(),
        ) + payload + jpeg.copyOfRange(2, jpeg.size)
    }
}
