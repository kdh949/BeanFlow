package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.ReservationExpiryOutcome
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryResult
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class ReservationExpiryWorkerUnitTest {

	@Test
	fun `runOnce returns only orders that actually expired`() {
		val expiredId = UUID.randomUUID()
		val noLongerEligibleId = UUID.randomUUID()
		val repository = mock<OrderJpaRepository>()
		val useCase = mock<ReservationExpiryUseCase>()
		`when`(repository.findDueIds(NOW, PageRequest.of(0, 100)))
			.thenReturn(listOf(expiredId, noLongerEligibleId))
		`when`(useCase.expireIfDue(expiredId, NOW))
			.thenReturn(ReservationExpiryResult(expiredId, ReservationExpiryOutcome.EXPIRED))
		`when`(useCase.expireIfDue(noLongerEligibleId, NOW))
			.thenReturn(ReservationExpiryResult(noLongerEligibleId, ReservationExpiryOutcome.NOT_ELIGIBLE))
		val worker = ReservationExpiryWorker(
			orderRepository = repository,
			expiryUseCase = useCase,
			clock = Clock.fixed(NOW, ZoneOffset.UTC),
			meterRegistry = SimpleMeterRegistry(),
			chunkSize = 100,
		)

		assertThat(worker.runOnce()).isEqualTo(1)
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-07-29T00:00:00Z")
	}
}
