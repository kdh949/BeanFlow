package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.internal.CursorHmacKeyProperties
import io.github.kdh949.beanflow.shared.internal.CursorHmacKeyRing
import io.github.kdh949.beanflow.shared.internal.CursorHmacProperties
import io.github.kdh949.beanflow.shared.internal.CursorMetrics
import io.github.kdh949.beanflow.shared.internal.HmacSignedCursorCodec
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

internal class CustomerOrderPagingTest {
    private val now = Instant.parse("2026-08-13T03:00:00Z")
    private val codec = codec(now)
    private val paging = CustomerOrderPaging(codec)
    private val customerId = UUID.fromString("10000000-0000-4000-8000-000000000001")

    @Test
    fun `defaults to thirty Seoul calendar days and common page bounds`() {
        val prepared = paging.prepare(criteria())

        assertThat(prepared.fromDate).isEqualTo(LocalDate.parse("2026-07-15"))
        assertThat(prepared.toDate).isEqualTo(LocalDate.parse("2026-08-13"))
        assertThat(prepared.fromInclusive).isEqualTo(Instant.parse("2026-07-14T15:00:00Z"))
        assertThat(prepared.toExclusive).isEqualTo(Instant.parse("2026-08-13T15:00:00Z"))
        assertThat(prepared.limit).isEqualTo(20)
        assertThat(prepared.status).isNull()
        assertThat(prepared.after).isNull()
    }

    @Test
    fun `cursor binds customer status and canonical effective date range`() {
        val first =
            paging.prepare(
                criteria(
                    status = CustomerOrderStatusFilter.ACTIVE,
                    from = LocalDate.parse("2026-01-01"),
                    to = LocalDate.parse("2026-08-13"),
                    limit = 1,
                ),
            )
        val sort = CustomerOrderSort(Instant.parse("2026-08-10T00:00:00Z"), UUID.fromString("20000000-0000-4000-8000-000000000002"))
        val token = codec.issue(first.cursorScope, sort, first.cursorExpiresAt)

        assertThat(
            paging
                .prepare(
                    criteria(
                        status = CustomerOrderStatusFilter.ACTIVE,
                        from = first.fromDate,
                        to = first.toDate,
                        cursor = token,
                        limit = 100,
                    ),
                ).after,
        ).isEqualTo(sort)

        listOf(
            criteria(
                customerId = UUID.fromString("10000000-0000-4000-8000-000000000002"),
                status = CustomerOrderStatusFilter.ACTIVE,
                from = first.fromDate,
                to = first.toDate,
                cursor = token,
            ),
            criteria(status = CustomerOrderStatusFilter.PAST, from = first.fromDate, to = first.toDate, cursor = token),
            criteria(status = CustomerOrderStatusFilter.ACTIVE, from = first.fromDate.minusDays(1), to = first.toDate, cursor = token),
        ).forEach { changed ->
            assertInvalid { paging.prepare(changed) }
        }
    }

    @Test
    fun `rejects invalid ranges limits oversized cursors and noncanonical sort values`() {
        assertInvalid { paging.prepare(criteria(from = LocalDate.parse("2026-08-14"), to = LocalDate.parse("2026-08-13"))) }
        assertInvalid { paging.prepare(criteria(limit = 0)) }
        assertInvalid { paging.prepare(criteria(limit = 101)) }
        assertInvalid { paging.prepare(criteria(cursor = "x".repeat(2049))) }

        assertThat(CustomerOrderPaging.SORT_ADAPTER.decode(listOf("2026-08-13T00:00:00Z", customerId.toString())))
            .isEqualTo(CustomerOrderSort(Instant.parse("2026-08-13T00:00:00Z"), customerId))
        assertThat(CustomerOrderPaging.SORT_ADAPTER.decode(listOf("2026-08-13T00:00:00+00:00", customerId.toString())))
            .isNull()
        val alphabeticId = UUID.fromString("abcdefab-cdef-4abc-8def-abcdefabcdef")
        assertThat(CustomerOrderPaging.SORT_ADAPTER.decode(listOf("2026-08-13T00:00:00Z", alphabeticId.toString().uppercase())))
            .isNull()
        assertThat(CustomerOrderPaging.SORT_ADAPTER.decode(listOf("2026-08-13T00:00:00Z"))).isNull()
    }

    private fun criteria(
        customerId: UUID = this.customerId,
        status: CustomerOrderStatusFilter? = null,
        from: LocalDate? = null,
        to: LocalDate? = null,
        cursor: String? = null,
        limit: Int? = null,
    ) = CustomerOrderListCriteria(customerId, status, from, to, cursor, limit, now)

    private fun assertInvalid(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.INVALID_REQUEST)
    }

    private fun codec(now: Instant): HmacSignedCursorCodec {
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { index -> (index + 1).toByte() })
        val keyRing =
            CursorHmacKeyRing.from(
                CursorHmacProperties(
                    activeKeyId = "customer-order-test-vector",
                    keys = listOf(CursorHmacKeyProperties("customer-order-test-vector", secret)),
                ),
            )
        return HmacSignedCursorCodec(keyRing, Clock.fixed(now, ZoneOffset.UTC), CursorMetrics(SimpleMeterRegistry()))
    }
}
