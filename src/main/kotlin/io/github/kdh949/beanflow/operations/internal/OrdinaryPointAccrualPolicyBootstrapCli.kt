package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import java.io.PrintStream
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import kotlin.system.exitProcess

@Configuration(proxyBeanMethods = false)
@Profile("ordinary-point-accrual-policy-bootstrap")
@EnableAutoConfiguration
@EntityScan(basePackageClasses = [AuditRecordEntity::class, OrdinaryPointAccrualPolicyVersionEntity::class])
@EnableJpaRepositories(
    basePackageClasses = [AuditRecordJpaRepository::class, OrdinaryPointAccrualPolicyVersionJpaRepository::class],
)
@Import(
    AuditRecordService::class,
    DatabaseAdvisoryLock::class,
    OrdinaryPointAccrualPolicyBootstrapTransaction::class,
    OrdinaryPointAccrualPolicyBootstrapLifecycle::class,
)
internal class OrdinaryPointAccrualPolicyBootstrapApplication {
    @Bean
    fun pointAccrualBootstrapIdentifierSource(): IdentifierSource = IdentifierSource(UUID::randomUUID)
}

internal data class ParsedOrdinaryPointAccrualPolicyBootstrap(
    val command: OrdinaryPointAccrualPolicyBootstrapCommand,
    val identity: OidcWorkloadIdentityConfiguration,
)

internal class OrdinaryPointAccrualPolicyBootstrapInputParser(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun parse(
        arguments: Array<String>,
        environment: Map<String, String>,
    ): ParsedOrdinaryPointAccrualPolicyBootstrap {
        val supplied = parseArguments(arguments)
        val command =
            OrdinaryPointAccrualPolicyBootstrapCommand(
                accrualRateBps = required(supplied, environment, "rate-bps", "RATE_BPS").toInt(),
                roundingMode =
                    PointAccrualRoundingMode.valueOf(
                        required(supplied, environment, "rounding-mode", "ROUNDING_MODE").uppercase(),
                    ),
                issuerType =
                    PointAccrualIssuerType.valueOf(
                        required(supplied, environment, "issuer-type", "ISSUER_TYPE").uppercase(),
                    ),
                issuerReference = required(supplied, environment, "issuer-reference", "ISSUER_REFERENCE").trim(),
                expiryRule =
                    OrdinaryPointAccrualExpiryRule.valueOf(
                        required(supplied, environment, "expiry-rule", "EXPIRY_RULE").uppercase(),
                    ),
                validityDays = required(supplied, environment, "validity-days", "VALIDITY_DAYS").toInt(),
                reason = required(supplied, environment, "reason", "REASON").trim(),
                evidenceReference =
                    required(supplied, environment, "evidence-reference", "EVIDENCE_REFERENCE").trim(),
                correlationId = required(supplied, environment, "correlation-id", "CORRELATION_ID").trim(),
                now = clock.instant(),
            )
        validate(command)
        val allowedSubjects =
            identityRequired(supplied, environment, "allowed-subjects", "ALLOWED_SUBJECTS")
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        val identity =
            OidcWorkloadIdentityConfiguration(
                tokenFile = Path.of(identityRequired(supplied, environment, "token-file", "TOKEN_FILE")),
                jwkSetFile = Path.of(identityRequired(supplied, environment, "jwk-set-file", "JWK_SET_FILE")),
                issuer = identityRequired(supplied, environment, "issuer", "ISSUER"),
                audience = identityRequired(supplied, environment, "audience", "AUDIENCE"),
                allowedSubjects = allowedSubjects,
                deploymentRunClaim =
                    identityRequired(
                        supplied,
                        environment,
                        "deployment-run-claim",
                        "DEPLOYMENT_RUN_CLAIM",
                    ),
            )
        return ParsedOrdinaryPointAccrualPolicyBootstrap(command, identity)
    }

    private fun parseArguments(arguments: Array<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        arguments.forEach { argument ->
            if (!argument.startsWith("--") || '=' !in argument) throw IllegalArgumentException()
            val (name, value) = argument.removePrefix("--").split('=', limit = 2)
            if (name !in ALLOWED_ARGUMENTS || result.put(name, value) != null) throw IllegalArgumentException()
        }
        return result
    }

    private fun required(
        supplied: Map<String, String>,
        environment: Map<String, String>,
        argument: String,
        environmentSuffix: String,
    ): String =
        supplied[argument]
            ?: environment["BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_$environmentSuffix"]
            ?: throw IllegalArgumentException()

    private fun identityRequired(
        supplied: Map<String, String>,
        environment: Map<String, String>,
        argument: String,
        environmentSuffix: String,
    ): String =
        supplied[argument]
            ?: environment["BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_$environmentSuffix"]
            ?: throw WorkloadIdentityConfigurationException()

    private fun validate(command: OrdinaryPointAccrualPolicyBootstrapCommand) {
        if (command.accrualRateBps !in 0..10_000 || command.validityDays !in 1..3650) {
            throw IllegalArgumentException()
        }
        if (command.issuerReference.length !in 1..240 || command.issuerReference.hasControlCharacter()) {
            throw IllegalArgumentException()
        }
        if (command.reason.length !in 1..500 || command.reason.hasControlCharacter()) throw IllegalArgumentException()
        if (command.evidenceReference.length !in 1..500 || command.evidenceReference.hasControlCharacter()) {
            throw IllegalArgumentException()
        }
        if (command.correlationId.length !in 1..160 || command.correlationId.hasControlCharacter()) {
            throw IllegalArgumentException()
        }
    }

    private companion object {
        val ALLOWED_ARGUMENTS =
            setOf(
                "rate-bps",
                "rounding-mode",
                "issuer-type",
                "issuer-reference",
                "expiry-rule",
                "validity-days",
                "reason",
                "evidence-reference",
                "correlation-id",
                "token-file",
                "jwk-set-file",
                "issuer",
                "audience",
                "allowed-subjects",
                "deployment-run-claim",
            )
    }
}

internal object OrdinaryPointAccrualPolicyBootstrapCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        exitProcess(execute(arguments, System.getenv(), System.out, System.err))
    }

    internal fun execute(
        arguments: Array<String>,
        environment: Map<String, String>,
        standardOut: PrintStream,
        standardError: PrintStream,
        springProperties: Map<String, Any> = emptyMap(),
        apply: (
            (
                OrdinaryPointAccrualPolicyBootstrapCommand,
                VerifiedReleasePrincipal,
            ) -> OrdinaryPointAccrualPolicyBootstrapResult
        )? = null,
    ): Int {
        val parsed =
            try {
                OrdinaryPointAccrualPolicyBootstrapInputParser().parse(arguments, environment)
            } catch (_: WorkloadIdentityConfigurationException) {
                writeResult(standardError, OrdinaryPointAccrualPolicyBootstrapResult.IDENTITY_VERIFICATION_FAILED)
                return 3
            } catch (_: RuntimeException) {
                writeResult(standardError, OrdinaryPointAccrualPolicyBootstrapResult.INVALID_INPUT)
                return 2
            }
        val principal =
            try {
                OidcWorkloadIdentityVerifier().verify(parsed.identity)
            } catch (_: WorkloadIdentityVerificationException) {
                writeResult(standardError, OrdinaryPointAccrualPolicyBootstrapResult.IDENTITY_VERIFICATION_FAILED)
                return 3
            }
        val result =
            try {
                apply?.invoke(parsed.command, principal)
                    ?: applyWithSpring(parsed.command, principal, springProperties)
            } catch (_: RuntimeException) {
                OrdinaryPointAccrualPolicyBootstrapResult.DEPENDENCY_UNAVAILABLE
            }
        writeResult(
            if (result == OrdinaryPointAccrualPolicyBootstrapResult.APPLIED) standardOut else standardError,
            result,
        )
        return when (result) {
            OrdinaryPointAccrualPolicyBootstrapResult.APPLIED -> 0
            OrdinaryPointAccrualPolicyBootstrapResult.INVALID_INPUT -> 2
            OrdinaryPointAccrualPolicyBootstrapResult.IDENTITY_VERIFICATION_FAILED -> 3
            OrdinaryPointAccrualPolicyBootstrapResult.POLICY_ALREADY_INITIALIZED -> 4
            OrdinaryPointAccrualPolicyBootstrapResult.DEPENDENCY_UNAVAILABLE -> 5
        }
    }

    private fun applyWithSpring(
        command: OrdinaryPointAccrualPolicyBootstrapCommand,
        principal: VerifiedReleasePrincipal,
        springProperties: Map<String, Any>,
    ): OrdinaryPointAccrualPolicyBootstrapResult {
        val application: SpringApplication =
            SpringApplicationBuilder(OrdinaryPointAccrualPolicyBootstrapApplication::class.java)
                .profiles("ordinary-point-accrual-policy-bootstrap")
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .properties(
                    mapOf(
                        "spring.main.banner-mode" to "off",
                        "spring.task.scheduling.enabled" to "false",
                        "logging.level.root" to "OFF",
                        "spring.autoconfigure.exclude" to MODULITH_AUTO_CONFIGURATION_EXCLUSIONS,
                    ) + springProperties,
                ).build()
        val context = application.run()
        return try {
            context.getBean(OrdinaryPointAccrualPolicyBootstrapLifecycle::class.java).apply(command, principal)
        } finally {
            context.close()
        }
    }

    private fun writeResult(
        output: PrintStream,
        result: OrdinaryPointAccrualPolicyBootstrapResult,
    ) {
        output.println("operation=INITIALIZE principal=verified-release-principal result=${result.name}")
    }

    private const val MODULITH_AUTO_CONFIGURATION_EXCLUSIONS =
        "org.springframework.modulith.actuator.autoconfigure.ApplicationModulesEndpointConfiguration," +
            "org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration," +
            "org.springframework.modulith.observability.autoconfigure.SpringDataRestModuleObservabilityAutoConfiguration," +
            "org.springframework.modulith.core.config.ApplicationModuleInitializerRuntimeVerification," +
            "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration," +
            "org.springframework.modulith.events.config.EventPublicationAutoConfiguration," +
            "org.springframework.modulith.events.config.EventExternalizationAutoConfiguration," +
            "org.springframework.modulith.events.jpa.JpaEventPublicationAutoConfiguration," +
            "org.springframework.modulith.events.jpa.archiving.ArchivingAutoConfiguration," +
            "org.springframework.modulith.moments.autoconfigure.MomentsAutoConfiguration," +
            "org.springframework.modulith.moments.autoconfigure.MomentsJacksonAutoConfiguration," +
            "org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration," +
            "org.springframework.modulith.events.jackson2.Jackson2EventSerializationConfiguration"
}
