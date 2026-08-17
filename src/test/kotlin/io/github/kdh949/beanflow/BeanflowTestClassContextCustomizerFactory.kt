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

/**
 * A cached Spring context owns one datasource, so its cache key must include the test class in order
 * for every integration-test class to receive a separate database from [TestcontainersConfiguration].
 */
internal class BeanflowTestClassContextCustomizerFactory : ContextCustomizerFactory {
    override fun createContextCustomizer(
        testClass: Class<*>,
        configAttributes: List<ContextConfigurationAttributes>,
    ): ContextCustomizer? =
        if (AnnotatedElementUtils.hasAnnotation(testClass, SpringBootTest::class.java)) {
            TestClassIdentityContextCustomizer(testClass.name)
        } else {
            null
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

/** Close the class-specific context immediately so its Hikari pool closes before database drop. */
internal class BeanflowDatabaseCleanupTestExecutionListener : AbstractTestExecutionListener() {
    override fun afterTestClass(testContext: TestContext) {
        if (AnnotatedElementUtils.hasAnnotation(testContext.testClass, SpringBootTest::class.java)) {
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
