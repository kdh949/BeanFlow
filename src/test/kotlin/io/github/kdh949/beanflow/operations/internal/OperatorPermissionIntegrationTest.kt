package io.github.kdh949.beanflow.operations.internal

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.zaxxer.hikari.HikariDataSource
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class OperatorPermissionIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val lifecycle: OperatorPermissionGrantLifecycle,
        private val authorization: OperatorPermissionAuthorizationService,
        private val dataSource: HikariDataSource,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactionTemplate = TransactionTemplate(transactionManager)

        @TempDir
        lateinit var tempDirectory: Path

        private val actorId = UUID.fromString("20000000-0000-0000-0000-000000000001")
        private val now = Instant.parse("2026-08-01T12:00:00Z")
        private val principal =
            VerifiedReleasePrincipal("issuer=https://issuer.example|subject=release-job|audience=beanflow|deploymentRun=run-1")

        @BeforeEach
        fun cleanSecurityState() {
            dropAuditFailureTrigger()
            jdbcTemplate.execute("TRUNCATE TABLE operations_operator_permission_grant, operations_audit_record")
        }

        @AfterEach
        fun cleanupFailureTrigger() = dropAuditFailureTrigger()

        @Test
        fun `role and active grant are both required and revoke regrant is immediate`() {
            val endpoint = "/api/v1/operations/policies/expired-benefit-restoration"

            mockMvc
                .perform(
                    get(endpoint)
                        .with(operatorJwt(actorId, includePermissionClaim = true))
                        .header("X-Access-Reason", "Role-only attempt"),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            mockMvc
                .perform(
                    get(endpoint)
                        .with(operatorJwt("not-a-uuid"))
                        .header("X-Access-Reason", "Malformed subject"),
                ).andExpect(status().isForbidden)

            assertThat(apply(OperatorPermissionBootstrapAction.GRANT)).isEqualTo(OperatorPermissionBootstrapResult.APPLIED)
            assertThat(apply(OperatorPermissionBootstrapAction.GRANT))
                .isEqualTo(OperatorPermissionBootstrapResult.GRANT_STATE_CONFLICT)

            mockMvc
                .perform(
                    get(endpoint)
                        .with(jwt().jwt { it.subject(actorId.toString()).claim("roles", emptyList<String>()) })
                        .header("X-Access-Reason", "Grant without role"),
                ).andExpect(status().isForbidden)
            mockMvc
                .perform(
                    get(endpoint)
                        .with(operatorJwt(actorId))
                        .header("X-Access-Reason", "Authorized support read"),
                ).andExpect(status().isOk)

            assertThat(apply(OperatorPermissionBootstrapAction.REVOKE)).isEqualTo(OperatorPermissionBootstrapResult.APPLIED)
            assertThat(apply(OperatorPermissionBootstrapAction.REVOKE))
                .isEqualTo(OperatorPermissionBootstrapResult.GRANT_STATE_CONFLICT)
            mockMvc
                .perform(
                    get(endpoint)
                        .with(operatorJwt(actorId))
                        .header("X-Access-Reason", "Revoked attempt"),
                ).andExpect(status().isForbidden)

            assertThat(apply(OperatorPermissionBootstrapAction.REGRANT)).isEqualTo(OperatorPermissionBootstrapResult.APPLIED)
            mockMvc
                .perform(
                    get(endpoint)
                        .with(operatorJwt(actorId))
                        .header("X-Access-Reason", "Regranted read"),
                ).andExpect(status().isOk)

            val state =
                jdbcTemplate.queryForMap(
                    """
                    SELECT state, version, revoked_at
                      FROM operations_operator_permission_grant
                     WHERE actor_id = ? AND permission = 'EXPIRED_BENEFIT_POLICY_READ'
                    """.trimIndent(),
                    actorId,
                )
            assertThat(state["state"]).isEqualTo("ACTIVE")
            assertThat(state["version"]).isEqualTo(3L)
            assertThat(state["revoked_at"]).isNull()
        }

        @Test
        fun `closed permission vocabulary rejects direct unsupported values`() {
            assertThatThrownBy {
                jdbcTemplate.update(
                    """
                    INSERT INTO operations_operator_permission_grant (
                        actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference
                    ) VALUES (?, 'UNDECLARED_PERMISSION', 'ACTIVE', now(), null, 1, ?)
                    """.trimIndent(),
                    actorId,
                    "test:${UUID.randomUUID()}",
                )
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        @Test
        fun `grant transaction rolls back when audit persistence fails`() {
            installAuditFailureTrigger()

            assertThat(apply(OperatorPermissionBootstrapAction.GRANT))
                .isEqualTo(OperatorPermissionBootstrapResult.DEPENDENCY_UNAVAILABLE)
            assertThat(count("operations_operator_permission_grant")).isZero()
            assertThat(count("operations_audit_record")).isZero()
        }

        @Test
        fun `revoke waits for an authorized transaction and later reads fail closed`() {
            assertThat(apply(OperatorPermissionBootstrapAction.GRANT)).isEqualTo(OperatorPermissionBootstrapResult.APPLIED)
            val authorizationLocked = CountDownLatch(1)
            val releaseAuthorization = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val authorizedTransaction =
                    executor.submit {
                        transactionTemplate.executeWithoutResult {
                            authorization.requireActive(actorId, OperatorPermission.EXPIRED_BENEFIT_POLICY_READ)
                            authorizationLocked.countDown()
                            check(releaseAuthorization.await(5, TimeUnit.SECONDS))
                        }
                    }
                assertThat(authorizationLocked.await(5, TimeUnit.SECONDS)).isTrue()

                val revoke =
                    executor.submit<OperatorPermissionBootstrapResult> {
                        apply(OperatorPermissionBootstrapAction.REVOKE)
                    }
                assertThatThrownBy { revoke.get(250, TimeUnit.MILLISECONDS) }
                    .isInstanceOf(TimeoutException::class.java)

                releaseAuthorization.countDown()
                authorizedTransaction.get(5, TimeUnit.SECONDS)
                assertThat(revoke.get(5, TimeUnit.SECONDS)).isEqualTo(OperatorPermissionBootstrapResult.APPLIED)

                mockMvc
                    .perform(
                        get("/api/v1/operations/policies/expired-benefit-restoration")
                            .with(operatorJwt(actorId))
                            .header("X-Access-Reason", "Post-revoke read"),
                    ).andExpect(status().isForbidden)
            } finally {
                releaseAuthorization.countDown()
                executor.shutdownNow()
            }
        }

        @Test
        fun `OIDC verifier rejects issuer audience subject time and token file before any grant write`() {
            val rsaKey = RSAKeyGenerator(2048).keyID("bootstrap-test").generate()
            val verifier = OidcWorkloadIdentityVerifier(Clock.fixed(now, ZoneOffset.UTC))
            val valid = filesFor(rsaKey, token(rsaKey))
            assertThat(verifier.verify(valid).reference)
                .contains("subject=release-job", "deploymentRun=run-1")

            val untrustedSigningKey = RSAKeyGenerator(2048).keyID("untrusted-bootstrap-key").generate()
            val invalidConfigurations =
                listOf(
                    filesFor(rsaKey, token(untrustedSigningKey)),
                    filesFor(rsaKey, token(rsaKey, issuer = "https://wrong-issuer.example")),
                    filesFor(rsaKey, token(rsaKey, audience = "wrong-audience")),
                    filesFor(rsaKey, token(rsaKey, subject = "untrusted-job")),
                    filesFor(rsaKey, token(rsaKey, expiresAt = now)),
                    filesFor(rsaKey, token(rsaKey, notBefore = now.plusSeconds(1))),
                )
            invalidConfigurations.forEach { configuration ->
                assertThatThrownBy { verifier.verify(configuration) }
                    .isInstanceOf(WorkloadIdentityVerificationException::class.java)
            }

            val writableToken = filesFor(rsaKey, token(rsaKey))
            Files.setPosixFilePermissions(
                writableToken.tokenFile,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            assertThatThrownBy { verifier.verify(writableToken) }
                .isInstanceOf(WorkloadIdentityVerificationException::class.java)

            val missingToken = valid.copy(tokenFile = tempDirectory.resolve("missing-token"))
            assertThatThrownBy { verifier.verify(missingToken) }
                .isInstanceOf(WorkloadIdentityVerificationException::class.java)
            assertThat(count("operations_operator_permission_grant")).isZero()
            assertThat(count("operations_audit_record")).isZero()
        }

        @Test
        fun `bootstrap CLI returns nonzero without invoking transaction or exposing sensitive inputs`() {
            val rsaKey = RSAKeyGenerator(2048).keyID("bootstrap-cli-test").generate()
            val invalid = filesFor(rsaKey, token(rsaKey, audience = "wrong-audience"))
            val environment =
                environment(invalid) +
                    mapOf(
                        "BEANFLOW_OPERATOR_BOOTSTRAP_REASON" to "sensitive operator reason",
                        "BEANFLOW_OPERATOR_BOOTSTRAP_EVIDENCE_REFERENCE" to "evidence://sensitive-reference",
                    )
            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()
            val transactionCalls = AtomicInteger()

            val exitCode =
                OperatorPermissionBootstrapCli.execute(
                    emptyArray(),
                    environment,
                    PrintStream(output),
                    PrintStream(error),
                ) { _, _ ->
                    transactionCalls.incrementAndGet()
                    OperatorPermissionBootstrapResult.APPLIED
                }

            assertThat(exitCode).isEqualTo(3)
            assertThat(transactionCalls).hasValue(0)
            assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty()
            assertThat(error.toString(StandardCharsets.UTF_8))
                .contains("result=IDENTITY_VERIFICATION_FAILED")
                .doesNotContain(
                    "sensitive operator reason",
                    "evidence://sensitive-reference",
                    Files.readString(invalid.tokenFile),
                    invalid.tokenFile.toString(),
                )
            assertThat(count("operations_operator_permission_grant")).isZero()
        }

        @Test
        fun `bootstrap CLI exits zero only for an applied verified command`() {
            val rsaKey = RSAKeyGenerator(2048).keyID("bootstrap-cli-success-test").generate()
            val systemNow = Instant.now()
            val valid =
                filesFor(
                    rsaKey,
                    token(
                        rsaKey,
                        expiresAt = systemNow.plusSeconds(300),
                        notBefore = systemNow.minusSeconds(1),
                    ),
                )
            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()

            val exitCode =
                OperatorPermissionBootstrapCli.execute(
                    emptyArray(),
                    environment(valid),
                    PrintStream(output),
                    PrintStream(error),
                    springProperties =
                        mapOf(
                            "BEANFLOW_DB_URL" to dataSource.jdbcUrl,
                            "BEANFLOW_DB_USERNAME" to dataSource.username,
                            "BEANFLOW_DB_PASSWORD" to dataSource.password,
                        ),
                )

            assertThat(exitCode).isZero()
            assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty()
            assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("result=APPLIED")
                .doesNotContain(
                    "bootstrap test",
                    "evidence://run-1",
                    Files.readString(valid.tokenFile),
                    valid.tokenFile.toString(),
                )
            assertThat(count("operations_operator_permission_grant")).isEqualTo(1)
            assertThat(count("operations_audit_record")).isEqualTo(1)
            val audit =
                jdbcTemplate.queryForMap(
                    """
                    SELECT actor_id, reason, before_summary, after_summary
                      FROM operations_audit_record
                     WHERE action = 'OPERATOR_PERMISSION_GRANTED'
                    """.trimIndent(),
                )
            assertThat(audit["reason"]).isEqualTo("VERIFIED_RELEASE_OPERATOR_PERMISSION_CHANGE")
            assertThat(audit.values.joinToString("|"))
                .doesNotContain("bootstrap test", Files.readString(valid.tokenFile))
        }

        private fun apply(action: OperatorPermissionBootstrapAction): OperatorPermissionBootstrapResult =
            lifecycle.apply(
                OperatorPermissionBootstrapCommand(
                    action = action,
                    actorId = actorId,
                    permission = OperatorPermission.EXPIRED_BENEFIT_POLICY_READ,
                    reason = "Lifecycle test",
                    evidenceReference = "evidence://run-1",
                    correlationId = UUID.randomUUID().toString(),
                    now = now.plusSeconds(action.ordinal.toLong()),
                ),
                principal,
            )

        private fun operatorJwt(
            actor: UUID,
            includePermissionClaim: Boolean = false,
        ) = operatorJwt(actor.toString(), includePermissionClaim)

        private fun operatorJwt(
            subject: String,
            includePermissionClaim: Boolean = false,
        ) = jwt()
            .jwt {
                it
                    .subject(subject)
                    .claim("roles", listOf("PLATFORM_OPERATOR"))
                if (includePermissionClaim) {
                    it.claim("permissions", listOf("EXPIRED_BENEFIT_POLICY_READ"))
                }
            }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private fun token(
            rsaKey: RSAKey,
            issuer: String = "https://issuer.example",
            audience: String = "beanflow-bootstrap",
            subject: String = "release-job",
            expiresAt: Instant = now.plusSeconds(300),
            notBefore: Instant = now.minusSeconds(1),
        ): String {
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .expirationTime(Date.from(expiresAt))
                    .notBeforeTime(Date.from(notBefore))
                    .claim("run_id", "run-1")
                    .build()
            return SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(),
                claims,
            ).also { it.sign(RSASSASigner(rsaKey)) }.serialize()
        }

        private fun filesFor(
            rsaKey: RSAKey,
            token: String,
        ): OidcWorkloadIdentityConfiguration {
            val directory = Files.createTempDirectory(tempDirectory, "identity-")
            val tokenFile = directory.resolve("token.jwt")
            val jwkSetFile = directory.resolve("jwks.json")
            Files.writeString(tokenFile, token)
            Files.writeString(jwkSetFile, JWKSet(rsaKey.toPublicJWK()).toString())
            val readOnly = setOf(PosixFilePermission.OWNER_READ)
            Files.setPosixFilePermissions(tokenFile, readOnly)
            Files.setPosixFilePermissions(jwkSetFile, readOnly)
            return OidcWorkloadIdentityConfiguration(
                tokenFile = tokenFile,
                jwkSetFile = jwkSetFile,
                issuer = "https://issuer.example",
                audience = "beanflow-bootstrap",
                allowedSubjects = setOf("release-job"),
                deploymentRunClaim = "run_id",
            )
        }

        private fun environment(configuration: OidcWorkloadIdentityConfiguration): Map<String, String> =
            mapOf(
                "BEANFLOW_OPERATOR_BOOTSTRAP_ACTION" to "grant",
                "BEANFLOW_OPERATOR_BOOTSTRAP_ACTOR_ID" to actorId.toString(),
                "BEANFLOW_OPERATOR_BOOTSTRAP_PERMISSION" to "EXPIRED_BENEFIT_POLICY_READ",
                "BEANFLOW_OPERATOR_BOOTSTRAP_REASON" to "bootstrap test",
                "BEANFLOW_OPERATOR_BOOTSTRAP_EVIDENCE_REFERENCE" to "evidence://run-1",
                "BEANFLOW_OPERATOR_BOOTSTRAP_CORRELATION_ID" to UUID.randomUUID().toString(),
                "BEANFLOW_OPERATOR_BOOTSTRAP_TOKEN_FILE" to configuration.tokenFile.toString(),
                "BEANFLOW_OPERATOR_BOOTSTRAP_JWK_SET_FILE" to configuration.jwkSetFile.toString(),
                "BEANFLOW_OPERATOR_BOOTSTRAP_ISSUER" to configuration.issuer,
                "BEANFLOW_OPERATOR_BOOTSTRAP_AUDIENCE" to configuration.audience,
                "BEANFLOW_OPERATOR_BOOTSTRAP_ALLOWED_SUBJECTS" to configuration.allowedSubjects.joinToString(","),
                "BEANFLOW_OPERATOR_BOOTSTRAP_DEPLOYMENT_RUN_CLAIM" to configuration.deploymentRunClaim,
            )

        private fun count(table: String): Long =
            requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java))

        private fun installAuditFailureTrigger() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_reject_grant_audit()
                RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                    IF NEW.action LIKE 'OPERATOR_PERMISSION_%' THEN
                        RAISE EXCEPTION 'injected grant audit failure';
                    END IF;
                    RETURN NEW;
                END
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER test_reject_grant_audit
                BEFORE INSERT ON operations_audit_record
                FOR EACH ROW EXECUTE FUNCTION test_reject_grant_audit()
                """.trimIndent(),
            )
        }

        private fun dropAuditFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_reject_grant_audit ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_reject_grant_audit()")
        }
    }
