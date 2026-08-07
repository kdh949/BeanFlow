package io.github.kdh949.beanflow

/**
 * Returns a copy of [cursor] whose HMAC signature is guaranteed to decode to different bytes.
 *
 * Flipping the final Base64URL character is not sufficient. A 32-byte HMAC encodes to 43
 * characters covering 258 bits, so the last character's two low bits are padding that the decoder
 * discards: `a` (011010) and `b` (011011) differ only in that padding and decode to the *same*
 * signature. A test that mutates the last character therefore sends an untampered cursor whenever
 * the signature happens to end in `a` or `b`, and asserts a rejection that cannot happen.
 *
 * The first character's six bits are all significant, so mutating it always changes the signature.
 */
internal fun tamperSignedCursorSignature(cursor: String): String {
    val parts = cursor.split('.')
    require(parts.size == 4) { "Expected a v1.<key-id>.<payload>.<signature> cursor" }
    val signature = parts[3]
    require(signature.isNotEmpty()) { "Cursor signature must not be empty" }
    val mutatedFirst = if (signature.first() == 'A') 'B' else 'A'
    return listOf(parts[0], parts[1], parts[2], "$mutatedFirst${signature.drop(1)}").joinToString(".")
}
