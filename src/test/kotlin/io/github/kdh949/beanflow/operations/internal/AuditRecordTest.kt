package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	properties = [
		"beanflow.reservation-expiry.initial-delay-ms=3600000",
		"beanflow.audit-retention.initial-delay-ms=3600000",
	],
)
internal class AuditRecordTest @Autowired constructor(
	private val operations: AuditRecordOperations,
	private val service: AuditRecordService,
	private val transactionTemplate: TransactionTemplate,
	private val jdbcTemplate: JdbcTemplate,
) {

	@BeforeEach
	fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

	@Test
	fun `sensitive summary is rejected instead of persisted`() {
		val command = command(after = mapOf("cardNumber" to "redacted-value"))

		assertThatThrownBy {
			transactionTemplate.executeWithoutResult { operations.appendAll(listOf(command)) }
		}
			.isInstanceOfSatisfying(DomainFailure::class.java) {
				assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
			}
		assertThat(count()).isZero()
	}

	@Test
	fun `duplicate audit key is rejected instead of overwriting append only history`() {
		val command = command()
		transactionTemplate.executeWithoutResult { operations.appendAll(listOf(command)) }

		assertThatThrownBy {
			transactionTemplate.executeWithoutResult { operations.appendAll(listOf(command)) }
		}.isInstanceOf(DataIntegrityViolationException::class.java)

		assertThat(count()).isEqualTo(1)
		assertThat(AuditRecordOperations::class.java.methods.map { it.name })
			.doesNotContain("update", "delete", "purge")
	}

	@Test
	fun `seoul calendar fifth anniversary controls leap day cleanup boundary`() {
		val occurredAt = ZonedDateTime.of(
			2024,
			2,
			29,
			12,
			0,
			0,
			0,
			ZoneId.of("Asia/Seoul"),
		).toInstant()
		val command = command(occurredAt = occurredAt)
		transactionTemplate.executeWithoutResult { operations.appendAll(listOf(command)) }
		val expectedExpiry = Instant.parse("2029-02-28T03:00:00Z")

		assertThat(service.retentionExpiry(occurredAt)).isEqualTo(expectedExpiry)
		assertThat(service.purgeDue(expectedExpiry.minusNanos(1_000), 10).deletedCount).isZero()
		assertThat(service.purgeDue(expectedExpiry, 10).deletedCount).isEqualTo(1)
		assertThat(service.purgeDue(expectedExpiry, 10).deletedCount).isZero()
	}

	private fun command(
		occurredAt: Instant = Instant.parse("2026-07-28T00:00:00Z"),
		after: Map<String, String> = mapOf("state" to "RESERVED"),
	) = AppendAuditRecordCommand(
		actorId = UUID.randomUUID().toString(),
		actorType = AuditActorType.CUSTOMER,
		action = "STOCK_RESERVED",
		targetType = "STOCK_RESERVATION",
		targetId = UUID.randomUUID(),
		occurredAt = occurredAt,
		reason = "CUSTOMER_ORDER_CREATION",
		beforeSummary = emptyMap(),
		afterSummary = after,
		correlationId = UUID.randomUUID().toString(),
		sourceReference = "test:${UUID.randomUUID()}",
	)

	private fun count(): Long =
		requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java))
}
