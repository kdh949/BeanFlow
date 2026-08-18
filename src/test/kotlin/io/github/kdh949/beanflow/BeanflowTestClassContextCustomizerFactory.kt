package io.github.kdh949.beanflow

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfigurationAttributes
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.ContextCustomizerFactory
import org.springframework.test.context.MergedContextConfiguration
import org.springframework.test.context.TestContext
import org.springframework.test.context.support.AbstractTestExecutionListener

/** Adds test-class identity to the cache key only when the test requires a private database. */
internal class BeanflowTestClassContextCustomizerFactory : ContextCustomizerFactory {
    override fun createContextCustomizer(
        testClass: Class<*>,
        configAttributes: List<ContextConfigurationAttributes>,
    ): ContextCustomizer? {
        if (!AnnotatedElementUtils.hasAnnotation(testClass, SpringBootTest::class.java)) {
            return null
        }
        val shared = AnnotatedElementUtils.hasAnnotation(testClass, BeanflowSharedDatabaseTest::class.java)
        val isolated = AnnotatedElementUtils.hasAnnotation(testClass, BeanflowIsolatedSpringContext::class.java)
        check(shared.xor(isolated)) {
            "${testClass.name} must declare exactly one Spring test isolation marker"
        }
        if (isolated) {
            val reason =
                AnnotatedElementUtils
                    .findMergedAnnotation(testClass, BeanflowIsolatedSpringContext::class.java)
                    ?.reason
                    .orEmpty()
            check(reason.isNotBlank()) {
                "${testClass.name} must explain why its Spring context is isolated"
            }
        }

        return if (isolated) TestClassIdentityContextCustomizer(testClass.name) else null
    }
}

private data class TestClassIdentityContextCustomizer(
    private val testClassName: String,
) : ContextCustomizer {
    override fun customizeContext(
        context: ConfigurableApplicationContext,
        mergedConfig: MergedContextConfiguration,
    ) = Unit
}

/** Resets shared doubles and closes only class-specific contexts before their database is dropped. */
internal class BeanflowDatabaseCleanupTestExecutionListener : AbstractTestExecutionListener() {
    override fun afterTestMethod(testContext: TestContext) {
        if (
            AnnotatedElementUtils.hasAnnotation(testContext.testClass, BeanflowSharedDatabaseTest::class.java) &&
            testContext.hasApplicationContext()
        ) {
            testContext.applicationContext
                .getBeansOfType(ResettableTestDouble::class.java)
                .values
                .forEach(ResettableTestDouble::reset)
        }
    }

    override fun afterTestClass(testContext: TestContext) {
        val isolated = AnnotatedElementUtils.hasAnnotation(testContext.testClass, BeanflowIsolatedSpringContext::class.java)
        if (isolated) {
            val databaseName =
                if (testContext.hasApplicationContext()) {
                    testContext.applicationContext
                        .getBeanProvider(IsolatedTestDatabase::class.java)
                        .ifAvailable
                        ?.databaseName
                } else {
                    null
                }
            testContext.markApplicationContextDirty(DirtiesContext.HierarchyMode.CURRENT_LEVEL)
            check(databaseName == null || !BeanflowPostgresTestRuntime.databaseExists(databaseName)) {
                "Spring test database was not dropped after ${testContext.testClass.name}: $databaseName"
            }
        }
    }
}
