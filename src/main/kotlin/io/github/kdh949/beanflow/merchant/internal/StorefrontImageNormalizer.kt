package io.github.kdh949.beanflow.merchant.internal

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

internal data class NormalizedStorefrontImage(
    val original: ByteArray,
    val thumbnail: ByteArray,
    val contentType: String,
    val extension: String,
    val sha256: String,
)

/** Validates and re-encodes untrusted uploads before any object-storage call is made. */
@Component
internal class StorefrontImageNormalizer {
    fun normalize(
        bytes: ByteArray,
        declaredContentType: String?,
    ): NormalizedStorefrontImage {
        if (bytes.isEmpty() || bytes.size > MAX_UPLOAD_BYTES) invalid()
        val format = ImageFormat.detect(bytes) ?: invalid()
        if (declaredContentType?.trim()?.lowercase() != format.contentType) invalid()

        val decoded = decodeWithinLimits(bytes, format)
        val orientation = exifOrientation(bytes)
        val oriented = orient(decoded, orientation)
        val original = encode(oriented, format)
        val thumbnail = encode(squareThumbnail(oriented), format)
        return NormalizedStorefrontImage(
            original = original,
            thumbnail = thumbnail,
            contentType = format.contentType,
            extension = format.extension,
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)),
        )
    }

    fun normalizeCampaignBanner(
        bytes: ByteArray,
        declaredContentType: String?,
    ): NormalizedStorefrontImage {
        if (bytes.isEmpty() || bytes.size > MAX_UPLOAD_BYTES) invalid()
        val format = ImageFormat.detect(bytes) ?: invalid()
        if (declaredContentType?.trim()?.lowercase() != format.contentType) invalid()
        val oriented = orient(decodeWithinLimits(bytes, format), exifOrientation(bytes))
        val banner = wideBanner(oriented)
        val encoded = encode(banner, ImageFormat.JPEG)
        return NormalizedStorefrontImage(
            original = encoded,
            thumbnail = encoded,
            contentType = ImageFormat.JPEG.contentType,
            extension = ImageFormat.JPEG.extension,
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)),
        )
    }

    private fun decodeWithinLimits(
        bytes: ByteArray,
        expectedFormat: ImageFormat,
    ): BufferedImage =
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) invalid()
                val reader = readers.next()
                try {
                    reader.input = input
                    val actualFormat = ImageFormat.fromReaderName(reader.formatName) ?: invalid()
                    if (actualFormat != expectedFormat) invalid()
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width !in MIN_DIMENSION..MAX_DIMENSION || height !in MIN_DIMENSION..MAX_DIMENSION) invalid()
                    if (width.toLong() * height.toLong() > MAX_PIXELS) invalid()
                    reader.read(0) ?: invalid()
                } finally {
                    reader.dispose()
                }
            }
        } catch (failure: DomainFailure) {
            throw failure
        } catch (_: RuntimeException) {
            invalid()
        } catch (_: Exception) {
            invalid()
        }

    private fun exifOrientation(bytes: ByteArray): Int =
        try {
            ImageMetadataReader
                .readMetadata(ByteArrayInputStream(bytes))
                .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
                ?.takeIf { it in 1..8 }
                ?: 1
        } catch (_: Exception) {
            invalid()
        }

    private fun orient(
        source: BufferedImage,
        orientation: Int,
    ): BufferedImage {
        if (orientation == 1) return source
        val width = source.width
        val height = source.height
        val swapsAxes = orientation in setOf(5, 6, 7, 8)
        val target = BufferedImage(if (swapsAxes) height else width, if (swapsAxes) width else height, imageType(source))
        val transform =
            when (orientation) {
                2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, width.toDouble(), 0.0)
                3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, width.toDouble(), height.toDouble())
                4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, height.toDouble())
                5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
                6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, height.toDouble(), 0.0)
                7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, height.toDouble(), width.toDouble())
                8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, width.toDouble())
                else -> AffineTransform()
            }
        target.createGraphics().use { graphics ->
            graphics.drawImage(source, transform, null)
        }
        return target
    }

    private fun squareThumbnail(source: BufferedImage): BufferedImage {
        val side = minOf(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        val target = BufferedImage(THUMBNAIL_SIZE, THUMBNAIL_SIZE, imageType(source))
        target.createGraphics().use { graphics ->
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE, x, y, x + side, y + side, null)
        }
        return target
    }

    private fun wideBanner(source: BufferedImage): BufferedImage {
        val targetRatio = BANNER_WIDTH.toDouble() / BANNER_HEIGHT.toDouble()
        val sourceRatio = source.width.toDouble() / source.height.toDouble()
        val cropWidth = if (sourceRatio > targetRatio) (source.height * targetRatio).toInt() else source.width
        val cropHeight = if (sourceRatio > targetRatio) source.height else (source.width / targetRatio).toInt()
        val x = (source.width - cropWidth) / 2
        val y = (source.height - cropHeight) / 2
        val target = BufferedImage(BANNER_WIDTH, BANNER_HEIGHT, BufferedImage.TYPE_INT_RGB)
        target.createGraphics().use { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, BANNER_WIDTH, BANNER_HEIGHT)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, BANNER_WIDTH, BANNER_HEIGHT, x, y, x + cropWidth, y + cropHeight, null)
        }
        return target
    }

    private fun encode(
        source: BufferedImage,
        format: ImageFormat,
    ): ByteArray {
        val image =
            if (format == ImageFormat.JPEG && source.colorModel.hasAlpha()) {
                BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB).also { target ->
                    target.createGraphics().use { graphics ->
                        graphics.color = Color.WHITE
                        graphics.fillRect(0, 0, target.width, target.height)
                        graphics.drawImage(source, 0, 0, null)
                    }
                }
            } else {
                source
            }
        val writer = ImageIO.getImageWritersByFormatName(format.writerName).asSequence().firstOrNull() ?: invalid()
        return try {
            ByteArrayOutputStream().use { output ->
                ImageIO.createImageOutputStream(output).use { imageOutput ->
                    writer.output = imageOutput
                    val parameters = writer.defaultWriteParam
                    if (format == ImageFormat.JPEG && parameters.canWriteCompressed()) {
                        parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                        parameters.compressionQuality = JPEG_QUALITY
                    }
                    writer.write(null, IIOImage(image, null, null), parameters)
                }
                output.toByteArray()
            }
        } catch (failure: DomainFailure) {
            throw failure
        } catch (_: Exception) {
            invalid()
        } finally {
            writer.dispose()
        }
    }

    private fun imageType(source: BufferedImage): Int =
        if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_IMAGE, "Image validation failed")

    internal companion object {
        const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
        const val MIN_DIMENSION = 256
        const val MAX_DIMENSION = 4096
        const val MAX_PIXELS = 16_777_216L
        const val THUMBNAIL_SIZE = 512
        const val BANNER_WIDTH = 1200
        const val BANNER_HEIGHT = 450
        const val JPEG_QUALITY = 0.9f
    }

    private enum class ImageFormat(
        val contentType: String,
        val extension: String,
        val writerName: String,
    ) {
        JPEG("image/jpeg", "jpg", "jpeg"),
        PNG("image/png", "png", "png"),
        ;

        companion object {
            fun detect(bytes: ByteArray): ImageFormat? =
                when {
                    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> JPEG
                    bytes.size >= PNG_SIGNATURE.size && bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) -> PNG
                    else -> null
                }

            fun fromReaderName(name: String): ImageFormat? =
                when (name.lowercase()) {
                    "jpeg", "jpg" -> JPEG
                    "png" -> PNG
                    else -> null
                }

            private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        }
    }
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}
