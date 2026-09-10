package io.github.kdh949.beanflow.schema

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.datasource.DriverManagerDataSource

internal class FlywayMigrationSmokeTest : IsolatedPostgresSupport() {
    @Test
    fun `fresh database migrates from V1 to the current version and validates cleanly`() {
        val flyway =
            Flyway
                .configure()
                .dataSource(
                    DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
                ).locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()

        val packagedVersions =
            PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql")
                .map { MigrationVersion.fromVersion(requireNotNull(it.filename).substringAfter("V").substringBefore("__")) }
        assertThat(packagedVersions).isNotEmpty()
        assertThat(packagedVersions.min()).isEqualTo(MigrationVersion.fromVersion("1"))
        val currentSchemaVersion = packagedVersions.max().toString()
        val firstMigration = flyway.migrate()
        val validation = flyway.validateWithResult()
        val repeatedMigration = flyway.migrate()

        assertThat(firstMigration.success).isTrue()
        assertThat(firstMigration.targetSchemaVersion.toString()).isEqualTo(currentSchemaVersion)
        assertThat(validation.validationSuccessful).isTrue()
        assertThat(validation.invalidMigrations).isEmpty()
        assertThat(repeatedMigration.success).isTrue()
        assertThat(repeatedMigration.migrationsExecuted).isZero()
        assertThat(
            flyway
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo(currentSchemaVersion)
    }
}
