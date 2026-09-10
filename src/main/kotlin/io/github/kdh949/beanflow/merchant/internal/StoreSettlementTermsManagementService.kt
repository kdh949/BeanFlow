package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.ManagedStoreSettlementTerms
import io.github.kdh949.beanflow.merchant.api.RegisterStoreSettlementTermsCommand
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsChange
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsManagementOperations
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsRows
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY)
internal class StoreSettlementTermsManagementService(
    private val stores: StoreJpaRepository,
    private val terms: StoreSettlementTermsJpaRepository,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) : StoreSettlementTermsManagementOperations {
    override fun get(
        storeId: UUID,
        termsVersionId: UUID,
    ): ManagedStoreSettlementTerms {
        lockStore(
            storeId,
            false,
        )
        val item =
            jdbc
                .query(
                    "$SELECT WHERE store_id = ? AND terms_version_id = ?",
                    ::map,
                    storeId,
                    termsVersionId,
                ).singleOrNull() ?: missing()
        return ManagedStoreSettlementTerms(
            item,
            revision(storeId),
        )
    }

    override fun list(
        storeId: UUID,
        afterFrom: Instant?,
        afterId: UUID?,
        limit: Int,
    ): StoreSettlementTermsRows {
        if (limit !in 1..101 ||
            (afterFrom == null) != (afterId == null)
        ) {
            invalid()
        }
        lockStore(
            storeId,
            false,
        )
        val args = mutableListOf<Any>(storeId)
        val after =
            if (afterFrom != null && afterId != null) {
                args += Timestamp.from(afterFrom)
                args += afterId
                " AND (effective_from, terms_version_id) > (?, ?)"
            } else {
                ""
            }
        args += limit
        return StoreSettlementTermsRows(
            jdbc.query(
                "$SELECT WHERE store_id = ?$after ORDER BY effective_from, terms_version_id LIMIT ?",
                ::map,
                *args.toTypedArray(),
            ),
            revision(storeId),
        )
    }

    override fun register(command: RegisterStoreSettlementTermsCommand): StoreSettlementTermsChange {
        val c =
            command.copy(
                effectiveFrom = command.effectiveFrom.truncatedTo(ChronoUnit.MICROS),
                effectiveTo = command.effectiveTo?.truncatedTo(ChronoUnit.MICROS),
            )
        if (!validText(
                c.key,
                8,
                128,
            ) ||
            !validText(
                c.sourceReference,
                1,
                240,
            ) ||

            !validText(
                c.reason,
                1,
                500,
            ) ||
            c.feeRateBps !in 0..10000 ||
            c.expectedRevision < 0 ||

            (c.effectiveTo != null && !c.effectiveTo.isAfter(c.effectiveFrom))
        ) {
            invalid()
        }
        val hash =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    mapper.writeValueAsBytes(
                        listOf(
                            c.storeId,
                            c.sourceReference,
                            c.feeRateBps,
                            c.effectiveFrom,
                            c.effectiveTo,
                            c.expectedRevision,
                            c.reason,
                        ),
                    ),
                ),
            )
        lock("terms-command:${c.actorId}:${c.key}")
        jdbc
            .query(
                "SELECT id, payload_hash, response_json FROM merchant_terms_command WHERE actor_id = ? AND idempotency_key = ?",
                {
                    rs,
                    _,
                    ->
                    Triple(
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                        rs.getString("payload_hash"),
                        rs.getString("response_json"),
                    )
                },
                c.actorId,
                c.key,
            ).singleOrNull()
            ?.let {
                if (it.second != hash) {
                    throw DomainFailure(
                        FailureCode.IDEMPOTENCY_KEY_REUSED,
                        "Terms key has another payload",
                    )
                }
                return StoreSettlementTermsChange(
                    it.first,
                    mapper.readValue(
                        it.third,
                        ManagedStoreSettlementTerms::class.java,
                    ),
                    true,
                )
            }
        if (!c.effectiveFrom.isAfter(c.now)) invalid()
        lock("terms-source:${c.sourceReference}")
        lockStore(
            c.storeId,
            true,
        )
        val revision = revision(c.storeId)
        if (revision != c.expectedRevision) conflict("Terms revision is stale")
        if (jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM merchant_store_settlement_terms WHERE source_reference = ?)",
                Boolean::class.java,
                c.sourceReference,
            ) == true
        ) {
            conflict("Terms source reference already exists")
        }
        if (jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM merchant_store_settlement_terms WHERE store_id = ? " +
                    "AND effective_from < COALESCE(?::timestamptz, 'infinity'::timestamptz) " +
                    "AND ? < COALESCE(effective_to, 'infinity'::timestamptz))",
                Boolean::class.java,
                c.storeId,
                c.effectiveTo?.let(Timestamp::from),
                Timestamp.from(c.effectiveFrom),
            ) == true
        ) {
            conflict("Terms effective intervals overlap")
        }
        val id = UUID.randomUUID()
        terms.saveAndFlush(
            StoreSettlementTermsEntity(
                id,
                c.storeId,
                c.sourceReference,
                c.feeRateBps,
                c.effectiveFrom,
                c.effectiveTo,
                c.now,
            ),
        )
        val response =
            ManagedStoreSettlementTerms(
                StoreSettlementTermsSnapshot(
                    id,
                    c.storeId,
                    c.sourceReference,
                    c.feeRateBps,
                    c.effectiveFrom,
                    c.effectiveTo,
                ),
                revision + 1,
            )
        val commandId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_terms_command(id, actor_id, store_id, idempotency_key, payload_hash, response_json, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            commandId,
            c.actorId,
            c.storeId,
            c.key,
            hash,
            mapper.writeValueAsString(response),
            Timestamp.from(c.now),
        )
        return StoreSettlementTermsChange(
            commandId,
            response,
            false,
        )
    }

    private fun lockStore(
        storeId: UUID,
        write: Boolean,
    ) {
        (if (write) stores.findByIdForUpdate(storeId) else stores.findByIdForShare(storeId)) ?: missing()
    }

    private fun revision(storeId: UUID) =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM merchant_store_settlement_terms WHERE store_id = ?",
                Long::class.java,
                storeId,
            ),
        )

    private fun lock(key: String) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            Any::class.java,
            key,
        )
    }

    private fun map(
        rs: ResultSet,
        row: Int,
    ) = StoreSettlementTermsSnapshot(
        rs.getObject(
            "terms_version_id",
            UUID::class.java,
        ),
        rs.getObject(
            "store_id",
            UUID::class.java,
        ),
        rs.getString("source_reference"),
        rs.getInt("fee_rate_bps"),
        rs.getTimestamp("effective_from").toInstant(),
        rs.getTimestamp("effective_to")?.toInstant(),
    )

    private fun validText(
        value: String,
        min: Int,
        max: Int,
    ) = value.length in min..max && value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)

    private fun invalid(): Nothing =
        throw DomainFailure(
            FailureCode.INVALID_REQUEST,
            "Terms input must have a future valid interval",
        )

    private fun conflict(message: String): Nothing =
        throw DomainFailure(
            FailureCode.RESOURCE_STATE_CONFLICT,
            message,
        )

    private fun missing(): Nothing =
        throw DomainFailure(
            FailureCode.RESOURCE_NOT_FOUND,
            "Store or terms version was not found",
        )

    private companion object {
        const val SELECT =
            "SELECT terms_version_id, store_id, source_reference, fee_rate_bps, effective_from, " +
                "effective_to FROM merchant_store_settlement_terms"
    }
}

@Component
internal class StoreTermsCommandRetention(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    @Scheduled(
        fixedDelayString = "\${beanflow.store-terms.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.store-terms.retention.initial-delay-ms:3600000}",
    )
    fun cleanup() {
        jdbc.update(
            "DELETE FROM merchant_terms_command WHERE id IN (SELECT id FROM merchant_terms_command " +
                "WHERE created_at < ? ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED)",
            Timestamp.from(clock.instant().minus(Duration.ofDays(90))),
        )
    }
}
