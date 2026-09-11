package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StoreIdentityChange
import io.github.kdh949.beanflow.merchant.api.StoreIdentityCommand
import io.github.kdh949.beanflow.merchant.api.StoreIdentityOperations
import io.github.kdh949.beanflow.merchant.api.StoreIdentitySnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReplaceStoreSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermEntry
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
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
import java.util.HexFormat
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY)
internal class StoreIdentityService(
    private val stores: StoreJpaRepository,
    private val regions: RegionRepository,
    private val index: StoreSearchIndexOperations,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) : StoreIdentityOperations {
    override fun names(storeIds: Set<UUID>): Map<UUID, String> {
        val names =
            storeIds
                .chunked(100)
                .flatMap { batch ->
                    jdbc.query(
                        "SELECT store_id, name FROM merchant_store_discovery_profile WHERE store_id IN (${batch.joinToString(
                            ",",
                        ) { "?" }})",
                        { rs, _ -> rs.getObject("store_id", UUID::class.java) to rs.getString("name") },
                        *batch.toTypedArray(),
                    )
                }.toMap()
        if (names.keys != storeIds || names.values.any(String::isBlank)) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Current store labels are incomplete")
        }
        return names
    }

    override fun get(storeId: UUID): StoreIdentitySnapshot =
        jdbc
            .query(
                "$SELECT WHERE s.id = ?",
                ::map,
                storeId,
            ).singleOrNull()
            ?: throw DomainFailure(
                FailureCode.RESOURCE_NOT_FOUND,
                "Store was not found",
            )

    override fun list(
        query: String?,
        afterId: UUID?,
        limit: Int,
    ): List<StoreIdentitySnapshot> {
        if (limit !in 1..101 ||
            (
                query != null && (
                    query.length > 200 ||
                        query.any(Char::isISOControl)
                )
            )
        ) {
            invalid("Store list input is invalid")
        }
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (!query.isNullOrBlank()) {
            conditions += "strpos(lower(p.name), lower(?)) > 0"
            args += query.trim()
        }
        if (afterId != null) {
            conditions += "s.id > ?"
            args += afterId
        }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        args += limit
        return jdbc.query(
            "$SELECT$where ORDER BY s.id LIMIT ?",
            ::map,
            *args.toTypedArray(),
        )
    }

    override fun change(command: StoreIdentityCommand): StoreIdentityChange {
        validate(command)
        val operation = if (command.storeId == null) "CREATE" else "REPLACE"
        val hash =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    mapper.writeValueAsBytes(
                        listOf(
                            operation,
                            command.storeId,
                            command.name,
                            command.latitude,
                            command.longitude,
                            command.regionCode,
                            command.expectedVersion,
                            command.reason,
                        ),
                    ),
                ),
            )
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            Any::class.java,
            "store-identity:${command.actorId}:$operation:${command.key}",
        )
        jdbc
            .query(
                "SELECT id, payload_hash, response_json FROM merchant_store_identity_command WHERE actor_id = ? AND " +
                    "operation = ? AND idempotency_key = ?",
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
                command.actorId,
                operation,
                command.key,
            ).singleOrNull()
            ?.let {
                if (it.second != hash) {
                    throw DomainFailure(
                        FailureCode.IDEMPOTENCY_KEY_REUSED,
                        "Store identity key has another payload",
                    )
                }
                return StoreIdentityChange(
                    it.first,
                    mapper.readValue(
                        it.third,
                        StoreIdentitySnapshot::class.java,
                    ),
                    null,
                    true,
                )
            }
        val id = command.storeId ?: UUID.randomUUID()
        val previous: StoreIdentitySnapshot?
        if (command.storeId == null) {
            val region =
                regions.find(requireNotNull(command.regionCode)) ?: throw DomainFailure(
                    FailureCode.RESOURCE_NOT_FOUND,
                    "Region code was not found",
                )
            stores.saveAndFlush(
                StoreEntity(
                    id,
                    acceptingOrders = false,
                    pickupEnabled = false,
                ),
            )
            jdbc.update(
                "INSERT INTO merchant_store_discovery_profile(store_id, name, location, region_code) VALUES (?, ?, " +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)",
                id,
                command.name,
                command.longitude,
                command.latitude,
                region.code,
            )
            val regionTerms =
                listOf(
                    StoreSearchTermKind.REGION_SIDO to region.sido,
                    StoreSearchTermKind.REGION_SIGUNGU to region.sigungu,
                    StoreSearchTermKind.REGION_EUPMYEONDONG to region.eupmyeondong,
                    StoreSearchTermKind.REGION_RI to region.ri,
                ).filter {
                    it.second.isNotBlank()
                }.map {
                    StoreSearchTermEntry(
                        it.first,
                        it.second,
                    )
                }
            index.replaceStoreTerms(
                ReplaceStoreSearchTermsCommand(
                    id,
                    regionTerms
                        .map {
                            it.kind
                        }.toSet(),
                    regionTerms,
                ),
            )
            previous = null
        } else {
            stores.findByIdForUpdate(id) ?: throw DomainFailure(
                FailureCode.RESOURCE_NOT_FOUND,
                "Store was not found",
            )
            // Region authoring also locks the profile. Name/location updates preserve its current region.
            jdbc.queryForObject(
                "SELECT store_id FROM merchant_store_discovery_profile WHERE store_id = ? FOR UPDATE",
                UUID::class.java,
                id,
            )
            previous = get(id)
            if (previous.version != command.expectedVersion) {
                throw DomainFailure(
                    FailureCode.RESOURCE_STATE_CONFLICT,
                    "Store identity version is stale",
                )
            }
            jdbc.update(
                "UPDATE merchant_store_discovery_profile SET name = ?, location = ST_SetSRID(ST_MakePoint(?, ?), " +
                    "4326)::geography, identity_version = identity_version + 1 WHERE store_id = ?",
                command.name,
                command.longitude,
                command.latitude,
                id,
            )
        }
        index.replaceStoreTerms(
            ReplaceStoreSearchTermsCommand(
                id,
                setOf(StoreSearchTermKind.STORE_NAME),
                listOf(
                    StoreSearchTermEntry(
                        StoreSearchTermKind.STORE_NAME,
                        command.name,
                    ),
                ),
            ),
        )
        val response = get(id)
        val commandId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store_identity_command(id, actor_id, operation, idempotency_key, store_id, " +
                "payload_hash, response_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            commandId,
            command.actorId,
            operation,
            command.key,
            id,
            hash,
            mapper.writeValueAsString(response),
            Timestamp.from(command.now),
        )
        return StoreIdentityChange(
            commandId,
            response,
            previous,
            false,
        )
    }

    private fun validate(c: StoreIdentityCommand) {
        if (c.key.length !in 8..128 ||
            c.key != c.key.trim() ||
            c.key.any(Char::isISOControl) ||
            c.name.isBlank() ||
            c.name != c.name.trim() ||
            c.name.length > 200 ||
            c.name.any(Char::isISOControl) ||
            !c.latitude.isFinite() ||
            !c.longitude.isFinite() ||
            c.latitude !in -90.0..90.0 ||
            c.longitude !in -180.0..180.0 ||
            c.reason.isBlank() ||
            c.reason != c.reason.trim() ||
            c.reason.length > 500 ||
            c.reason.any(Char::isISOControl)
        ) {
            invalid("Store identity input is invalid")
        }
        if (c.storeId == null) {
            if (c.regionCode == null ||
                !c.regionCode.matches(Regex("[0-9]{10}")) ||
                c.expectedVersion != null
            ) {
                invalid("Store creation requires a verified region code")
            }
        } else if (c.expectedVersion == null ||
            c.expectedVersion < 0 ||
            c.regionCode != null
        ) {
            invalid("Store identity replacement requires a version and preserves the region")
        }
    }

    private fun map(
        rs: ResultSet,
        row: Int,
    ): StoreIdentitySnapshot {
        val name =
            rs.getString("name") ?: throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Store discovery profile is missing",
            )
        return StoreIdentitySnapshot(
            rs.getObject(
                "id",
                UUID::class.java,
            ),
            name,
            rs.getDouble("latitude"),
            rs.getDouble("longitude"),
            rs.getString("region_code"),
            rs.getLong("identity_version"),
            rs.getBoolean("accepting_orders"),
            rs.getBoolean("pickup_enabled"),
        )
    }

    private fun invalid(message: String): Nothing =
        throw DomainFailure(
            FailureCode.INVALID_REQUEST,
            message,
        )

    private companion object {
        const val SELECT =
            "SELECT s.id, s.accepting_orders, s.pickup_enabled, p.name, ST_Y(p.location::geometry) AS latitude, " +
                "ST_X(p.location::geometry) AS longitude, p.region_code, p.identity_version FROM merchant_store s " +
                "LEFT JOIN merchant_store_discovery_profile p ON p.store_id = s.id"
    }
}

@Component
internal class StoreIdentityCommandRetention(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    @Scheduled(
        fixedDelayString = "\${beanflow.store-identity.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.store-identity.retention.initial-delay-ms:3600000}",
    )
    fun cleanup() {
        jdbc.update(
            "DELETE FROM merchant_store_identity_command WHERE id IN (SELECT id FROM " +
                "merchant_store_identity_command WHERE created_at < ? ORDER BY created_at, id LIMIT 100 FOR UPDATE " +
                "SKIP LOCKED)",
            Timestamp.from(clock.instant().minus(Duration.ofDays(90))),
        )
    }
}
