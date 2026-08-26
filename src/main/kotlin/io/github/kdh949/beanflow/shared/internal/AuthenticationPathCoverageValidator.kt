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
        val applicationRoutes =
            handlerMapping.handlerMethods
                .filterValues { handler -> handler.beanType.packageName.startsWith("io.github.kdh949.beanflow") }
                .keys
                .flatMap { mapping ->
                    val methods: List<String?> =
                        if (mapping.methodsCondition.methods.isEmpty()) {
                            listOf(null)
                        } else {
                            mapping.methodsCondition.methods.map { it.name }
                        }
                    mapping.pathPatternsCondition?.patterns.orEmpty().flatMap { pattern ->
                        methods.map { method -> ControllerRoute(pattern.patternString, method) }
                    }
                }.toSet()
        validateRoutes(applicationRoutes)
    }

    internal fun validate(paths: Collection<String>) =
        validateRoutes(paths.map { ControllerRoute(it, null) })

    private fun validateRoutes(routes: Collection<ControllerRoute>) {
        check(registry.overlappingPatterns().isEmpty()) {
            "Authentication path patterns overlap across actor chains: ${registry.overlappingPatterns()}"
        }
        val unassigned =
            routes.filterNot { registry.hasRegistration(it.path.canonicalPath(), it.method) }
                .map { route -> route.method?.let { "$it ${route.path}" } ?: route.path }
                .sorted()
        check(unassigned.isEmpty()) {
            "Controller paths must be assigned to exactly one authentication chain: $unassigned"
        }
    }

    private fun String.canonicalPath(): String = replace(Regex("\\{[^}]+}"), "sample")

    private data class ControllerRoute(val path: String, val method: String?)
}
