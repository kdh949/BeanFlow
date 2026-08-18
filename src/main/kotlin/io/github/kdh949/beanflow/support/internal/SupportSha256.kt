package io.github.kdh949.beanflow.support.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

internal object SupportSha256 {
    fun utf8(value: String): String = bytes(value.toByteArray(StandardCharsets.UTF_8))

    fun bytes(value: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
}
