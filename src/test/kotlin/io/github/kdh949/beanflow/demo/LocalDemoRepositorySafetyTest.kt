package io.github.kdh949.beanflow.demo

import com.drew.imaging.FileType
import com.drew.imaging.FileTypeDetector
import com.drew.imaging.ImageMetadataReader
import com.drew.imaging.ImageProcessingException
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.webp.WebpDirectory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.readText

/**
 * Repository-level guarantees for the demo: no secret is tracked, and the smoke flow only calls
 * operations the runtime OpenAPI actually declares.
 *
 * Both are checked against the repository itself rather than against a description of it, because
 * both are the kind of promise that quietly stops being true.
 */
internal class LocalDemoRepositorySafetyTest {
    @Test
    fun `no tracked file carries a private key, a JWT or run-time demo key material`() {
        val offenders =
            trackedFiles().mapNotNull { path ->
                val text = readIfText(path) ?: return@mapNotNull null
                val reasons =
                    SECRET_PATTERNS.filter { (_, pattern) -> pattern.containsMatchIn(text) }.map { it.first }
                if (reasons.isEmpty()) null else "$path -> $reasons"
            }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `new image filenames are recognized by format without a path allowlist`(
        @TempDir directory: Path,
    ) {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        for (format in listOf("png", "jpeg")) {
            val output = ByteArrayOutputStream()
            check(ImageIO.write(image, format, output))
            val path = directory.resolve("new-screenshot.${format.uppercase()}")
            path.toFile().writeBytes(output.toByteArray())
            assertThat(readIfText(path)).isNull()
        }
        val webp = directory.resolve("new-screenshot.webp")
        webp.toFile().writeBytes(Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"))
        assertThat(readIfText(webp)).isNull()
    }

    @Test
    fun `image extensions do not hide text containing a secret`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("not-an-image.png")
        val text = "BEANFLOW_DEMO_CURSOR_SECRET=" + "x".repeat(16)
        path.toFile().writeText(text)

        assertThat(readIfText(path)).isEqualTo(text)
        assertThat(SECRET_PATTERNS.any { (_, pattern) -> pattern.containsMatchIn(readIfText(path)!!) }).isTrue()
    }

    @Test
    fun `truncated image headers remain unexpected binaries`(
        @TempDir directory: Path,
    ) {
        val headers =
            mapOf(
                "png" to byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0),
                "jpg" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 2),
                "webp" to "RIFF\u0004\u0000\u0000\u0000WEBP".toByteArray(),
            )
        for ((extension, bytes) in headers) {
            val path = directory.resolve("truncated.$extension")
            path.toFile().writeBytes(bytes)
            assertThatIllegalStateException()
                .isThrownBy { readIfText(path) }
                .withMessageContaining(path.toString())
        }
    }

    @Test
    fun `image payloads require an image extension instead of a source path`(
        @TempDir directory: Path,
    ) {
        val output = ByteArrayOutputStream()
        check(ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output))
        val source = directory.resolve("source.kt")
        source.toFile().writeBytes(output.toByteArray())
        assertThatIllegalStateException()
            .isThrownBy { readIfText(source) }
            .withMessageContaining(source.toString())

        val image = directory.resolve("legacy-screenshot.jpg")
        image.toFile().writeBytes(output.toByteArray())
        assertThat(readIfText(image)).isNull()
    }

    @Test
    fun `the run-time demo directory is never tracked`() {
        assertThat(trackedFiles().map { it.toString() })
            .noneMatch { it.startsWith(".demo-runtime") }
    }

    @Test
    fun `every operation the smoke flow calls exists in the runtime OpenAPI`() {
        val declared = runtimeOperations()
        assertThat(declared).isNotEmpty()

        val called = smokeCalls()
        // A smoke flow that exercised nothing would pass an "only declared operations" check
        // trivially, so the call list itself has to be non-trivial.
        assertThat(called).hasSizeGreaterThanOrEqualTo(10)

        val undeclared = called.filterNot { call -> declared.any { it.matches(call) } }
        assertThat(undeclared).isEmpty()

        // The matcher must be able to say no, otherwise the assertion above proves nothing.
        assertThat(declared).noneMatch { it.matches(SmokeCall("DELETE", "/stores/{}/menus")) }
        assertThat(declared).noneMatch { it.matches(SmokeCall("GET", "/internal/debug")) }
        assertThat(declared).anyMatch { it.matches(SmokeCall("GET", "/stores/{}/menus")) }
    }

    @Test
    fun `every smoke API request uses the call helper`() {
        val curlLines =
            Path
                .of("scripts/demo/smoke.sh")
                .readText()
                .lineSequence()
                .filter { it.contains("\$(curl") }
                .toList()

        // `call` is the only place an API request belongs. Keeping it singular makes the OpenAPI
        // inventory assertion above cover every smoke request rather than a misleading subset.
        assertThat(curlLines).hasSize(1)
        assertThat(curlLines.single()).contains("local status")
    }

    @Test
    fun `full smoke authenticates merchant operations with a browser session instead of a legacy JWT`() {
        val text = Path.of("scripts/demo/smoke.sh").readText()

        assertThat(text)
            .contains("/auth/merchant/sessions", "BEANFLOW_MERCHANT_SESSION", "/auth/merchant/password-changes")
            .doesNotContain("STORE_OWNER_TOKEN", "OTHER_STORE_OWNER_TOKEN")
    }

    /** `call <name> <status> <METHOD> "<path>" ...` lines in the smoke script. */
    private fun smokeCalls(): List<SmokeCall> {
        val text = Path.of("scripts/demo/smoke.sh").readText()
        return CALL_PATTERN
            .findAll(text)
            .map { match ->
                val method = match.groupValues[1]
                val path =
                    match.groupValues[2]
                        .substringBefore('?')
                        // Shell interpolations are the path parameters.
                        .replace(SHELL_INTERPOLATION, "{}")
                SmokeCall(method, path)
            }.toList()
    }

    private fun runtimeOperations(): List<DeclaredOperation> {
        val target = methodsByPath(Path.of("openapi/beanflow-v1.yaml").readText())
        val operations = mutableListOf<DeclaredOperation>()
        var path: String? = null
        Path
            .of("openapi/beanflow-v1-runtime.yaml")
            .readText()
            .lineSequence()
            .forEach { line ->
                PATH_PATTERN.find(line)?.let { path = it.groupValues[1] }
                METHOD_PATTERN.find(line)?.let { match ->
                    path?.let { operations.add(DeclaredOperation(match.groupValues[1].uppercase(), it)) }
                }
                // A `$ref` path item lives in the target document. Its methods are resolved there
                // rather than assumed, so this check cannot silently accept an undeclared method.
                if (line.trim().startsWith("\$ref:") && line.contains("#/paths/")) {
                    val referenced = line.substringAfter("#/paths/").trim('"', ' ').replace("~1", "/")
                    target[referenced]?.forEach { operations.add(DeclaredOperation(it, referenced)) }
                }
            }
        return operations
    }

    private fun methodsByPath(document: String): Map<String, List<String>> {
        val methods = mutableMapOf<String, MutableList<String>>()
        var path: String? = null
        document.lineSequence().forEach { line ->
            PATH_PATTERN.find(line)?.let { path = it.groupValues[1] }
            METHOD_PATTERN.find(line)?.let { match ->
                path?.let { methods.getOrPut(it) { mutableListOf() }.add(match.groupValues[1].uppercase()) }
            }
        }
        return methods
    }

    private fun trackedFiles(): List<Path> {
        val process =
            ProcessBuilder("git", "ls-files", "-z")
                .directory(Path.of(".").toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "git ls-files did not finish" }
        check(process.exitValue() == 0) { "git ls-files failed: $output" }
        return output.split('\u0000').filter { it.isNotBlank() }.map(Path::of)
    }

    private fun readIfText(path: Path): String? {
        val file = path.toFile()
        if (!file.isFile || file.length() > MAX_SCANNED_BYTES) return null
        val bytes = file.readBytes()
        if (isImage(path, bytes)) return null
        if (bytes.any { it == 0.toByte() }) {
            check(path == Path.of("gradle/wrapper/gradle-wrapper.jar")) { "Tracked text file contains a NUL byte: $path" }
            return null
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun isImage(
        path: Path,
        bytes: ByteArray,
    ): Boolean {
        if (path.fileName
                .toString()
                .substringAfterLast('.')
                .lowercase() !in IMAGE_EXTENSIONS
        ) {
            return false
        }
        return try {
            val actualType = FileTypeDetector.detectFileType(ByteArrayInputStream(bytes))
            if (actualType !in IMAGE_TYPES) return false
            val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes), bytes.size.toLong(), actualType)
            if (metadata.directories.any { it.hasErrors() }) return false
            val (directory, widthTag, heightTag) =
                when (actualType) {
                    FileType.Png -> {
                        Triple(
                            metadata.getFirstDirectoryOfType(PngDirectory::class.java),
                            PngDirectory.TAG_IMAGE_WIDTH,
                            PngDirectory.TAG_IMAGE_HEIGHT,
                        )
                    }

                    FileType.Jpeg -> {
                        Triple(
                            metadata.getFirstDirectoryOfType(JpegDirectory::class.java),
                            JpegDirectory.TAG_IMAGE_WIDTH,
                            JpegDirectory.TAG_IMAGE_HEIGHT,
                        )
                    }

                    FileType.WebP -> {
                        Triple(
                            metadata.getFirstDirectoryOfType(WebpDirectory::class.java),
                            WebpDirectory.TAG_IMAGE_WIDTH,
                            WebpDirectory.TAG_IMAGE_HEIGHT,
                        )
                    }

                    else -> {
                        return false
                    }
                }
            (directory?.getInteger(widthTag) ?: 0) > 0 && (directory?.getInteger(heightTag) ?: 0) > 0
        } catch (_: ImageProcessingException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    private data class SmokeCall(
        val method: String,
        val path: String,
    )

    private data class DeclaredOperation(
        val method: String,
        val path: String,
    ) {
        fun matches(call: SmokeCall): Boolean = method == call.method && call.path == path.replace(OPENAPI_PARAMETER, "{}")
    }

    private companion object {
        const val MAX_SCANNED_BYTES = 2L * 1024 * 1024

        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
        val IMAGE_TYPES = setOf(FileType.Png, FileType.Jpeg, FileType.WebP)

        val CALL_PATTERN =
            Regex("""^\s*call\s+"[^"]*"\s+\d{3}\s+(GET|POST|PUT|PATCH|DELETE)\s+"([^"]+)"""", RegexOption.MULTILINE)
        val SHELL_INTERPOLATION = Regex("""\$\{[^}]+}""")
        val OPENAPI_PARAMETER = Regex("""\{[^}]+}""")
        val PATH_PATTERN = Regex("""^ {2}(/\S*):\s*$""")
        val METHOD_PATTERN = Regex("""^ {4}(get|post|put|patch|delete):\s*$""")

        /**
         * Deliberately narrow. The public signed-cursor test vector in `src/test/resources` is a
         * documented, non-deployment value, so generic base64 is not a signal; a private key, a JWT
         * or a demo run-time secret assignment is.
         */
        val SECRET_PATTERNS =
            listOf(
                "private key block" to Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
                "JWT" to Regex("""eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"""),
                // A literal value only. The identity server's own source writes this line at run
                // time from an interpolation, which is the mechanism, not a committed secret.
                "demo cursor secret value" to Regex("""BEANFLOW_DEMO_CURSOR_SECRET=[A-Za-z0-9_-]{16,}"""),
                "demo token value" to Regex("""(CUSTOMER|STORE_OWNER|OTHER_STORE_OWNER)_TOKEN=[A-Za-z0-9_.-]{20,}"""),
            )
    }
}
