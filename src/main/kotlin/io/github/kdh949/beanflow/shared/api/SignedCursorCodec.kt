package io.github.kdh949.beanflow.shared.api

import java.time.Instant

/**
 * Shared boundary for opaque, signed keyset cursors. Endpoint adapters own their typed sort tuple.
 */
interface SignedCursorCodec {
    fun <T> issue(
        scope: SignedCursorScope<T>,
        sort: T,
        expiresAt: Instant,
    ): String

    fun <T> verify(
        token: String,
        scope: SignedCursorScope<T>,
    ): SignedCursor<T>
}

/**
 * Binds a public endpoint and its canonical filter digest to the typed sort tuple it owns.
 */
class SignedCursorScope<T>(
    val endpoint: String,
    val filterHash: String,
    val sortAdapter: CursorSortAdapter<T>,
)

/**
 * Converts an endpoint-owned sort tuple without exposing untrusted token parsing to a repository.
 */
interface CursorSortAdapter<T> {
    fun encode(sort: T): List<String>

    fun decode(values: List<String>): T?
}

data class SignedCursor<T>(
    val sort: T,
    val issuedAt: Instant,
    val expiresAt: Instant,
)
