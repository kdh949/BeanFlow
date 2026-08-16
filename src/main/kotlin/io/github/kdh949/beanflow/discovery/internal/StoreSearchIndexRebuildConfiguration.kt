package io.github.kdh949.beanflow.discovery.internal

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated

/** Settings that bound the in-memory target snapshot's processing batches. */
@Validated
@ConfigurationProperties(prefix = "beanflow.search-index-rebuild")
internal data class StoreSearchIndexRebuildProperties(
    @field:Min(1)
    @field:Max(1000)
    val chunkSize: Int = 100,
) {
    init {
        require(chunkSize in 1..1000) { "beanflow.search-index-rebuild.chunk-size must be between 1 and 1000" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StoreSearchIndexRebuildProperties::class)
internal class StoreSearchIndexRebuildConfiguration
