package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.math.roundToLong
import kotlin.system.measureNanoTime

/** Fixed-fixture PostgreSQL evidence for the Store commerce-root lock introduced by ADR-118. */
internal class MerchantCatalogLockPerformanceEvidenceTest : IsolatedPostgresSupport() {
    companion object {
        const val WARMUP_ITERATIONS = 20
        const val MEASURED_ITERATIONS = 200
        const val CONTROLLED_HOLD_MILLIS = 250L
    }

    private val dataSource: DataSource by lazy {
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }
    private val jdbc by lazy { JdbcTemplate(dataSource) }

    @BeforeEach
    fun migrateFromCleanDatabase() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `fixed Store fixture records shared lock overhead compatibility and exclusive wait`() {
        val storeId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            storeId,
        )

        measureTransactions(storeId, forShare = false, iterations = WARMUP_ITERATIONS)
        measureTransactions(storeId, forShare = true, iterations = WARMUP_ITERATIONS)
        val baselineNanos = measureTransactions(storeId, forShare = false, iterations = MEASURED_ITERATIONS)
        val sharedNanos = measureTransactions(storeId, forShare = true, iterations = MEASURED_ITERATIONS)

        val compatibleSharedNanos = measureCompatibleSharedAcquisition(storeId)
        val exclusiveWait = measureExclusiveWait(storeId)

        assertThat(baselineNanos).isPositive()
        assertThat(sharedNanos).isPositive()
        assertThat(compatibleSharedNanos).isLessThan(TimeUnit.SECONDS.toNanos(1))
        assertThat(exclusiveWait.observedDatabaseLockWait).isTrue()
        assertThat(exclusiveWait.elapsedNanos).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(CONTROLLED_HOLD_MILLIS))

        println(
            "MERCHANT_CATALOG_LOCK_THROUGHPUT_FIXTURE iterations=$MEASURED_ITERATIONS " +
                "baselineElapsedMs=${milliseconds(baselineNanos)} baselineOpsPerSecond=${opsPerSecond(baselineNanos)} " +
                "sharedElapsedMs=${milliseconds(sharedNanos)} sharedOpsPerSecond=${opsPerSecond(sharedNanos)}",
        )
        println(
            "MERCHANT_CATALOG_LOCK_WAIT_FIXTURE controlledHoldMs=$CONTROLLED_HOLD_MILLIS " +
                "compatibleSharedAcquireMs=${milliseconds(compatibleSharedNanos)} " +
                "exclusiveWaitMs=${milliseconds(exclusiveWait.elapsedNanos)} waitEventType=Lock blockingPidObserved=true",
        )
    }

    private fun measureTransactions(
        storeId: UUID,
        forShare: Boolean,
        iterations: Int,
    ): Long =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val suffix = if (forShare) " FOR SHARE" else ""
            measureNanoTime {
                repeat(iterations) {
                    connection.prepareStatement("SELECT id FROM merchant_store WHERE id = ?$suffix").use { statement ->
                        statement.setObject(1, storeId)
                        statement.executeQuery().use { rows ->
                            check(rows.next())
                        }
                    }
                    connection.commit()
                }
            }
        }

    private fun measureCompatibleSharedAcquisition(storeId: UUID): Long =
        dataSource.connection.use { holder ->
            dataSource.connection.use { peer ->
                holder.autoCommit = false
                peer.autoCommit = false
                lockStore(holder, storeId, "FOR SHARE")
                try {
                    measureNanoTime { lockStore(peer, storeId, "FOR SHARE") }
                } finally {
                    peer.rollback()
                    holder.rollback()
                }
            }
        }

    private fun measureExclusiveWait(storeId: UUID): ExclusiveWaitEvidence {
        val holder = dataSource.connection
        val writer = dataSource.connection
        val observer = dataSource.connection
        val executor = Executors.newSingleThreadExecutor()
        holder.autoCommit = false
        writer.autoCommit = false
        try {
            lockStore(holder, storeId, "FOR SHARE")
            val writerPid = backendPid(writer)
            val startedAt = System.nanoTime()
            val writerResult =
                executor.submit(
                    Callable {
                        lockStore(writer, storeId, "FOR UPDATE")
                        System.nanoTime() - startedAt
                    },
                )

            val waitObserved = waitForDatabaseLock(observer, writerPid)
            Thread.sleep(CONTROLLED_HOLD_MILLIS)
            holder.commit()
            val elapsedNanos = writerResult.get(5, TimeUnit.SECONDS)
            writer.rollback()
            return ExclusiveWaitEvidence(elapsedNanos, waitObserved)
        } finally {
            runCatching { holder.rollback() }
            runCatching { writer.rollback() }
            executor.shutdownNow()
            observer.close()
            writer.close()
            holder.close()
        }
    }

    private fun lockStore(
        connection: Connection,
        storeId: UUID,
        mode: String,
    ) {
        connection.prepareStatement("SELECT id FROM merchant_store WHERE id = ? $mode").use { statement ->
            statement.setObject(1, storeId)
            statement.executeQuery().use { rows -> check(rows.next()) }
        }
    }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    private fun waitForDatabaseLock(
        observer: Connection,
        writerPid: Int,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            observer
                .prepareStatement(
                    """
                    SELECT wait_event_type = 'Lock' AND cardinality(pg_blocking_pids(pid)) > 0
                      FROM pg_stat_activity
                     WHERE pid = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, writerPid)
                    statement.executeQuery().use { rows ->
                        if (rows.next() && rows.getBoolean(1)) return true
                    }
                }
            Thread.sleep(10)
        }
        return false
    }

    private fun milliseconds(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanos)

    private fun opsPerSecond(nanos: Long): Long = (MEASURED_ITERATIONS.toDouble() * TimeUnit.SECONDS.toNanos(1) / nanos).roundToLong()

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()

    private data class ExclusiveWaitEvidence(
        val elapsedNanos: Long,
        val observedDatabaseLockWait: Boolean,
    )
}
