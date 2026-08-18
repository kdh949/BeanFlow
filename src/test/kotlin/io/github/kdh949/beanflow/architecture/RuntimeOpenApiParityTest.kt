package io.github.kdh949.beanflow.architecture

import io.github.kdh949.beanflow.BeanflowSharedDatabaseTest
import io.github.kdh949.beanflow.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.nio.file.Path
import kotlin.io.path.readLines

@Import(TestcontainersConfiguration::class)
@BeanflowSharedDatabaseTest
@SpringBootTest
internal class RuntimeOpenApiParityTest(
    @Qualifier("requestMappingHandlerMapping")
    private val handlerMapping: RequestMappingHandlerMapping,
) {
    @Test
    fun `runtime OpenAPI operations exactly match public Spring MVC mappings`() {
        val actual = springMvcOperations()
        val documented = OpenApiOperationInventory.load(RUNTIME_OPENAPI)

        assertThat(actual)
            .withFailMessage(
                "Runtime OpenAPI operation parity failed.%nMissing from runtime OpenAPI: %s%n" +
                    "Missing from Spring MVC: %s",
                (actual - documented).sorted(),
                (documented - actual).sorted(),
            ).isEqualTo(documented)
    }

    private fun springMvcOperations(): Set<HttpOperation> =
        handlerMapping.handlerMethods.keys
            .flatMap { mapping ->
                mapping.patternValues.flatMap { path ->
                    if (!normalizePath(path).startsWith(API_PREFIX)) {
                        emptyList()
                    } else {
                        val methods = mapping.methodsCondition.methods
                        check(methods.isNotEmpty()) {
                            "Public mapping must declare an explicit HTTP method: $path"
                        }
                        methods
                            .asSequence()
                            .filterNot { it == RequestMethod.HEAD || it == RequestMethod.OPTIONS }
                            .map { HttpOperation(normalizePath(path), it.name) }
                            .toList()
                    }
                }
            }.toSet()

    private companion object {
        const val API_PREFIX = "/api/v1"
        val RUNTIME_OPENAPI: Path = Path.of("openapi/beanflow-v1-runtime.yaml")
    }
}

private data class HttpOperation(
    val path: String,
    val method: String,
) : Comparable<HttpOperation> {
    override fun compareTo(other: HttpOperation): Int = compareValuesBy(this, other, HttpOperation::path, HttpOperation::method)

    override fun toString(): String = "$method $path"
}

private object OpenApiOperationInventory {
    private val pathLine = Regex("^  (/[^:]*):\\s*$")
    private val methodLine = Regex("^    (get|post|put|patch|delete|head|options):\\s*$")
    private val referenceLine = Regex("^    \\${'$'}ref:\\s*[\\\"]([^\\\"]+)[\\\"]\\s*$")
    private val serverLine = Regex("^  - url:\\s*[\\\"]?([^\\\"#]+)[\\\"]?\\s*$")

    fun load(runtimeFile: Path): Set<HttpOperation> {
        val runtime = parse(runtimeFile)
        check(runtime.serverPrefix == "/api/v1") {
            "Runtime OpenAPI server prefix must be /api/v1: ${runtime.serverPrefix}"
        }
        return runtime.pathItems
            .flatMap { (path, item) ->
                resolveMethods(runtimeFile, item).map { method ->
                    HttpOperation(normalizePath("${runtime.serverPrefix}/$path"), method.uppercase())
                }
            }.toSet()
    }

    private fun resolveMethods(
        ownerFile: Path,
        item: OpenApiPathItem,
    ): Set<String> {
        if (item.reference == null) return item.methods

        check(item.methods.isEmpty()) {
            "A runtime OpenAPI path item cannot mix operation methods with a path-level reference: ${item.reference}"
        }
        val reference = checkNotNull(item.reference)
        val (relativeFile, fragment) =
            reference.split('#', limit = 2).let {
                check(it.size == 2) { "OpenAPI path reference must contain a file and fragment: $reference" }
                it[0] to it[1]
            }
        check(fragment.startsWith("/paths/")) {
            "Only OpenAPI path-item references are supported in the runtime inventory: $reference"
        }
        val referencedPath = decodeJsonPointerToken(fragment.removePrefix("/paths/"))
        val referencedFile = ownerFile.parent.resolve(relativeFile).normalize()
        val referencedDocument = parse(referencedFile)
        val referencedItem =
            checkNotNull(referencedDocument.pathItems[referencedPath]) {
                "OpenAPI path reference does not exist: $reference"
            }
        check(referencedItem.reference == null) {
            "Nested OpenAPI path-item references are not supported: $reference"
        }
        return referencedItem.methods
    }

    private fun parse(file: Path): OpenApiDocument {
        val pathItems = linkedMapOf<String, OpenApiPathItemBuilder>()
        var serverPrefix: String? = null
        var currentPath: String? = null
        var inPaths = false

        file.readLines().forEachIndexed { index, line ->
            serverLine.matchEntire(line)?.let { serverPrefix = it.groupValues[1].trimEnd('/') }
            when {
                line == "paths:" -> {
                    inPaths = true
                    currentPath = null
                }

                inPaths && line.isNotEmpty() && !line.startsWith(' ') -> {
                    inPaths = false
                    currentPath = null
                }

                inPaths -> {
                    pathLine.matchEntire(line)?.let { match ->
                        currentPath = match.groupValues[1]
                        pathItems.getOrPut(checkNotNull(currentPath)) { OpenApiPathItemBuilder() }
                    }
                    methodLine.matchEntire(line)?.let { match ->
                        val path =
                            checkNotNull(currentPath) {
                                "OpenAPI operation has no path at $file:${index + 1}"
                            }
                        pathItems.getValue(path).methods += match.groupValues[1]
                    }
                    referenceLine.matchEntire(line)?.let { match ->
                        val path =
                            checkNotNull(currentPath) {
                                "OpenAPI path reference has no path at $file:${index + 1}"
                            }
                        val builder = pathItems.getValue(path)
                        check(builder.reference == null) {
                            "OpenAPI path has more than one reference at $file:${index + 1}"
                        }
                        builder.reference = match.groupValues[1]
                    }
                }
            }
        }

        check(pathItems.isNotEmpty()) { "OpenAPI document has no paths: $file" }
        pathItems.forEach { (path, item) ->
            check(item.methods.isNotEmpty() || item.reference != null) {
                "OpenAPI path item has neither operations nor a reference: $path"
            }
        }
        return OpenApiDocument(
            serverPrefix = checkNotNull(serverPrefix) { "OpenAPI document has no server URL: $file" },
            pathItems = pathItems.mapValues { (_, value) -> value.build() },
        )
    }

    private fun decodeJsonPointerToken(value: String): String = value.replace("~1", "/").replace("~0", "~")
}

private data class OpenApiDocument(
    val serverPrefix: String,
    val pathItems: Map<String, OpenApiPathItem>,
)

private data class OpenApiPathItem(
    val methods: Set<String>,
    val reference: String?,
)

private class OpenApiPathItemBuilder {
    val methods: MutableSet<String> = linkedSetOf()
    var reference: String? = null

    fun build(): OpenApiPathItem = OpenApiPathItem(methods.toSet(), reference)
}

private val pathVariablePattern = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)(?::[^{}]+)?}")
private val repeatedSlashPattern = Regex("/{2,}")

private fun normalizePath(value: String): String {
    val withLeadingSlash = if (value.startsWith('/')) value else "/$value"
    val normalizedVariables =
        pathVariablePattern.replace(withLeadingSlash) { match -> "{${match.groupValues[1]}}" }
    val normalizedSlashes = repeatedSlashPattern.replace(normalizedVariables, "/")
    return if (normalizedSlashes.length > 1) normalizedSlashes.trimEnd('/') else normalizedSlashes
}
