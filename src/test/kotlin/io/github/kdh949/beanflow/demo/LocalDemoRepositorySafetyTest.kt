package io.github.kdh949.beanflow.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
        if (bytes.any { it == 0.toByte() }) {
            check(path.toString() in ALLOWED_BINARY_FILES) { "Tracked text file contains a NUL byte: $path" }
            return null
        }
        return String(bytes, Charsets.UTF_8)
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

        // Every intentionally tracked binary is explicit. Any other NUL-bearing tracked path is a
        // source/configuration mistake rather than a reason to skip the secret scan.
        val ALLOWED_BINARY_FILES =
            setOf(
                "frontend/public/brand/logo-full.png",
                "frontend/public/brand/logo-mark.png",
                "frontend/public/brand/logo-wordmark.png",
                "gradle/wrapper/gradle-wrapper.jar",
            )

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
