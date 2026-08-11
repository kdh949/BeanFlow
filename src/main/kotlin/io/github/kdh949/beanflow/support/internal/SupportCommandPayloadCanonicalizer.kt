package io.github.kdh949.beanflow.support.internal

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * A length-prefixed, typed and ordered command representation. Free-form values are never joined
 * with a delimiter, so different field boundaries cannot share an idempotency payload hash.
 */
@Component
internal class SupportCommandPayloadCanonicalizer {
    fun canonical(
        operation: String,
        fields: List<SupportCommandPayloadField>,
    ): String =
        buildString {
            appendSegment("support-command-payload/v1")
            appendSegment("operation")
            appendSegment("enum")
            appendSegment(operation)
            fields.forEach { field ->
                appendSegment(field.name)
                appendSegment(field.type)
                appendSegment(field.value)
            }
        }

    private fun StringBuilder.appendSegment(value: String?) {
        if (value == null) {
            append("-1:")
            return
        }
        append(value.toByteArray(StandardCharsets.UTF_8).size)
        append(':')
        append(value)
    }
}

internal data class SupportCommandPayloadField(
    val name: String,
    val type: String,
    val value: String?,
)
