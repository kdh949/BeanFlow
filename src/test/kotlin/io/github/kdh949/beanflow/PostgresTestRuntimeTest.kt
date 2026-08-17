package io.github.kdh949.beanflow

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

internal class PostgresTestRuntimeTest : IsolatedPostgresSupport() {
    @Test
    fun `databases share one server and isolate state until forced drop`() {
        val second = BeanflowPostgresTestRuntime.createDatabase("runtime-second")
        val secondName = second.databaseName
        val firstJdbc = jdbc(postgres)
        val secondJdbc = jdbc(second)

        try {
            assertThat(second.serverId).isEqualTo(postgres.serverId)
            assertThat(second.databaseName).isNotEqualTo(postgres.databaseName)

            firstJdbc.execute("CREATE TABLE runtime_isolation_probe (id integer PRIMARY KEY)")
            assertThat(
                secondJdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = 'runtime_isolation_probe'",
                    Long::class.java,
                ),
            ).isZero()
        } finally {
            second.close()
        }

        assertThat(
            firstJdbc.queryForObject(
                "SELECT count(*) FROM pg_database WHERE datname = ?",
                Long::class.java,
                secondName,
            ),
        ).isZero()
    }

    @Test
    fun `Flyway migration enables PostGIS in an isolated database`() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        assertThat(
            jdbc(postgres).queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'postgis'",
                Long::class.java,
            ),
        ).isOne()
    }

    private fun jdbc(database: IsolatedTestDatabase): JdbcTemplate =
        JdbcTemplate(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))
}
