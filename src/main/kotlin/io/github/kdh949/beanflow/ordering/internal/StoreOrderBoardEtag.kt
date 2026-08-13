package io.github.kdh949.beanflow.ordering.internal

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
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
) : StoreOrderBoardEtagGenerator {
    override fun generate(board: StoreOrderBoardResponse): String =
        try {
            val canonical = objectMapper.writeValueAsString(board)
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            "\"$digest\""
        } catch (failure: RuntimeException) {
            throw StoreOrderBoardEtagFailure(failure)
        }
}

internal object StoreOrderBoardConditionalRequest {
    fun matches(
        ifNoneMatch: String?,
        currentEtag: String,
    ): Boolean =
        ifNoneMatch
            ?.split(',')
            ?.map(String::trim)
            ?.any { candidate -> candidate == "*" || candidate.removePrefix("W/") == currentEtag }
            ?: false
}
