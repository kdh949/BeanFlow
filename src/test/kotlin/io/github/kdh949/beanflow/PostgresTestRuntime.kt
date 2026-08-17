package io.github.kdh949.beanflow

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One fail-closed PostGIS server for every database-backed test running in this Gradle JVM. */
internal object BeanflowPostgresTestRuntime {
    private val databaseSequence = AtomicInteger()

    private val server: PostgreSQLContainer by lazy {
        PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)
            .withUrlParam("sslmode", "disable")
            .also(PostgreSQLContainer::start)
    }

    fun createDatabase(owner: String): IsolatedTestDatabase {
        val normalizedOwner =
            owner
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .take(24)
                .ifEmpty { "test" }
        val name = "beanflow_${normalizedOwner}_${databaseSequence.incrementAndGet()}"
        return IsolatedTestDatabase(server, name)
    }

    val serverId: String
        get() = server.containerId

    fun databaseExists(databaseName: String): Boolean =
        server.createConnection("").use { connection ->
            connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = ?)").use { statement ->
                statement.setString(1, databaseName)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getBoolean(1)
                }
            }
        }
}

internal class IsolatedTestDatabase(
    private val server: PostgreSQLContainer,
    val databaseName: String,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val additionalDatabases = linkedSetOf<String>()

    val jdbcUrl: String = jdbcUrl(databaseName)
    val username: String
        get() = server.username
    val password: String
        get() = server.password
    val driverClassName: String
        get() = server.driverClassName
    val serverId: String
        get() = server.containerId

    init {
        createDatabase(databaseName)
    }

    @Synchronized
    fun createAdditionalDatabase(
        databaseName: String,
        template: String = "template1",
    ): String {
        createDatabase(databaseName, template)
        additionalDatabases += databaseName
        return jdbcUrl(databaseName)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        val failures = mutableListOf<Throwable>()
        (additionalDatabases.toList().asReversed() + databaseName).forEach { name ->
            try {
                dropDatabase(name)
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun jdbcUrl(databaseName: String): String {
        val withoutQuery = server.jdbcUrl.substringBefore('?')
        val query = server.jdbcUrl.substringAfter('?', "")
        return withoutQuery.substringBeforeLast('/') +
            "/$databaseName" +
            if (query.isEmpty()) "" else "?$query"
    }

    private fun createDatabase(
        databaseName: String,
        template: String? = null,
    ) {
        server.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                val templateClause = template?.let { " TEMPLATE ${quotedIdentifier(it)}" }.orEmpty()
                statement.execute("CREATE DATABASE ${quotedIdentifier(databaseName)}$templateClause")
            }
        }
    }

    private fun dropDatabase(databaseName: String) {
        server.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE ${quotedIdentifier(databaseName)} WITH (FORCE)")
            }
        }
    }

    private fun quotedIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal abstract class IsolatedPostgresSupport {
    protected lateinit var postgres: IsolatedTestDatabase
        private set

    @BeforeAll
    fun createIsolatedPostgresDatabase() {
        postgres = BeanflowPostgresTestRuntime.createDatabase(javaClass.simpleName)
    }

    @AfterAll
    fun dropIsolatedPostgresDatabase() {
        if (::postgres.isInitialized) {
            postgres.close()
        }
    }
}
