package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.RetentionPolicyCategory
import io.github.kdh949.beanflow.operations.api.RetentionPolicyOperations
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class AuditPermissionBoundaryConcurrencyTest
    @Autowired
    constructor(
        private val auditOperations: AuditRecordOperations,
        private val auditService: AuditRecordService,
        private val retentionPolicies: RetentionPolicyOperations,
        private val permissionLifecycle: OperatorPermissionGrantLifecycle,
        private val permissionAuthorization: OperatorPermissionAuthorizationService,
        private val transactionTemplate: TransactionTemplate,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

        @Test
        fun `two retention workers delete disjoint bounded chunks and leave future rows`() {
            transactionTemplate.executeWithoutResult {
                auditOperations.appendAll(
                    List(150) { index -> command(index, Instant.parse("2010-01-01T00:00:00Z")) } +
                        command(999, Instant.parse("2025-01-01T00:00:00Z")),
                )
            }
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val workers =
                    List(2) {
                        executor.submit<Int> {
                            check(start.await(5, TimeUnit.SECONDS))
                            auditService.purgeDue(Instant.parse("2020-01-01T00:00:00Z"), 100).deletedCount
                        }
                    }
                start.countDown()

                assertThat(workers.map { it.get(10, TimeUnit.SECONDS) }.sorted()).containsExactly(50, 100)
                assertThat(count()).isOne()
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT action FROM operations_audit_record",
                        String::class.java,
                    ),
                ).isEqualTo("STOCK_RESERVED")
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `policy head read lock is the linearization point for future activation`() {
            val policyLocked = CountDownLatch(1)
            val releasePolicy = CountDownLatch(1)
            val updaterAttempted = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val reader =
                    executor.submit {
                        transactionTemplate.executeWithoutResult {
                            retentionPolicies.current(RetentionPolicyCategory.PII_ACCESS)
                            policyLocked.countDown()
                            check(releasePolicy.await(5, TimeUnit.SECONDS))
                        }
                    }
                assertThat(policyLocked.await(5, TimeUnit.SECONDS)).isTrue()

                val updater =
                    executor.submit<Int> {
                        transactionTemplate.execute { status ->
                            updaterAttempted.countDown()
                            val updated =
                                jdbcTemplate.update(
                                    "UPDATE operations_retention_policy_head SET version = version + 1 " +
                                        "WHERE category = 'PII_ACCESS'",
                                )
                            status.setRollbackOnly()
                            updated
                        }
                    }
                assertThat(updaterAttempted.await(5, TimeUnit.SECONDS)).isTrue()
                assertThatThrownBy { updater.get(250, TimeUnit.MILLISECONDS) }
                    .isInstanceOf(TimeoutException::class.java)

                releasePolicy.countDown()
                reader.get(5, TimeUnit.SECONDS)
                assertThat(updater.get(5, TimeUnit.SECONDS)).isOne()
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT version FROM operations_retention_policy_head WHERE category = 'PII_ACCESS'",
                        Long::class.java,
                    ),
                ).isZero()
            } finally {
                releasePolicy.countDown()
                executor.shutdownNow()
            }
        }

        @Test
        fun `support permission revoke waits for an authorized local transaction then denies later use`() {
            val actorId = UUID.randomUUID()
            val permission = OperatorPermission.SUPPORT_CASE_READ
            val principal = VerifiedReleasePrincipal("issuer=test|subject=s10-concurrency")
            assertThat(apply(actorId, permission, OperatorPermissionBootstrapAction.GRANT, principal))
                .isEqualTo(OperatorPermissionBootstrapResult.APPLIED)
            val authorized = CountDownLatch(1)
            val release = CountDownLatch(1)
            val revokerAttempted = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val reader =
                    executor.submit {
                        transactionTemplate.executeWithoutResult {
                            permissionAuthorization.requireActive(actorId, permission)
                            authorized.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    }
                assertThat(authorized.await(5, TimeUnit.SECONDS)).isTrue()
                val revoker =
                    executor.submit<OperatorPermissionBootstrapResult> {
                        revokerAttempted.countDown()
                        apply(actorId, permission, OperatorPermissionBootstrapAction.REVOKE, principal)
                    }
                assertThat(revokerAttempted.await(5, TimeUnit.SECONDS)).isTrue()
                assertThatThrownBy { revoker.get(250, TimeUnit.MILLISECONDS) }
                    .isInstanceOf(TimeoutException::class.java)

                release.countDown()
                reader.get(5, TimeUnit.SECONDS)
                assertThat(revoker.get(5, TimeUnit.SECONDS)).isEqualTo(OperatorPermissionBootstrapResult.APPLIED)
                assertThatThrownBy {
                    transactionTemplate.executeWithoutResult {
                        permissionAuthorization.requireActive(actorId, permission)
                    }
                }.isInstanceOf(RuntimeException::class.java)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }

        private fun apply(
            actorId: UUID,
            permission: OperatorPermission,
            action: OperatorPermissionBootstrapAction,
            principal: VerifiedReleasePrincipal,
        ) = permissionLifecycle.apply(
            OperatorPermissionBootstrapCommand(
                action = action,
                actorId = actorId,
                permission = permission,
                reason = "S10 concurrency test",
                evidenceReference = "test://s10-concurrency",
                correlationId = UUID.randomUUID().toString(),
                now = Instant.parse("2026-08-01T00:00:00Z").plusSeconds(action.ordinal.toLong()),
            ),
            principal,
        )

        private fun command(
            index: Int,
            occurredAt: Instant,
        ) = AppendAuditRecordCommand(
            actorId = "SYSTEM",
            actorType = AuditActorType.SYSTEM,
            category = AuditCategory.ORDER_AND_FULFILLMENT,
            action = "STOCK_RESERVED",
            targetType = "S10_CONCURRENCY",
            targetId = UUID.nameUUIDFromBytes("s10-audit:$index".toByteArray()),
            occurredAt = occurredAt,
            reason = "S10_CONCURRENCY",
            beforeSummary = emptyMap(),
            afterSummary = mapOf("state" to "RECORDED"),
            correlationId = "s10-concurrency-$index",
            sourceReference = "s10-concurrency:$index",
        )

        private fun count(): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)!!
    }
