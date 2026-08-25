package io.github.kdh949.beanflow

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
@SpringBootTest
@Import(TestcontainersConfiguration::class)
internal class PostgresSpringTestRuntimeTest {
    @Autowired
    private lateinit var database: IsolatedTestDatabase

    @Test
    fun `Spring integration uses the JVM PostGIS server and its own migrated database`() {
        assertThat(database.serverId).isEqualTo(BeanflowPostgresTestRuntime.serverId)
        assertThat(database.databaseName).startsWith("beanflow_spring_")
        assertThat(
            JdbcTemplate(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))
                .queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long::class.java),
        ).isGreaterThan(0)
    }
}
