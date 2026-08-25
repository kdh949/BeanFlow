package io.github.kdh949.beanflow

import org.springframework.transaction.annotation.Transactional

/**
 * Reuses the cached Spring application context and database while Spring's test transaction rolls
 * back every test method.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Transactional
internal annotation class BeanflowSharedDatabaseTest

/** Keeps a class-specific Spring context and database for tests that cross rollback boundaries. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class BeanflowIsolatedSpringContext(
    val reason: String,
)

/** Mutable test doubles in a shared context must deterministically clear method-local state. */
internal fun interface ResettableTestDouble {
    fun reset()
}
