package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OperatorPermission
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
@Profile("operator-permission-bootstrap")
@EnableAutoConfiguration
@EntityScan(
    basePackageClasses = [
        AuditRecordEntity::class,
        OperatorPermissionGrantEntity::class,
        RetentionPolicyHeadEntity::class,
        RetentionPolicyVersionEntity::class,
    ],
)
@EnableJpaRepositories(
    basePackageClasses = [
        AuditRecordJpaRepository::class,
        OperatorPermissionGrantJpaRepository::class,
        RetentionPolicyHeadJpaRepository::class,
        RetentionPolicyVersionJpaRepository::class,
    ],
)
@Import(
    AuditRecordService::class,
    RetentionPolicyService::class,
    DatabaseAdvisoryLock::class,
    OperatorSecurityMetrics::class,
    OperatorPermissionGrantTransaction::class,
    OperatorPermissionGrantLifecycle::class,
)
internal class OperatorPermissionBootstrapApplication {
    @Bean
    fun bootstrapIdentifierSource(): IdentifierSource = IdentifierSource(UUID::randomUUID)
}

internal data class ParsedOperatorPermissionBootstrap(
    val command: OperatorPermissionBootstrapCommand,
    val identity: OidcWorkloadIdentityConfiguration,
)

internal class WorkloadIdentityConfigurationException : RuntimeException()

internal class OperatorPermissionBootstrapInputParser(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun parse(
        arguments: Array<String>,
        environment: Map<String, String>,
    ): ParsedOperatorPermissionBootstrap {
        val supplied = parseArguments(arguments)
        val action =
            OperatorPermissionBootstrapAction.valueOf(
                required(supplied, environment, "action", "BEANFLOW_OPERATOR_BOOTSTRAP_ACTION").uppercase(),
            )
        val actorId =
            UUID.fromString(
                required(supplied, environment, "actor-id", "BEANFLOW_OPERATOR_BOOTSTRAP_ACTOR_ID"),
            )
        val permission =
            OperatorPermission.valueOf(
                required(supplied, environment, "permission", "BEANFLOW_OPERATOR_BOOTSTRAP_PERMISSION"),
            )
        val command =
            OperatorPermissionBootstrapCommand(
                action = action,
                actorId = actorId,
                permission = permission,
                reason = required(supplied, environment, "reason", "BEANFLOW_OPERATOR_BOOTSTRAP_REASON"),
                evidenceReference =
                    required(
                        supplied,
                        environment,
                        "evidence-reference",
                        "BEANFLOW_OPERATOR_BOOTSTRAP_EVIDENCE_REFERENCE",
                    ),
                correlationId =
                    required(
                        supplied,
                        environment,
                        "correlation-id",
                        "BEANFLOW_OPERATOR_BOOTSTRAP_CORRELATION_ID",
                    ),
                now = clock.instant(),
            )
        validateCommand(command)
        val allowedSubjects =
            identityRequired(
                supplied,
                environment,
                "allowed-subjects",
                "BEANFLOW_OPERATOR_BOOTSTRAP_ALLOWED_SUBJECTS",
            ).split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        val identity =
            OidcWorkloadIdentityConfiguration(
                tokenFile =
                    Path.of(
                        identityRequired(
                            supplied,
                            environment,
                            "token-file",
                            "BEANFLOW_OPERATOR_BOOTSTRAP_TOKEN_FILE",
                        ),
                    ),
                jwkSetFile =
                    Path.of(
                        identityRequired(
                            supplied,
                            environment,
                            "jwk-set-file",
                            "BEANFLOW_OPERATOR_BOOTSTRAP_JWK_SET_FILE",
                        ),
                    ),
                issuer =
                    identityRequired(supplied, environment, "issuer", "BEANFLOW_OPERATOR_BOOTSTRAP_ISSUER"),
                audience =
                    identityRequired(supplied, environment, "audience", "BEANFLOW_OPERATOR_BOOTSTRAP_AUDIENCE"),
                allowedSubjects = allowedSubjects,
                deploymentRunClaim =
                    identityRequired(
                        supplied,
                        environment,
                        "deployment-run-claim",
                        "BEANFLOW_OPERATOR_BOOTSTRAP_DEPLOYMENT_RUN_CLAIM",
                    ),
            )
        return ParsedOperatorPermissionBootstrap(command, identity)
    }

    private fun parseArguments(arguments: Array<String>): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        arguments.forEach { argument ->
            if (!argument.startsWith("--") || '=' !in argument) throw IllegalArgumentException()
            val (name, value) = argument.removePrefix("--").split('=', limit = 2)
            if (name !in ALLOWED_ARGUMENTS || parsed.put(name, value) != null) throw IllegalArgumentException()
        }
        return parsed
    }

    private fun required(
        supplied: Map<String, String>,
        environment: Map<String, String>,
        argument: String,
        environmentName: String,
    ): String = supplied[argument] ?: environment[environmentName] ?: throw IllegalArgumentException()

    private fun identityRequired(
        supplied: Map<String, String>,
        environment: Map<String, String>,
        argument: String,
        environmentName: String,
    ): String = supplied[argument] ?: environment[environmentName] ?: throw WorkloadIdentityConfigurationException()

    private fun validateCommand(command: OperatorPermissionBootstrapCommand) {
        if (command.reason.trim().length !in 1..500 || command.reason.hasControlCharacter()) {
            throw IllegalArgumentException()
        }
        if (command.evidenceReference.trim().length !in 1..500 || command.evidenceReference.hasControlCharacter()) {
            throw IllegalArgumentException()
        }
        if (command.correlationId.trim().length !in 1..160 || command.correlationId.hasControlCharacter()) {
            throw IllegalArgumentException()
        }
    }

    private companion object {
        val ALLOWED_ARGUMENTS =
            setOf(
                "action",
                "actor-id",
                "permission",
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

internal object OperatorPermissionBootstrapCli {
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
        apply: ((OperatorPermissionBootstrapCommand, VerifiedReleasePrincipal) -> OperatorPermissionBootstrapResult)? = null,
    ): Int {
        val parsed =
            try {
                OperatorPermissionBootstrapInputParser().parse(arguments, environment)
            } catch (_: WorkloadIdentityConfigurationException) {
                writeResult(
                    standardError,
                    "UNKNOWN",
                    "UNKNOWN",
                    OperatorPermissionBootstrapResult.IDENTITY_VERIFICATION_FAILED,
                )
                return 3
            } catch (_: RuntimeException) {
                writeResult(
                    standardError,
                    "UNKNOWN",
                    "UNKNOWN",
                    OperatorPermissionBootstrapResult.INVALID_INPUT,
                )
                return 2
            }
        val principal =
            try {
                OidcWorkloadIdentityVerifier().verify(parsed.identity)
            } catch (_: WorkloadIdentityVerificationException) {
                writeResult(
                    standardError,
                    parsed.command.action.name,
                    parsed.command.permission.name,
                    OperatorPermissionBootstrapResult.IDENTITY_VERIFICATION_FAILED,
                )
                return 3
            }
        val result =
            try {
                apply?.invoke(parsed.command, principal)
                    ?: applyWithSpring(parsed.command, principal, springProperties)
            } catch (_: RuntimeException) {
                OperatorPermissionBootstrapResult.DEPENDENCY_UNAVAILABLE
            }
        val output = if (result == OperatorPermissionBootstrapResult.APPLIED) standardOut else standardError
        writeResult(output, parsed.command.action.name, parsed.command.permission.name, result)
        return when (result) {
            OperatorPermissionBootstrapResult.APPLIED -> 0
            OperatorPermissionBootstrapResult.INVALID_INPUT -> 2
            OperatorPermissionBootstrapResult.IDENTITY_VERIFICATION_FAILED -> 3
            OperatorPermissionBootstrapResult.GRANT_STATE_CONFLICT -> 4
            OperatorPermissionBootstrapResult.DEPENDENCY_UNAVAILABLE -> 5
        }
    }

    private fun applyWithSpring(
        command: OperatorPermissionBootstrapCommand,
        principal: VerifiedReleasePrincipal,
        springProperties: Map<String, Any>,
    ): OperatorPermissionBootstrapResult {
        val application: SpringApplication =
            SpringApplicationBuilder(OperatorPermissionBootstrapApplication::class.java)
                .profiles("operator-permission-bootstrap")
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
            context.getBean(OperatorPermissionGrantLifecycle::class.java).apply(command, principal)
        } finally {
            context.close()
        }
    }

    private fun writeResult(
        output: PrintStream,
        action: String,
        permission: String,
        result: OperatorPermissionBootstrapResult,
    ) {
        output.println(
            "action=$action permission=$permission principal=verified-release-principal result=${result.name}",
        )
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
