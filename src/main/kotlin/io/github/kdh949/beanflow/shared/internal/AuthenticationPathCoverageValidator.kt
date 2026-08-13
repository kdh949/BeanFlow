package io.github.kdh949.beanflow.shared.internal

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

@Component
internal class AuthenticationPathCoverageValidator(
    private val registry: AuthenticationPathRegistry,
    @Qualifier("requestMappingHandlerMapping")
    private val handlerMapping: RequestMappingHandlerMapping,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val applicationPaths =
            handlerMapping.handlerMethods
                .filterValues { handler -> handler.beanType.packageName.startsWith("io.github.kdh949.beanflow") }
                .keys
                .flatMap { mapping ->
                    mapping.pathPatternsCondition
                        ?.patterns
                        .orEmpty()
                        .map { it.patternString }
                }.toSet()
        validate(applicationPaths)
    }

    internal fun validate(paths: Collection<String>) {
        check(registry.overlappingPatterns().isEmpty()) {
            "Authentication path patterns overlap across actor chains: ${registry.overlappingPatterns()}"
        }
        val unassigned = paths.filter { registry.classify(it.canonicalPath()) == null }.sorted()
        check(unassigned.isEmpty()) {
            "Controller paths must be assigned to exactly one authentication chain: $unassigned"
        }
    }

    private fun String.canonicalPath(): String = replace(Regex("\\{[^}]+}"), "sample")
}
