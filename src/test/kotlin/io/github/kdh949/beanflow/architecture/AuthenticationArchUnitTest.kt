package io.github.kdh949.beanflow.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthenticationArchUnitTest {
    private val beanflowClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("io.github.kdh949.beanflow")

    @Test
    fun `domain and application types do not depend on Spring Security`() {
        noClasses()
            .that()
            .resideInAnyPackage("..internal.domain..", "..api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.security..")
            .check(beanflowClasses)

        noClasses()
            .that()
            .haveSimpleNameEndingWith("ApplicationService")
            .or()
            .haveSimpleNameEndingWith("Service")
            .or()
            .haveSimpleNameEndingWith("Coordinator")
            .or()
            .haveSimpleNameEndingWith("Worker")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.security..")
            .check(beanflowClasses)
    }

    @Test
    fun `controllers expose typed actors instead of security or servlet sessions`() {
        val forbiddenTypes =
            setOf(
                "org.springframework.security.oauth2.jwt.Jwt",
                "org.springframework.security.core.Authentication",
                "jakarta.servlet.http.HttpSession",
            )

        val violations =
            beanflowClasses
                .filter { it.simpleName.endsWith("Controller") }
                .flatMap { controller ->
                    controller.methods.flatMap { method ->
                        method.rawParameterTypes
                            .filter { it.name in forbiddenTypes }
                            .map { parameter -> "${controller.name}.${method.name} -> ${parameter.name}" }
                    }
                }

        assertThat(violations).isEmpty()
    }
}
