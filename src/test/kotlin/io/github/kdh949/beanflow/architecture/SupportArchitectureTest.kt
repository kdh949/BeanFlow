package io.github.kdh949.beanflow.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.github.kdh949.beanflow.support.internal.SupportCaseEntity
import jakarta.persistence.Entity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SupportArchitectureTest {
    private val supportClasses =
        ClassFileImporter().importPackages("io.github.kdh949.beanflow.support")

    @Test
    fun `support controllers do not depend on repositories or JPA entities`() {
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("JpaRepository")
            .check(supportClasses)
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(Entity::class.java)
            .check(supportClasses)
    }

    @Test
    fun `support does not depend on other contexts internal packages`() {
        noClasses()
            .that()
            .resideInAPackage("..support..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..identity.internal..",
                "..merchant.internal..",
                "..operations.internal..",
                "..ordering.internal..",
            ).check(supportClasses)
    }

    @Test
    fun `SupportCase root has no interaction or note collection`() {
        assertThat(SupportCaseEntity::class.java.declaredFields)
            .noneMatch { java.util.Collection::class.java.isAssignableFrom(it.type) }
    }
}
