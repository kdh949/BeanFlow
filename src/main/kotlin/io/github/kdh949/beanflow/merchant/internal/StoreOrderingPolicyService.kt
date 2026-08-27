package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.ReplaceStoreOrderingPolicyCommand
import io.github.kdh949.beanflow.merchant.api.StoreOrderingPolicyOperations
import io.github.kdh949.beanflow.merchant.api.StoreOrderingPolicyReplacement
import io.github.kdh949.beanflow.merchant.api.StoreOrderingPolicySnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

@Service
internal class StoreOrderingPolicyService(
    private val stores: StoreJpaRepository,
    private val commands: StoreOrderingPolicyCommandRepository,
    private val identifiers: IdentifierSource,
    private val objectMapper: ObjectMapper,
) : StoreOrderingPolicyOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun find(storeId: UUID): StoreOrderingPolicySnapshot =
        stores.findById(storeId).orElse(null)?.snapshot()
            ?: throw DomainFailure(
                FailureCode.RESOURCE_NOT_FOUND,
                "Store was not found",
                targetReference = storeId.toString(),
            )

    @Transactional(propagation = Propagation.MANDATORY)
    override fun replace(command: ReplaceStoreOrderingPolicyCommand): StoreOrderingPolicyReplacement {
        validate(command)
        val payloadHash = payloadHash(command)
        commands.lockCommandKey(command.actorId, OPERATION, command.idempotencyKey)
        commands.findCommand(command.actorId, OPERATION, command.idempotencyKey)?.let { existing ->
            if (existing.payloadHash != payloadHash) {
                throw DomainFailure(
                    FailureCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was reused with another Store ordering-policy command",
                )
            }
            val replay = objectMapper.readValue(existing.responseJson, StoreOrderingPolicySnapshot::class.java)
            return StoreOrderingPolicyReplacement(replay, replay, changed = false, replayed = true)
        }
        val store =
            stores.findByIdForUpdate(command.storeId)
                ?: throw DomainFailure(
                    FailureCode.RESOURCE_NOT_FOUND,
                    "Store was not found",
                    targetReference = command.storeId.toString(),
                )
        if (store.orderingPolicyVersion != command.expectedVersion) {
            throw DomainFailure(FailureCode.MERCHANT_CONTENT_STALE, "Store ordering-policy version is stale")
        }

        val previous = store.snapshot()
        val changed =
            store.acceptingOrders != command.acceptingOrders ||
                store.pickupEnabled != command.pickupEnabled
        if (changed) {
            store.replaceOrderingPolicy(command.acceptingOrders, command.pickupEnabled, command.now)
            stores.flush()
        }
        val policy = store.snapshot()
        commands.insertCommand(
            id = identifiers.next(),
            actorId = command.actorId,
            idempotencyKey = command.idempotencyKey,
            payloadHash = payloadHash,
            storeId = command.storeId,
            responseJson = objectMapper.writeValueAsString(policy),
            now = command.now,
        )
        return StoreOrderingPolicyReplacement(policy, previous, changed, replayed = false)
    }

    private fun validate(command: ReplaceStoreOrderingPolicyCommand) {
        val key = command.idempotencyKey
        if (key.length !in 8..128 || key != key.trim() || key.any { it.isISOControl() }) {
            throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "Idempotency-Key must contain 8 to 128 non-control characters without outer whitespace",
            )
        }
        if (command.expectedVersion < 0) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "expectedVersion must be zero or greater")
        }
    }

    private fun payloadHash(command: ReplaceStoreOrderingPolicyCommand): String =
        sha256(
            listOf(
                OPERATION,
                command.storeId.toString(),
                command.acceptingOrders.toString(),
                command.pickupEnabled.toString(),
                command.expectedVersion.toString(),
            ).joinToString(FIELD_SEPARATOR),
        )

    private fun StoreEntity.snapshot() =
        StoreOrderingPolicySnapshot(id, acceptingOrders, pickupEnabled, orderingPolicyVersion, orderingPolicyUpdatedAt)

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        const val OPERATION = "REPLACE_STORE_ORDERING_POLICY_V1"
        const val FIELD_SEPARATOR = "\u001F"
    }
}

@Component
internal class StoreOrderingPolicyCommandRetentionWorker(
    private val cleanup: StoreOrderingPolicyCommandRetentionCleanup,
    private val clock: Clock,
    @Value("\${beanflow.store-ordering-policy-command.retention.batch-size:100}")
    private val batchSize: Int,
) {
    init {
        require(batchSize in 1..1_000) { "Store ordering-policy command cleanup batch size is invalid" }
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.store-ordering-policy-command.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.store-ordering-policy-command.retention.initial-delay-ms:3600000}",
    )
    fun cleanupExpired() {
        cleanup.deleteExpired(clock.instant(), batchSize)
    }
}
