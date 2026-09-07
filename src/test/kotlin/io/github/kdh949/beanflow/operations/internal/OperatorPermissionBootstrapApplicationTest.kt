package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@BeanflowIsolatedSpringContext("verifies committed audit state in the narrow bootstrap context")
@SpringBootTest(
    classes = [OperatorPermissionBootstrapApplication::class, TestcontainersConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" + ORDINARY_POINT_ACCRUAL_POLICY_BOOTSTRAP_AUTO_CONFIGURATION_EXCLUSIONS,
    ],
)
@ActiveProfiles("operator-permission-bootstrap")
internal class OperatorPermissionBootstrapApplicationTest
    @Autowired
    constructor(
        private val audits: AuditRecordOperations,
        private val transaction: TransactionTemplate,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @Test
        fun `narrow operator bootstrap commits a new audit record`() {
            val target = UUID.randomUUID()
            val ids =
                requireNotNull(
                    transaction.execute {
                        audits.appendAll(
                            listOf(
                                AppendAuditRecordCommand(
                                    actorId = "bootstrap-test",
                                    actorType = AuditActorType.SYSTEM,
                                    category = AuditCategory.SECURITY_AND_PERMISSION,
                                    action = "OPERATOR_PERMISSION_GRANTED",
                                    targetType = "OPERATOR_PERMISSION_GRANT",
                                    targetId = target,
                                    occurredAt = Instant.parse("2026-09-08T00:00:00Z"),
                                    reason = "BOOTSTRAP_DEPENDENCY_VERIFICATION",
                                    correlationId = "bootstrap-test",
                                    sourceReference = "bootstrap-test:$target",
                                ),
                            ),
                        )
                    },
                )
            assertThat(ids).hasSize(1)
            assertThat(
                jdbcTemplate.queryForObject("SELECT count(*) FROM operations_audit_record WHERE id = ?", Long::class.java, ids.single()),
            ).isEqualTo(1)
        }
    }
