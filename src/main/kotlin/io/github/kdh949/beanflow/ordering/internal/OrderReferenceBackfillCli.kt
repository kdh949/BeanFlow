package io.github.kdh949.beanflow.ordering.internal

import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import java.time.Clock
import kotlin.system.exitProcess

@Configuration(proxyBeanMethods = false)
@Profile("order-reference-backfill")
@EnableAutoConfiguration
@Import(
    OrderReferenceBackfillService::class,
    PublicOrderReferenceGenerator::class,
    SecurePublicOrderReferenceEntropy::class,
)
internal class OrderReferenceBackfillApplication {
    @Bean
    fun backfillClock(): Clock = Clock.systemUTC()
}

internal data class OrderReferenceBackfillArguments(
    val batchSize: Int,
) {
    companion object {
        fun parse(arguments: Array<String>): OrderReferenceBackfillArguments {
            if (arguments.size > 1) throw IllegalArgumentException("Only --batch-size is supported")
            val batchSize =
                arguments.singleOrNull()?.let { argument ->
                    if (!argument.startsWith("--batch-size=")) {
                        throw IllegalArgumentException("Expected --batch-size=<1..1000>")
                    }
                    argument.substringAfter('=').toIntOrNull()
                        ?: throw IllegalArgumentException("batch-size must be an integer")
                } ?: DEFAULT_BATCH_SIZE
            require(batchSize in 1..1_000) { "batch-size must be between 1 and 1000" }
            return OrderReferenceBackfillArguments(batchSize)
        }

        private const val DEFAULT_BATCH_SIZE = 100
    }
}

internal object OrderReferenceBackfillCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val parsed =
            try {
                OrderReferenceBackfillArguments.parse(arguments)
            } catch (failure: IllegalArgumentException) {
                System.err.println("order-reference-backfill invalid_arguments: ${failure.message}")
                exitProcess(2)
            }
        val application =
            SpringApplicationBuilder(OrderReferenceBackfillApplication::class.java)
                .profiles("order-reference-backfill")
                .web(WebApplicationType.NONE)
                .properties(
                    mapOf(
                        "spring.flyway.target" to "43",
                        "spring.main.banner-mode" to "off",
                    ),
                ).build()
        application.setRegisterShutdownHook(false)
        val exitCode =
            try {
                application.run().use { context ->
                    val result = context.getBean(OrderReferenceBackfillService::class.java).runAll(parsed.batchSize)
                    System.out.println(
                        "order-reference-backfill completed processed=${result.processedCount} batches=${result.batchCount}",
                    )
                }
                0
            } catch (failure: RuntimeException) {
                System.err.println(
                    "order-reference-backfill failed type=${failure::class.simpleName} message=${failure.message}",
                )
                1
            }
        exitProcess(exitCode)
    }
}
