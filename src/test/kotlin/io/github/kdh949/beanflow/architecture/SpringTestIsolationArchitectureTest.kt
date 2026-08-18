package io.github.kdh949.beanflow.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.BeanflowSharedDatabaseTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

internal class SpringTestIsolationArchitectureTest {
    private val springTests =
        ClassFileImporter()
            .withImportOption(ImportOption.OnlyIncludeTests())
            .importPackages("io.github.kdh949.beanflow")
            .filter { it.isAnnotatedWith(SpringBootTest::class.java) }
            .filterNot { it.name.startsWith("${CONTEXT_CUSTOMIZER_FACTORY_TEST_NAME}$") }

    @Test
    fun `every Spring Boot test declares exactly one isolation mode`() {
        val violations =
            springTests.mapNotNull { javaClass ->
                val reflection = Class.forName(javaClass.name, false, javaClass.javaClass.classLoader)
                val shared = reflection.isAnnotationPresent(BeanflowSharedDatabaseTest::class.java)
                val isolated = reflection.isAnnotationPresent(BeanflowIsolatedSpringContext::class.java)
                when {
                    !shared.xor(isolated) -> {
                        "${javaClass.name}: declare exactly one isolation marker"
                    }

                    isolated &&
                        reflection.getAnnotation(BeanflowIsolatedSpringContext::class.java).reason.isBlank() -> {
                        "${javaClass.name}: isolated reason must not be blank"
                    }

                    else -> {
                        null
                    }
                }
            }

        assertThat(violations).isEmpty()
    }

    private companion object {
        const val CONTEXT_CUSTOMIZER_FACTORY_TEST_NAME =
            "io.github.kdh949.beanflow.BeanflowTestClassContextCustomizerFactoryTest"
    }
}
