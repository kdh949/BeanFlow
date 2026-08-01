package io.github.kdh949.beanflow.shared.internal

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "beanflow.pagination.cursor-hmac")
internal data class CursorHmacProperties(
    val activeKeyId: String? = null,
    val keys: List<CursorHmacKeyProperties> = emptyList(),
)

internal data class CursorHmacKeyProperties(
    val id: String? = null,
    val secretBase64Url: String? = null,
)
