package io.github.kdh949.beanflow.identity.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal enum class LoginAttemptScope(
    val limit: Int,
) {
    IP(30),
    LOGIN_ID(5),
}

internal enum class LoginAttemptActorType {
    CUSTOMER,
    MERCHANT,
}

internal data class LoginAttemptRow(
    val id: UUID,
    val scope: LoginAttemptScope,
    val scopeHmac: String,
    val windowStart: Instant,
    val failureCount: Int,
    val blockedUntil: Instant?,
    val newlyInserted: Boolean = false,
)

internal data class LoginAttemptLock(
    val rows: Map<LoginAttemptScope, LoginAttemptRow>,
)

internal data class LoginAttemptOutcome(
    val loginId: LoginAttemptRow,
    val ip: LoginAttemptRow,
) {
    fun rateLimited(now: Instant): Boolean = ip.blockedUntil?.let(now::isBefore) == true

    fun authenticationBlocked(now: Instant): Boolean = loginId.blockedUntil?.let(now::isBefore) == true
}

@Repository
internal class LoginAttemptRepository(
    private val jdbc: JdbcTemplate,
) {
    fun lockExisting(
        actorType: LoginAttemptActorType,
        loginIdHmac: String,
        ipHmac: String,
    ): LoginAttemptLock = LoginAttemptLock(lockRows(actorType, loginIdHmac, ipHmac, emptySet()))

    fun beginFailure(
        actorType: LoginAttemptActorType,
        loginIdHmac: String,
        ipHmac: String,
        now: Instant,
    ): LoginAttemptLock {
        val inserted = mutableSetOf<LoginAttemptScope>()
        listOf(LoginAttemptScope.IP to ipHmac, LoginAttemptScope.LOGIN_ID to loginIdHmac)
            .sortedWith(compareBy({ it.first.name }, { it.second }))
            .forEach { (scope, hmac) ->
                val count =
                    jdbc.update(
                        """
                        INSERT INTO identity_login_attempt
                            (id, actor_type, scope_type, scope_hmac, window_start,
                             failure_count, blocked_until, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, NULL, ?)
                        ON CONFLICT (actor_type, scope_type, scope_hmac) DO NOTHING
                        """.trimIndent(),
                        UUID.randomUUID(),
                        actorType.name,
                        scope.name,
                        hmac,
                        Timestamp.from(now),
                        Timestamp.from(now),
                    )
                if (count == 1) inserted += scope
            }
        return LoginAttemptLock(lockRows(actorType, loginIdHmac, ipHmac, inserted))
    }

    fun applyFailure(
        lock: LoginAttemptLock,
        now: Instant,
    ): LoginAttemptOutcome {
        val updated = lock.rows.mapValues { (_, row) -> advance(row, now) }
        return LoginAttemptOutcome(
            loginId = checkNotNull(updated[LoginAttemptScope.LOGIN_ID]),
            ip = checkNotNull(updated[LoginAttemptScope.IP]),
        )
    }

    fun deleteLoginId(
        actorType: LoginAttemptActorType,
        loginIdHmac: String,
    ) {
        jdbc.update(
            "DELETE FROM identity_login_attempt WHERE actor_type = ? AND scope_type = 'LOGIN_ID' AND scope_hmac = ?",
            actorType.name,
            loginIdHmac,
        )
    }

    fun deleteExpired(
        cutoff: Instant,
        limit: Int,
    ): Int =
        jdbc.update(
            """
            DELETE FROM identity_login_attempt
             WHERE id IN (
                SELECT id
                  FROM identity_login_attempt
                 WHERE updated_at < ?
                 ORDER BY updated_at, id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
             )
            """.trimIndent(),
            Timestamp.from(cutoff),
            limit,
        )

    private fun lockRows(
        actorType: LoginAttemptActorType,
        loginIdHmac: String,
        ipHmac: String,
        inserted: Set<LoginAttemptScope>,
    ): Map<LoginAttemptScope, LoginAttemptRow> =
        jdbc
            .query(
                """
                SELECT id, scope_type, scope_hmac, window_start, failure_count, blocked_until
                  FROM identity_login_attempt
                 WHERE actor_type = ?
                   AND ((scope_type = 'IP' AND scope_hmac = ?)
                     OR (scope_type = 'LOGIN_ID' AND scope_hmac = ?))
                 ORDER BY actor_type, scope_type, scope_hmac
                 FOR UPDATE
                """.trimIndent(),
                { result, _ -> result.toAttemptRow(inserted) },
                actorType.name,
                ipHmac,
                loginIdHmac,
            ).associateBy(LoginAttemptRow::scope)

    private fun advance(
        row: LoginAttemptRow,
        now: Instant,
    ): LoginAttemptRow {
        val windowExpired = !now.isBefore(row.windowStart.plus(WINDOW))
        val currentlyBlocked = row.blockedUntil?.let(now::isBefore) == true
        val next =
            when {
                currentlyBlocked -> {
                    row
                }

                row.newlyInserted -> {
                    row
                }

                windowExpired || row.blockedUntil != null -> {
                    row.copy(windowStart = now, failureCount = 1, blockedUntil = null)
                }

                else -> {
                    val count = (row.failureCount + 1).coerceAtMost(row.scope.limit)
                    row.copy(
                        failureCount = count,
                        blockedUntil = if (count == row.scope.limit) now.plus(WINDOW) else null,
                    )
                }
            }
        if (next != row) {
            jdbc.update(
                """
                UPDATE identity_login_attempt
                   SET window_start = ?, failure_count = ?, blocked_until = ?, updated_at = ?
                 WHERE id = ?
                """.trimIndent(),
                Timestamp.from(next.windowStart),
                next.failureCount,
                next.blockedUntil?.let(Timestamp::from),
                Timestamp.from(now),
                next.id,
            )
        }
        return next.copy(newlyInserted = false)
    }

    private fun ResultSet.toAttemptRow(inserted: Set<LoginAttemptScope>): LoginAttemptRow {
        val scope = LoginAttemptScope.valueOf(getString("scope_type"))
        return LoginAttemptRow(
            id = getObject("id", UUID::class.java),
            scope = scope,
            scopeHmac = getString("scope_hmac"),
            windowStart = getTimestamp("window_start").toInstant(),
            failureCount = getInt("failure_count"),
            blockedUntil = getTimestamp("blocked_until")?.toInstant(),
            newlyInserted = scope in inserted,
        )
    }

    private companion object {
        val WINDOW: Duration = Duration.ofMinutes(15)
    }
}

@Component
internal class LoginAttemptRetentionWorker(
    private val repository: LoginAttemptRepository,
    private val clock: Clock,
    private val registry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.authentication.attempt-retention-fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.authentication.attempt-retention-initial-delay-ms:300000}",
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deleteExpired() {
        try {
            val deleted = repository.deleteExpired(clock.instant().minus(24, ChronoUnit.HOURS), 100)
            registry.counter("beanflow.identity.login_attempt.retention", "outcome", "success").increment(deleted.toDouble())
        } catch (failure: RuntimeException) {
            registry.counter("beanflow.identity.login_attempt.retention", "outcome", "failed").increment()
            logger.error("Customer login-attempt retention failed; authentication persistence remains required", failure)
            throw failure
        }
    }
}
