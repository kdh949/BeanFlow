package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.DeactivatePaymentMethodProviderCommand
import io.github.kdh949.beanflow.payment.api.PaymentMethodDeactivationProvider
import io.github.kdh949.beanflow.payment.api.PaymentMethodDeactivationProviderResult
import io.github.kdh949.beanflow.payment.api.PaymentMethodRegistrationProvider
import io.github.kdh949.beanflow.payment.api.PaymentMethodRegistrationProviderResult
import io.github.kdh949.beanflow.payment.api.RegisterPaymentMethodProviderCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal data class PaymentMethodHttpResult(
    val status: Int,
    val body: String,
)

internal data class RegisterPaymentMethodCommand(
    val customerId: UUID,
    val idempotencyKey: String,
    val authorizationKey: String,
    val displayAlias: String,
)

@Service
internal class PaymentMethodApplicationService(
    private val transactions: PaymentMethodLifecycleTransactions,
    private val registrationProvider: PaymentMethodRegistrationProvider,
    private val deactivationProvider: PaymentMethodDeactivationProvider,
    private val metrics: PaymentMethodLifecycleMetrics,
    private val objectMapper: ObjectMapper,
) {
    fun register(command: RegisterPaymentMethodCommand): PaymentMethodHttpResult {
        val normalized = normalize(command)
        val prepared = transactions.prepareRegistration(normalized)
        if (prepared is RegistrationPreparation.Respond) return prepared.response

        val registrationId = (prepared as RegistrationPreparation.Claimable).registrationId
        val claim = transactions.claimRegistration(registrationId) ?: return transactions.registrationResponse(registrationId)
        val providerResult =
            registrationProvider
                .register(
                    RegisterPaymentMethodProviderCommand(
                        authorizationKey = command.authorizationKey,
                        providerCustomerReference = claim.providerCustomerReference,
                    ),
                ).validated()
        metrics.provider("REGISTER", providerResult.metricOutcome())
        return try {
            transactions.completeRegistration(claim, providerResult)
        } catch (_: DataAccessException) {
            transactions.markRegistrationManualAfterPersistenceFailure(claim)
        }
    }

    fun setDefault(
        customerId: UUID,
        paymentMethodId: UUID,
        idempotencyKey: String,
    ): PaymentMethodHttpResult = transactions.setDefault(customerId, paymentMethodId, idempotencyKey)

    fun deactivate(
        customerId: UUID,
        paymentMethodId: UUID,
        idempotencyKey: String,
    ): PaymentMethodHttpResult {
        val prepared = transactions.prepareDeactivation(customerId, paymentMethodId, idempotencyKey)
        if (prepared is DeactivationPreparation.Respond) return prepared.response

        val deactivationId = (prepared as DeactivationPreparation.Claimable).deactivationId
        val claim = transactions.claimDeactivation(deactivationId) ?: return transactions.deactivationResponse(deactivationId)
        val providerResult =
            deactivationProvider.deactivate(
                DeactivatePaymentMethodProviderCommand(
                    tokenReference = claim.tokenReference,
                    providerCustomerReference = claim.providerCustomerReference,
                ),
            )
        metrics.provider("DEACTIVATE", providerResult.metricOutcome())
        return try {
            transactions.completeDeactivation(claim, providerResult)
        } catch (_: DataAccessException) {
            transactions.markDeactivationUnknownAfterPersistenceFailure(claim)
        }
    }

    private fun normalize(command: RegisterPaymentMethodCommand): NormalizedRegistrationCommand {
        requireIdempotencyKey(command.idempotencyKey)
        if (command.authorizationKey.length !in 1..300) invalid("authorizationKey must contain 1 to 300 characters")
        val alias = command.displayAlias.trim()
        if (alias.length !in 1..80 || alias.any { it.isISOControl() }) {
            invalid("displayAlias must contain 1 to 80 non-control characters after trimming")
        }
        val authorizationHash = sha256(command.authorizationKey)
        val canonical =
            "{\"provider\":\"$PROVIDER\",\"authorizationKeyHash\":\"$authorizationHash\"," +
                "\"displayAlias\":${jsonString(alias)}}"
        return NormalizedRegistrationCommand(
            customerId = command.customerId,
            idempotencyKey = command.idempotencyKey,
            authorizationKeyHash = authorizationHash,
            payloadHash = sha256(canonical),
            displayAlias = alias,
        )
    }

    private fun PaymentMethodRegistrationProviderResult.validated(): PaymentMethodRegistrationProviderResult =
        if (this is PaymentMethodRegistrationProviderResult.Issued) {
            val brand = cardBrand.trim()
            if (
                tokenReference.isBlank() || tokenReference.length > 200 ||
                brand.length !in 1..40 || brand != cardBrand ||
                !LAST_FOUR.matches(lastFour)
            ) {
                PaymentMethodRegistrationProviderResult.Unknown
            } else {
                copy(cardBrand = brand)
            }
        } else {
            this
        }

    private fun jsonString(value: String): String = objectMapper.writeValueAsString(value)

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private companion object {
        const val PROVIDER = "TOSS_PAYMENTS"
        val LAST_FOUR = Regex("^[0-9]{4}${'$'}")
    }
}

internal sealed interface RegistrationPreparation {
    data class Respond(
        val response: PaymentMethodHttpResult,
    ) : RegistrationPreparation

    data class Claimable(
        val registrationId: UUID,
    ) : RegistrationPreparation
}

internal data class RegistrationClaim(
    val registrationId: UUID,
    val claimToken: UUID,
    val providerCustomerReference: String,
)

internal sealed interface DeactivationPreparation {
    data class Respond(
        val response: PaymentMethodHttpResult,
    ) : DeactivationPreparation

    data class Claimable(
        val deactivationId: UUID,
    ) : DeactivationPreparation
}

internal data class DeactivationClaim(
    val deactivationId: UUID,
    val claimToken: UUID,
    val paymentMethodId: UUID,
    val tokenReference: String,
    val providerCustomerReference: String,
)

internal data class NormalizedRegistrationCommand(
    val customerId: UUID,
    val idempotencyKey: String,
    val authorizationKeyHash: String,
    val payloadHash: String,
    val displayAlias: String,
)

@Service
internal class PaymentMethodLifecycleTransactions(
    private val registrations: PaymentMethodRegistrationJpaRepository,
    private val defaultCommands: PaymentMethodDefaultCommandJpaRepository,
    private val deactivations: PaymentMethodDeactivationJpaRepository,
    private val methods: PaymentMethodJpaRepository,
    private val identifiers: IdentifierSource,
    private val providerReferences: PaymentMethodProviderReferenceSource,
    private val correlationIds: CorrelationIdSource,
    private val objectMapper: ObjectMapper,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
    private val metrics: PaymentMethodLifecycleMetrics,
    private val audits: PaymentMethodAuditWriter,
) {
    @Transactional
    fun prepareRegistration(command: NormalizedRegistrationCommand): RegistrationPreparation {
        advisoryLock("registration-key:${command.customerId}:${command.idempotencyKey}")
        val existing =
            registrations.findByActorIdAndOperationAndIdempotencyKey(
                command.customerId,
                REGISTER_OPERATION,
                command.idempotencyKey,
            )
        if (existing != null) {
            if (existing.payloadHash != command.payloadHash) return RegistrationPreparation.Respond(idempotencyReused())
            return if (existing.status in
                setOf(PaymentMethodRegistrationStatus.READY, PaymentMethodRegistrationStatus.MISCONFIGURED_RETRYABLE)
            ) {
                RegistrationPreparation.Claimable(existing.id)
            } else {
                RegistrationPreparation.Respond(stored(existing))
            }
        }

        advisoryLock("registration-auth:${command.customerId}:${command.authorizationKeyHash}")
        if (
            registrations.findByCustomerIdAndProviderAndAuthorizationKeyHash(
                command.customerId,
                PROVIDER,
                command.authorizationKeyHash,
            ) != null
        ) {
            return RegistrationPreparation.Respond(error(409, FailureCode.PAYMENT_METHOD_AUTHORIZATION_REUSED))
        }

        val now = clock.instant()
        val methodId = identifiers.next()
        val pending = pendingRegistration(methodId, correlationIds.currentOrCreate(), now, notice = false)
        val registration =
            PaymentMethodRegistrationEntity(
                id = identifiers.next(),
                actorId = command.customerId,
                operation = REGISTER_OPERATION,
                idempotencyKey = command.idempotencyKey,
                customerId = command.customerId,
                intendedPaymentMethodId = methodId,
                provider = PROVIDER,
                authorizationKeyHash = command.authorizationKeyHash,
                payloadHash = command.payloadHash,
                displayAlias = command.displayAlias,
                providerCustomerReference = providerReferences.next(),
                status = PaymentMethodRegistrationStatus.READY,
                firstResponseStatus = pending.status,
                firstResponseBody = pending.body,
                startedAt = now,
                updatedAt = now,
            )
        registrations.saveAndFlush(registration)
        metrics.command("REGISTER", "READY")
        return RegistrationPreparation.Claimable(registration.id)
    }

    @Transactional
    fun claimRegistration(registrationId: UUID): RegistrationClaim? {
        val registration = registrations.findLockedById(registrationId) ?: return null
        if (registration.status !in setOf(PaymentMethodRegistrationStatus.READY, PaymentMethodRegistrationStatus.MISCONFIGURED_RETRYABLE)) {
            return null
        }
        val now = clock.instant()
        val token = identifiers.next()
        registration.status = PaymentMethodRegistrationStatus.PROCESSING
        registration.claimToken = token
        registration.claimStartedAt = now
        registration.updatedAt = now
        return RegistrationClaim(registration.id, token, registration.providerCustomerReference)
    }

    @Transactional(readOnly = true)
    fun registrationResponse(registrationId: UUID): PaymentMethodHttpResult =
        stored(checkNotNull(registrations.findById(registrationId).orElse(null)))

    @Transactional
    fun completeRegistration(
        claim: RegistrationClaim,
        result: PaymentMethodRegistrationProviderResult,
    ): PaymentMethodHttpResult {
        val registration = registrations.findLockedById(claim.registrationId) ?: unavailable()
        requireClaim(registration.status == PaymentMethodRegistrationStatus.PROCESSING, registration.claimToken, claim.claimToken)
        val now = clock.instant()
        val correlationId = correlationFrom(registration)
        val response =
            when (result) {
                is PaymentMethodRegistrationProviderResult.Issued -> {
                    completeIssued(registration, result, now)
                }

                PaymentMethodRegistrationProviderResult.RejectedWithoutEffect -> {
                    registration.status = PaymentMethodRegistrationStatus.REJECTED
                    registration.terminalAt = now
                    registration.retentionExpiresAt = now.plus(RETENTION)
                    error(422, FailureCode.PAYMENT_METHOD_REGISTRATION_REJECTED)
                }

                PaymentMethodRegistrationProviderResult.Unknown -> {
                    registration.status = PaymentMethodRegistrationStatus.MANUAL_REVIEW
                    registration.manualReviewReason = "PROVIDER_RESULT_UNKNOWN"
                    pendingRegistration(
                        registration.intendedPaymentMethodId,
                        correlationFrom(registration),
                        now,
                        notice = true,
                    )
                }

                PaymentMethodRegistrationProviderResult.Misconfigured -> {
                    registration.status = PaymentMethodRegistrationStatus.MISCONFIGURED_RETRYABLE
                    registration.claimToken = null
                    registration.claimStartedAt = null
                    error(503, FailureCode.PAYMENT_METHOD_PROVIDER_UNAVAILABLE)
                }
            }
        registration.firstResponseStatus = response.status
        registration.firstResponseBody = response.body
        registration.updatedAt = now
        audits.customer(
            actorId = registration.customerId,
            action = "PAYMENT_METHOD_REGISTRATION_${registration.status.name}",
            targetType = "PAYMENT_METHOD_REGISTRATION",
            targetId = registration.intendedPaymentMethodId,
            occurredAt = now,
            beforeState = "PROCESSING",
            afterState = registration.status.name,
            sourceReference = "payment-method-registration:${registration.id}:${claim.claimToken}",
            correlationId = correlationId,
        )
        metrics.command("REGISTER", registration.status.name)
        return response
    }

    @Transactional
    fun markRegistrationManualAfterPersistenceFailure(claim: RegistrationClaim): PaymentMethodHttpResult {
        val registration = registrations.findLockedById(claim.registrationId) ?: unavailable()
        if (registration.status == PaymentMethodRegistrationStatus.PROCESSING && registration.claimToken == claim.claimToken) {
            val now = clock.instant()
            registration.status = PaymentMethodRegistrationStatus.MANUAL_REVIEW
            registration.manualReviewReason = "PROVIDER_RESULT_PERSISTENCE_FAILED"
            registration.updatedAt = now
            val response =
                pendingRegistration(
                    registration.intendedPaymentMethodId,
                    correlationFrom(registration),
                    now,
                    notice = true,
                )
            registration.firstResponseStatus = response.status
            registration.firstResponseBody = response.body
            audits.customer(
                actorId = registration.customerId,
                action = "PAYMENT_METHOD_REGISTRATION_MANUAL_REVIEW",
                targetType = "PAYMENT_METHOD_REGISTRATION",
                targetId = registration.intendedPaymentMethodId,
                occurredAt = now,
                beforeState = "PROCESSING",
                afterState = "MANUAL_REVIEW",
                sourceReference = "payment-method-registration:${registration.id}:${claim.claimToken}:persistence",
                correlationId = correlationFrom(registration),
            )
            return response
        }
        return stored(registration)
    }

    @Transactional
    fun setDefault(
        customerId: UUID,
        paymentMethodId: UUID,
        idempotencyKey: String,
    ): PaymentMethodHttpResult {
        requireIdempotencyKey(idempotencyKey)
        val payloadHash = sha256("{\"paymentMethodId\":\"$paymentMethodId\"}")
        advisoryLock("default-key:$customerId:$idempotencyKey")
        defaultCommands.findByActorIdAndOperationAndIdempotencyKey(customerId, DEFAULT_OPERATION, idempotencyKey)?.let {
            return if (it.payloadHash == payloadHash) {
                PaymentMethodHttpResult(it.firstResponseStatus, it.firstResponseBody)
            } else {
                idempotencyReused()
            }
        }

        advisoryLock("payment-method-customer:$customerId")
        val target = lifecycleTarget(customerId, paymentMethodId)
        if (target.status != PaymentMethodStatus.ACTIVE) return error(409, FailureCode.PAYMENT_METHOD_STATE_CONFLICT)
        val now = clock.instant()
        val ownedMethods = methods.findAllLockedByCustomerId(customerId)
        ownedMethods.filter { it.id != target.id && it.isDefault }.forEach { it.clearDefault(now) }
        methods.flush()
        target.markDefault(now)
        val response = PaymentMethodHttpResult(200, paymentMethodBody(target))
        val defaultCommandId = identifiers.next()
        defaultCommands.saveAndFlush(
            PaymentMethodDefaultCommandEntity(
                id = defaultCommandId,
                actorId = customerId,
                operation = DEFAULT_OPERATION,
                idempotencyKey = idempotencyKey,
                customerId = customerId,
                paymentMethodId = paymentMethodId,
                payloadHash = payloadHash,
                firstResponseStatus = response.status,
                firstResponseBody = response.body,
                startedAt = now,
                terminalAt = now,
                retentionExpiresAt = now.plus(RETENTION),
            ),
        )
        audits.customer(
            actorId = customerId,
            action = "PAYMENT_METHOD_DEFAULT_SET",
            targetType = "PAYMENT_METHOD",
            targetId = paymentMethodId,
            occurredAt = now,
            beforeState = "ACTIVE",
            afterState = "ACTIVE_DEFAULT",
            sourceReference = "payment-method-default:$defaultCommandId",
        )
        metrics.command("SET_DEFAULT", "COMPLETED")
        return response
    }

    @Transactional
    fun prepareDeactivation(
        customerId: UUID,
        paymentMethodId: UUID,
        idempotencyKey: String,
    ): DeactivationPreparation {
        requireIdempotencyKey(idempotencyKey)
        val payloadHash = sha256("{\"paymentMethodId\":\"$paymentMethodId\"}")
        advisoryLock("deactivation-key:$customerId:$idempotencyKey")
        deactivations.findByActorIdAndOperationAndIdempotencyKey(customerId, DEACTIVATE_OPERATION, idempotencyKey)?.let {
            if (it.payloadHash != payloadHash) return DeactivationPreparation.Respond(idempotencyReused())
            return if (it.status == PaymentMethodDeactivationStatus.READY) {
                DeactivationPreparation.Claimable(it.id)
            } else {
                DeactivationPreparation.Respond(stored(it))
            }
        }

        val method = lifecycleTarget(customerId, paymentMethodId, lock = true)
        if (method.status != PaymentMethodStatus.ACTIVE) {
            return DeactivationPreparation.Respond(error(409, FailureCode.PAYMENT_METHOD_STATE_CONFLICT))
        }
        val now = clock.instant()
        method.requestDeactivation(now)
        val response = pendingDeactivation(paymentMethodId, correlationIds.currentOrCreate(), now, notice = false)
        val work =
            PaymentMethodDeactivationEntity(
                id = identifiers.next(),
                actorId = customerId,
                operation = DEACTIVATE_OPERATION,
                idempotencyKey = idempotencyKey,
                customerId = customerId,
                paymentMethodId = paymentMethodId,
                payloadHash = payloadHash,
                status = PaymentMethodDeactivationStatus.READY,
                firstResponseStatus = response.status,
                firstResponseBody = response.body,
                startedAt = now,
                updatedAt = now,
            )
        deactivations.saveAndFlush(work)
        audits.customer(
            actorId = customerId,
            action = "PAYMENT_METHOD_DEACTIVATION_REQUESTED",
            targetType = "PAYMENT_METHOD",
            targetId = paymentMethodId,
            occurredAt = now,
            beforeState = "ACTIVE",
            afterState = "DEACTIVATION_REQUESTED",
            sourceReference = "payment-method-deactivation:${work.id}:requested",
            correlationId = correlationFrom(work),
        )
        metrics.command("DEACTIVATE", "READY")
        return DeactivationPreparation.Claimable(work.id)
    }

    @Transactional
    fun claimDeactivation(deactivationId: UUID): DeactivationClaim? {
        val work = deactivations.findLockedById(deactivationId) ?: return null
        if (work.status != PaymentMethodDeactivationStatus.READY) return null
        val method = methods.findLockedById(work.paymentMethodId) ?: unavailable()
        val providerReference = method.providerCustomerReference ?: unavailable()
        val now = clock.instant()
        val token = identifiers.next()
        work.status = PaymentMethodDeactivationStatus.PROCESSING
        work.claimToken = token
        work.claimStartedAt = now
        work.updatedAt = now
        return DeactivationClaim(work.id, token, method.id, method.tokenReference, providerReference)
    }

    @Transactional(readOnly = true)
    fun deactivationResponse(deactivationId: UUID): PaymentMethodHttpResult =
        stored(checkNotNull(deactivations.findById(deactivationId).orElse(null)))

    @Transactional
    fun completeDeactivation(
        claim: DeactivationClaim,
        result: PaymentMethodDeactivationProviderResult,
    ): PaymentMethodHttpResult {
        val work = deactivations.findLockedById(claim.deactivationId) ?: unavailable()
        requireClaim(work.status == PaymentMethodDeactivationStatus.PROCESSING, work.claimToken, claim.claimToken)
        val method = methods.findLockedById(claim.paymentMethodId) ?: unavailable()
        val now = clock.instant()
        val correlationId = correlationFrom(work)
        val response =
            when (result) {
                PaymentMethodDeactivationProviderResult.Deactivated -> {
                    method.confirmDeactivated(now)
                    work.status = PaymentMethodDeactivationStatus.COMPLETED
                    work.terminalAt = now
                    work.retentionExpiresAt = now.plus(RETENTION)
                    PaymentMethodHttpResult(204, "")
                }

                PaymentMethodDeactivationProviderResult.Unknown -> {
                    markUnknown(method, work, now)
                }

                PaymentMethodDeactivationProviderResult.RejectedWithoutEffect -> {
                    markManual(method, work, now, "PROVIDER_REJECTED_WITHOUT_EFFECT")
                }

                PaymentMethodDeactivationProviderResult.Misconfigured -> {
                    markManual(method, work, now, "PROVIDER_MISCONFIGURED")
                }
            }
        work.firstResponseStatus = response.status
        work.firstResponseBody = response.body
        work.updatedAt = now
        audits.customer(
            actorId = work.customerId,
            action = "PAYMENT_METHOD_DEACTIVATION_${work.status.name}",
            targetType = "PAYMENT_METHOD",
            targetId = work.paymentMethodId,
            occurredAt = now,
            beforeState = "DEACTIVATION_REQUESTED",
            afterState = method.status.name,
            sourceReference = "payment-method-deactivation:${work.id}:result",
            correlationId = correlationId,
        )
        metrics.command("DEACTIVATE", work.status.name)
        return response
    }

    @Transactional
    fun markDeactivationUnknownAfterPersistenceFailure(claim: DeactivationClaim): PaymentMethodHttpResult {
        val work = deactivations.findLockedById(claim.deactivationId) ?: unavailable()
        val method = methods.findLockedById(claim.paymentMethodId) ?: unavailable()
        if (work.status == PaymentMethodDeactivationStatus.PROCESSING && work.claimToken == claim.claimToken) {
            val now = clock.instant()
            val response = markUnknown(method, work, now)
            work.firstResponseStatus = response.status
            work.firstResponseBody = response.body
            work.updatedAt = now
            audits.customer(
                actorId = work.customerId,
                action = "PAYMENT_METHOD_DEACTIVATION_UNKNOWN",
                targetType = "PAYMENT_METHOD",
                targetId = work.paymentMethodId,
                occurredAt = now,
                beforeState = "DEACTIVATION_REQUESTED",
                afterState = method.status.name,
                sourceReference = "payment-method-deactivation:${work.id}:persistence",
                correlationId = correlationFrom(work),
            )
            return response
        }
        return stored(work)
    }

    private fun completeIssued(
        registration: PaymentMethodRegistrationEntity,
        issued: PaymentMethodRegistrationProviderResult.Issued,
        now: Instant,
    ): PaymentMethodHttpResult {
        advisoryLock("payment-method-token:${sha256("${registration.provider}:${issued.tokenReference}")}")
        val bindings = methods.findAllByProviderAndTokenReference(registration.provider, issued.tokenReference)
        val exact =
            bindings.singleOrNull()?.takeIf {
                it.customerId == registration.customerId &&
                    it.providerCustomerReference == registration.providerCustomerReference &&
                    it.displayAlias == registration.displayAlias &&
                    it.cardBrand == issued.cardBrand &&
                    it.lastFour == issued.lastFour &&
                    it.status == PaymentMethodStatus.ACTIVE
            }
        val response =
            when {
                exact != null -> {
                    PaymentMethodHttpResult(200, paymentMethodBody(exact))
                }

                bindings.isEmpty() -> {
                    val method =
                        PaymentMethodEntity.issueToss(
                            id = registration.intendedPaymentMethodId,
                            customerId = registration.customerId,
                            tokenReference = issued.tokenReference,
                            providerCustomerReference = registration.providerCustomerReference,
                            displayAlias = registration.displayAlias,
                            cardBrand = issued.cardBrand,
                            lastFour = issued.lastFour,
                            now = now,
                        )
                    methods.saveAndFlush(method)
                    PaymentMethodHttpResult(201, paymentMethodBody(method))
                }

                else -> {
                    registration.status = PaymentMethodRegistrationStatus.MANUAL_REVIEW
                    registration.manualReviewReason = "TOKEN_BINDING_CONFLICT"
                    return error(409, FailureCode.PAYMENT_METHOD_TOKEN_CONFLICT)
                }
            }
        registration.status = PaymentMethodRegistrationStatus.COMPLETED
        registration.terminalAt = now
        registration.retentionExpiresAt = now.plus(RETENTION)
        return response
    }

    private fun lifecycleTarget(
        customerId: UUID,
        paymentMethodId: UUID,
        lock: Boolean = false,
    ): PaymentMethodEntity {
        val method = if (lock) methods.findLockedById(paymentMethodId) else methods.findById(paymentMethodId).orElse(null)
        if (method == null ||
            method.provider != PROVIDER
        ) {
            throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Payment method was not found")
        }
        if (method.customerId != customerId) throw DomainFailure(FailureCode.ACCESS_DENIED, "Payment method belongs to another customer")
        return method
    }

    private fun markUnknown(
        method: PaymentMethodEntity,
        work: PaymentMethodDeactivationEntity,
        now: Instant,
    ): PaymentMethodHttpResult {
        method.markDeactivationUnknown(now)
        work.status = PaymentMethodDeactivationStatus.DEACTIVATION_UNKNOWN
        work.unknownAt = now
        work.manualReviewAt = now.plus(DEACTIVATION_WINDOW)
        return pendingDeactivation(method.id, correlationFrom(work), now, notice = false)
    }

    private fun markManual(
        method: PaymentMethodEntity,
        work: PaymentMethodDeactivationEntity,
        now: Instant,
        reason: String,
    ): PaymentMethodHttpResult {
        method.markManualReview(now)
        work.status = PaymentMethodDeactivationStatus.MANUAL_REVIEW
        work.unknownAt = now
        work.manualReviewAt = now.plus(DEACTIVATION_WINDOW)
        work.manualReviewReason = reason
        return pendingDeactivation(method.id, correlationFrom(work), now, notice = true)
    }

    private fun stored(registration: PaymentMethodRegistrationEntity): PaymentMethodHttpResult =
        PaymentMethodHttpResult(
            checkNotNull(registration.firstResponseStatus),
            checkNotNull(registration.firstResponseBody),
        )

    private fun stored(deactivation: PaymentMethodDeactivationEntity): PaymentMethodHttpResult =
        PaymentMethodHttpResult(
            checkNotNull(deactivation.firstResponseStatus),
            checkNotNull(deactivation.firstResponseBody),
        )

    private fun correlationFrom(registration: PaymentMethodRegistrationEntity): String =
        checkNotNull(
            objectMapper.readTree(checkNotNull(registration.firstResponseBody)).path("correlationId").stringValue(),
        )

    private fun correlationFrom(deactivation: PaymentMethodDeactivationEntity): String =
        checkNotNull(
            objectMapper.readTree(checkNotNull(deactivation.firstResponseBody)).path("correlationId").stringValue(),
        )

    private fun pendingRegistration(
        methodId: UUID,
        correlationId: String,
        now: Instant,
        notice: Boolean,
    ) = PaymentMethodHttpResult(
        202,
        objectMapper.writeValueAsString(
            linkedMapOf<String, Any>(
                "paymentMethodId" to methodId,
                "state" to "PROCESSING",
            ).apply { if (notice) put("noticeCode", "REGISTRATION_DELAYED") }
                .apply {
                    put("correlationId", correlationId)
                    put("updatedAt", now)
                },
        ),
    )

    private fun pendingDeactivation(
        methodId: UUID,
        correlationId: String,
        now: Instant,
        notice: Boolean,
    ) = PaymentMethodHttpResult(
        202,
        objectMapper.writeValueAsString(
            linkedMapOf<String, Any>(
                "paymentMethodId" to methodId,
                "state" to "PROCESSING",
            ).apply { if (notice) put("noticeCode", "DEACTIVATION_DELAYED") }
                .apply {
                    put("correlationId", correlationId)
                    put("updatedAt", now)
                },
        ),
    )

    private fun paymentMethodBody(method: PaymentMethodEntity): String =
        objectMapper.writeValueAsString(
            linkedMapOf<String, Any>(
                "paymentMethodId" to method.id,
                "provider" to PROVIDER,
                "displayAlias" to method.displayAlias,
                "cardBrand" to method.cardBrand,
                "lastFour" to method.lastFour,
                "isDefault" to method.isDefault,
                "status" to method.publicStatus(),
            ).apply {
                if (method.status == PaymentMethodStatus.MANUAL_REVIEW) put("noticeCode", "DEACTIVATION_DELAYED")
                put("createdAt", method.createdAt)
                put("updatedAt", method.updatedAt)
            },
        )

    private fun PaymentMethodEntity.publicStatus(): String = if (status == PaymentMethodStatus.ACTIVE) "ACTIVE" else "DEACTIVATION_PENDING"

    private fun idempotencyReused() = error(409, FailureCode.IDEMPOTENCY_KEY_REUSED)

    private fun error(
        status: Int,
        code: FailureCode,
    ): PaymentMethodHttpResult =
        PaymentMethodHttpResult(
            status,
            objectMapper.writeValueAsString(
                linkedMapOf(
                    "code" to code.name,
                    "message" to publicMessage(code),
                    "correlationId" to correlationIds.currentOrCreate(),
                    "details" to emptyList<Any>(),
                ),
            ),
        )

    private fun advisoryLock(value: String) {
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", Any::class.java, hash64(value))
    }

    private fun publicMessage(code: FailureCode): String =
        when (code) {
            FailureCode.IDEMPOTENCY_KEY_REUSED -> "Idempotency-Key was reused for another request"
            FailureCode.PAYMENT_METHOD_AUTHORIZATION_REUSED -> "The payment authorization was already consumed"
            FailureCode.PAYMENT_METHOD_REGISTRATION_REJECTED -> "Payment method registration was rejected"
            FailureCode.PAYMENT_METHOD_TOKEN_CONFLICT -> "Payment method binding requires manual review"
            FailureCode.PAYMENT_METHOD_PROVIDER_UNAVAILABLE -> "Payment method provider is unavailable"
            FailureCode.PAYMENT_METHOD_STATE_CONFLICT -> "Payment method state does not allow this operation"
            else -> "Payment method operation failed"
        }

    private fun unavailable(): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment method lifecycle state is unavailable")

    private fun requireClaim(
        processing: Boolean,
        actual: UUID?,
        expected: UUID,
    ) {
        if (!processing || actual != expected) unavailable()
    }

    private companion object {
        const val PROVIDER = "TOSS_PAYMENTS"
        const val REGISTER_OPERATION = "REGISTER_PAYMENT_METHOD_V1"
        const val DEFAULT_OPERATION = "SET_DEFAULT_PAYMENT_METHOD_V1"
        const val DEACTIVATE_OPERATION = "DEACTIVATE_PAYMENT_METHOD_V1"
        val RETENTION: Duration = Duration.ofDays(90)
        val DEACTIVATION_WINDOW: Duration = Duration.ofHours(96)
    }
}

@Component
internal class PaymentMethodProviderReferenceSource {
    private val secureRandom = SecureRandom()

    fun next(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return "bf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

@Component
internal class PaymentMethodLifecycleMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun command(
        operation: String,
        outcome: String,
    ) {
        meterRegistry.counter("beanflow.payment_method.command", "operation", operation, "outcome", outcome).increment()
    }

    fun provider(
        operation: String,
        outcome: String,
    ) {
        meterRegistry.counter("beanflow.payment_method.provider", "operation", operation, "outcome", outcome).increment()
    }

    fun work(
        kind: String,
        state: String,
    ) {
        meterRegistry.counter("beanflow.payment_method.work", "kind", kind, "state", state).increment()
    }

    fun notification(
        type: String,
        outcome: String,
    ) {
        meterRegistry.counter("beanflow.payment_method.notification", "type", type, "outcome", outcome).increment()
    }
}

private fun PaymentMethodRegistrationProviderResult.metricOutcome(): String =
    when (this) {
        is PaymentMethodRegistrationProviderResult.Issued -> "ISSUED"
        PaymentMethodRegistrationProviderResult.RejectedWithoutEffect -> "REJECTED"
        PaymentMethodRegistrationProviderResult.Unknown -> "UNKNOWN"
        PaymentMethodRegistrationProviderResult.Misconfigured -> "MISCONFIGURED"
    }

private fun PaymentMethodDeactivationProviderResult.metricOutcome(): String =
    when (this) {
        PaymentMethodDeactivationProviderResult.Deactivated -> "DEACTIVATED"
        PaymentMethodDeactivationProviderResult.RejectedWithoutEffect -> "REJECTED"
        PaymentMethodDeactivationProviderResult.Unknown -> "UNKNOWN"
        PaymentMethodDeactivationProviderResult.Misconfigured -> "MISCONFIGURED"
    }

private fun requireIdempotencyKey(value: String) {
    if (value.length !in 8..128) {
        throw DomainFailure(FailureCode.INVALID_REQUEST, "Idempotency-Key must contain 8 to 128 characters")
    }
}

private fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun hash64(value: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    var result = 0L
    repeat(Long.SIZE_BYTES) { index -> result = (result shl 8) or (digest[index].toLong() and 0xff) }
    return result
}
