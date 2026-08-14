package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

internal fun interface StoreOrderBoardEtagGenerator {
    fun generate(board: StoreOrderBoardResponse): String
}

internal class StoreOrderBoardEtagFailure(
    cause: RuntimeException,
) : RuntimeException(cause)

@Component
internal class Sha256StoreOrderBoardEtagGenerator(
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : StoreOrderBoardEtagGenerator {
    override fun generate(board: StoreOrderBoardResponse): String {
        val sample = Timer.start(meterRegistry)
        return try {
            val canonical = objectMapper.writeValueAsBytes(canonical(board))
            DistributionSummary
                .builder("beanflow.store.order.board.response.canonical.bytes")
                .register(meterRegistry)
                .record(canonical.size.toDouble())
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(canonical)
                    .joinToString("") { "%02x".format(it) }
            "W/\"$digest\""
        } catch (failure: RuntimeException) {
            throw StoreOrderBoardEtagFailure(failure)
        } finally {
            sample.stop(meterRegistry.timer("beanflow.store.order.board.etag.duration"))
        }
    }

    /**
     * Overflow cursors contain their issuance and expiry times. They must not turn an unchanged 3-second
     * board poll into a cache miss, so this is a weak semantic validator rather than a byte validator.
     * Lane/count changes remain part of the semantic board representation.
     */
    private fun canonical(board: StoreOrderBoardResponse): CanonicalStoreOrderBoard =
        CanonicalStoreOrderBoard(
            groups = board.groups,
            overflow = board.overflow.map { entry -> CanonicalStoreOrderBoardOverflow(entry.lane, entry.overflowCount) },
        )

    private data class CanonicalStoreOrderBoard(
        val groups: List<StoreOrderBoardDateGroupResponse>,
        val overflow: List<CanonicalStoreOrderBoardOverflow>,
    )

    private data class CanonicalStoreOrderBoardOverflow(
        val lane: StoreOrderBoardLane,
        val overflowCount: Long,
    )
}

internal object StoreOrderBoardConditionalRequest {
    fun matches(
        ifNoneMatch: String?,
        currentEtag: String,
    ): Boolean =
        ifNoneMatch
            ?.split(',')
            ?.map(String::trim)
            ?.any { candidate -> candidate == "*" || opaqueTag(candidate) == opaqueTag(currentEtag) }
            ?: false

    private fun opaqueTag(tag: String): String = tag.removePrefix("W/")
}
