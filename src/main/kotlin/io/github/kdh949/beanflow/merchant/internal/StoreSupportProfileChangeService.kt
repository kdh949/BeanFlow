package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.PrepareStoreAccessReregistration
import io.github.kdh949.beanflow.merchant.api.PrepareStoreOperationsContactCorrection
import io.github.kdh949.beanflow.merchant.api.PrepareStorePublicProfileCorrection
import io.github.kdh949.beanflow.merchant.api.PrepareStoreRepresentativeChange
import io.github.kdh949.beanflow.merchant.api.PrepareStoreSettlementAccountChange
import io.github.kdh949.beanflow.merchant.api.PreparedStoreProfileChange
import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileChangeOperations
import io.github.kdh949.beanflow.shared.api.BlindIndex
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.EncryptedPersonalData
import io.github.kdh949.beanflow.shared.api.ExactSearchCriterionType
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.KeyedBlindIndexPort
import io.github.kdh949.beanflow.shared.api.OwnerProfileChangeResult
import io.github.kdh949.beanflow.shared.api.OwnerProfileNotificationTarget
import io.github.kdh949.beanflow.shared.api.PersonalDataCryptoPort
import io.github.kdh949.beanflow.shared.api.PersonalDataEncryptionContext
import io.github.kdh949.beanflow.shared.api.PersonalDataField
import io.github.kdh949.beanflow.shared.api.PersonalDataMasker
import io.github.kdh949.beanflow.shared.api.PersonalDataNormalizer
import io.github.kdh949.beanflow.shared.api.PersonalDataOwnerContext
import io.github.kdh949.beanflow.shared.api.ProfileNotificationChannel
import io.github.kdh949.beanflow.shared.api.ProfileNotificationTargetKind
import io.github.kdh949.beanflow.shared.api.ProtectedProfileChangeValue
import io.github.kdh949.beanflow.shared.api.ResolvedProfileNotificationTarget
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class StoreSupportProfileChangeService(
    private val repository: StoreSupportProfileChangeRepository,
    private val crypto: PersonalDataCryptoPort,
    private val blindIndexes: KeyedBlindIndexPort,
    private val identifiers: IdentifierSource,
    private val clock: Clock,
) : StoreSupportProfileChangeOperations {
    @Transactional(readOnly = true)
    override fun currentVersion(storeId: UUID): Long = repository.currentVersion(storeId)

    override fun resolveNotificationTarget(targetId: UUID): ResolvedProfileNotificationTarget {
        val target = repository.notificationTarget(targetId)
        val field =
            when {
                target.purpose == "STORE_PUBLIC_PROFILE" && target.kind != ProfileNotificationTargetKind.CURRENT -> {
                    PersonalDataField.PUBLIC_PHONE
                }

                target.channel == ProfileNotificationChannel.PHONE -> {
                    PersonalDataField.SUPPORT_PHONE
                }

                else -> {
                    PersonalDataField.SUPPORT_EMAIL
                }
            }
        val destination =
            crypto.decrypt(
                target.encrypted,
                PersonalDataEncryptionContext(PersonalDataOwnerContext.MERCHANT, target.ownerId, field),
            )
        return ResolvedProfileNotificationTarget(targetId, target.kind, target.channel, destination)
    }

    override fun preparePublicProfile(command: PrepareStorePublicProfileCorrection): PreparedStoreProfileChange.PublicProfile {
        val values =
            buildList {
                command.displayName?.let { add(protectLabel(command.storeId, PersonalDataField.PUBLIC_DISPLAY_NAME, it)) }
                command.publicPhone?.let { add(protectContact(command.storeId, PersonalDataField.PUBLIC_PHONE, it)) }
            }
        val description = command.description?.let(::publicText)
        val pickup = command.pickupInstructions?.let(::publicText)
        require(values.isNotEmpty() || description != null || pickup != null) { "Store public-profile correction is empty" }
        return PreparedStoreProfileChange.PublicProfile(
            command.profileChangeId,
            command.storeId,
            requireVersion(command.expectedVersion),
            values,
            description,
            pickup,
        )
    }

    override fun prepareOperationsContact(command: PrepareStoreOperationsContactCorrection): PreparedStoreProfileChange.OperationsContact {
        val values =
            buildList {
                command.operationsPhone?.let { add(protectContact(command.storeId, PersonalDataField.SUPPORT_PHONE, it)) }
                command.operationsEmail?.let { add(protectContact(command.storeId, PersonalDataField.SUPPORT_EMAIL, it)) }
            }
        require(values.isNotEmpty()) { "Store operations-contact correction is empty" }
        return PreparedStoreProfileChange.OperationsContact(
            command.profileChangeId,
            command.storeId,
            requireVersion(command.expectedVersion),
            values,
        )
    }

    override fun prepareRepresentative(command: PrepareStoreRepresentativeChange): PreparedStoreProfileChange.Representative =
        PreparedStoreProfileChange.Representative(
            command.profileChangeId,
            command.storeId,
            requireVersion(command.expectedVersion),
            protectLabel(command.storeId, PersonalDataField.LEGAL_REPRESENTATIVE, command.representativeName),
        )

    override fun prepareSettlementAccount(command: PrepareStoreSettlementAccountChange): PreparedStoreProfileChange.SettlementAccount =
        PreparedStoreProfileChange.SettlementAccount(
            command.profileChangeId,
            command.storeId,
            requireVersion(command.expectedVersion),
            protectOpaque(
                command.storeId,
                PersonalDataField.SETTLEMENT_ACCOUNT_REFERENCE,
                command.settlementAccountReference,
            ),
        )

    override fun prepareAccessReregistration(command: PrepareStoreAccessReregistration): PreparedStoreProfileChange.AccessReregistration =
        PreparedStoreProfileChange.AccessReregistration(
            command.profileChangeId,
            command.storeId,
            requireVersion(command.expectedVersion),
        )

    @Transactional(propagation = Propagation.MANDATORY)
    override fun apply(prepared: PreparedStoreProfileChange): OwnerProfileChangeResult =
        try {
            repository.findResult(prepared.profileChangeId)?.let { return it }
            val row = repository.lock(prepared.storeId)
            if (row.version != prepared.expectedVersion) stale()
            if (prepared is PreparedStoreProfileChange.AccessReregistration) return applyReset(prepared, row)

            val values = prepared.values()
            val before = summary(values.mapNotNull { row.masked(it.field) }, prepared, before = true)
            val after = summary(values.map(ProtectedProfileChangeValue::masked), prepared, before = false)
            values.forEach { repository.updateValue(row.storeId, it) }
            if (prepared is PreparedStoreProfileChange.PublicProfile) {
                repository.updatePublicText(row.storeId, prepared.description, prepared.pickupInstructions)
            }
            values.filter { it.field in INDEXED_FIELDS }.forEach { value ->
                repository.replaceExactIndexes(row.storeId, value.field.criterionType(), value.exactIndexes, clock.instant())
            }
            val nextVersion = Math.addExact(row.version, 1)
            repository.advanceVersion(row.storeId, row.version, nextVersion, clock.instant())
            val historyId = identifiers.next()
            repository.insertHistory(
                historyId,
                row.storeId,
                prepared.profileChangeId,
                prepared.purpose(),
                prepared.risk(),
                row.version,
                nextVersion,
                before,
                after,
                clock.instant(),
            )
            val contactValues =
                values.filter {
                    it.field in
                        setOf(
                            PersonalDataField.PUBLIC_PHONE,
                            PersonalDataField.SUPPORT_PHONE,
                            PersonalDataField.SUPPORT_EMAIL,
                        )
                }
            val targets =
                if (contactValues.isNotEmpty()) {
                    buildList {
                        contactValues.forEach { value ->
                            row.notification(value.field)?.let {
                                add(
                                    repository.insertTarget(
                                        identifiers.next(),
                                        historyId,
                                        ProfileNotificationTargetKind.OLD,
                                        it,
                                        clock.instant(),
                                    ),
                                )
                            }
                            add(
                                repository.insertTarget(
                                    identifiers.next(),
                                    historyId,
                                    ProfileNotificationTargetKind.NEW,
                                    value.notification(),
                                    clock.instant(),
                                ),
                            )
                        }
                    }
                } else {
                    listOfNotNull(
                        row.preferredNotification()?.let {
                            repository.insertTarget(
                                identifiers.next(),
                                historyId,
                                ProfileNotificationTargetKind.CURRENT,
                                it,
                                clock.instant(),
                            )
                        },
                    )
                }
            OwnerProfileChangeResult(historyId, row.version, nextVersion, before, after, targets)
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store profile change is unavailable").also {
                it.initCause(failure)
            }
        }

    private fun applyReset(
        prepared: PreparedStoreProfileChange.AccessReregistration,
        row: StoreProfileChangeRow,
    ): OwnerProfileChangeResult {
        val now = clock.instant()
        val historyId = identifiers.next()
        repository.insertHistory(
            historyId,
            row.storeId,
            prepared.profileChangeId,
            "STORE_ACCESS_REREGISTRATION",
            "R4",
            row.version,
            row.version,
            "ACCESS_PROTECTED",
            "REREGISTRATION_REQUESTED",
            now,
        )
        repository.insertResetIntent(identifiers.next(), row.storeId, historyId, now)
        val targets =
            listOfNotNull(
                row.preferredNotification()?.let {
                    repository.insertTarget(identifiers.next(), historyId, ProfileNotificationTargetKind.CURRENT, it, now)
                },
            )
        return OwnerProfileChangeResult(
            historyId,
            row.version,
            row.version,
            "ACCESS_PROTECTED",
            "REREGISTRATION_REQUESTED",
            targets,
        )
    }

    private fun protectLabel(
        storeId: UUID,
        field: PersonalDataField,
        raw: String,
    ): ProtectedProfileChangeValue {
        val normalized = label(raw)
        return ProtectedProfileChangeValue(field, encrypt(storeId, field, normalized), PersonalDataMasker.maskDisplayLabel(normalized))
    }

    private fun protectContact(
        storeId: UUID,
        field: PersonalDataField,
        raw: String,
    ): ProtectedProfileChangeValue {
        val type = field.criterionType()
        val normalized = PersonalDataNormalizer.normalize(type, raw)
        val canonical =
            if (type == ExactSearchCriterionType.PHONE) {
                PersonalDataNormalizer.normalizePhoneForMasking(raw)
            } else {
                PersonalDataNormalizer.normalizeEmailForMasking(raw)
            }
        val masked =
            if (type == ExactSearchCriterionType.PHONE) PersonalDataMasker.maskPhone(canonical) else PersonalDataMasker.maskEmail(canonical)
        return ProtectedProfileChangeValue(
            field,
            encrypt(storeId, field, canonical),
            masked,
            if (field in INDEXED_FIELDS) blindIndexes.generate(normalized, blindIndexes.activeSearchKeyVersions()) else emptyList(),
        )
    }

    private fun protectOpaque(
        storeId: UUID,
        field: PersonalDataField,
        raw: String,
    ): ProtectedProfileChangeValue {
        val normalized = opaque(raw)
        return ProtectedProfileChangeValue(field, encrypt(storeId, field, normalized), "***${normalized.takeLast(4)}")
    }

    private fun encrypt(
        storeId: UUID,
        field: PersonalDataField,
        plaintext: String,
    ): EncryptedPersonalData =
        crypto.encrypt(
            plaintext.toByteArray(StandardCharsets.UTF_8),
            PersonalDataEncryptionContext(PersonalDataOwnerContext.MERCHANT, storeId, field),
        )

    private fun summary(
        masked: List<String>,
        prepared: PreparedStoreProfileChange,
        before: Boolean,
    ): String = (masked.ifEmpty { listOf(if (before) "PUBLIC_PROFILE_PRESENT" else "PUBLIC_TEXT_UPDATED") }).joinToString(";").take(1000)

    private fun publicText(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC).also {
            require(it.length in 1..1000 && it.none(Char::isISOControl)) { "Store public text is invalid" }
        }

    private fun label(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC).also {
            require(it.length in 1..200 && it.none(Char::isISOControl)) { "Store profile label is invalid" }
        }

    private fun opaque(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC).also {
            require(it.matches(Regex("^[A-Za-z0-9:_-]{4,200}$"))) { "Store opaque reference is invalid" }
        }

    private fun requireVersion(version: Long): Long = version.also { require(it >= 0) }

    private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Store profile version is stale")

    private companion object {
        val INDEXED_FIELDS = setOf(PersonalDataField.SUPPORT_PHONE, PersonalDataField.SUPPORT_EMAIL)
    }
}

private fun PreparedStoreProfileChange.values(): List<ProtectedProfileChangeValue> =
    when (this) {
        is PreparedStoreProfileChange.PublicProfile -> values
        is PreparedStoreProfileChange.OperationsContact -> values
        is PreparedStoreProfileChange.Representative -> listOf(value)
        is PreparedStoreProfileChange.SettlementAccount -> listOf(value)
        is PreparedStoreProfileChange.AccessReregistration -> emptyList()
    }

private fun PreparedStoreProfileChange.purpose(): String =
    when (this) {
        is PreparedStoreProfileChange.PublicProfile -> "STORE_PUBLIC_PROFILE"
        is PreparedStoreProfileChange.OperationsContact -> "STORE_OPERATIONS_CONTACT"
        is PreparedStoreProfileChange.Representative -> "STORE_REPRESENTATIVE"
        is PreparedStoreProfileChange.SettlementAccount -> "STORE_SETTLEMENT_ACCOUNT"
        is PreparedStoreProfileChange.AccessReregistration -> "STORE_ACCESS_REREGISTRATION"
    }

private fun PreparedStoreProfileChange.risk(): String =
    when (this) {
        is PreparedStoreProfileChange.PublicProfile -> "R1"

        is PreparedStoreProfileChange.OperationsContact -> "R2"

        is PreparedStoreProfileChange.Representative,
        is PreparedStoreProfileChange.SettlementAccount,
        -> "R3"

        is PreparedStoreProfileChange.AccessReregistration -> "R4"
    }

internal data class StoreNotificationValue(
    val channel: ProfileNotificationChannel,
    val encrypted: EncryptedPersonalData,
    val masked: String,
)

private fun ProtectedProfileChangeValue.notification(): StoreNotificationValue =
    StoreNotificationValue(field.criterionType().channel(), encrypted, masked)

private fun PersonalDataField.criterionType(): ExactSearchCriterionType =
    when (this) {
        PersonalDataField.PUBLIC_PHONE,
        PersonalDataField.SUPPORT_PHONE,
        -> ExactSearchCriterionType.PHONE

        PersonalDataField.SUPPORT_EMAIL -> ExactSearchCriterionType.EMAIL

        else -> throw IllegalArgumentException("Profile field is not a contact")
    }

private fun ExactSearchCriterionType.channel(): ProfileNotificationChannel =
    if (this == ExactSearchCriterionType.PHONE) ProfileNotificationChannel.PHONE else ProfileNotificationChannel.EMAIL

internal data class StoreProfileChangeRow(
    val storeId: UUID,
    val values: Map<PersonalDataField, StoreNotificationValue>,
    val version: Long,
) {
    fun masked(field: PersonalDataField): String? = values[field]?.masked

    fun notification(field: PersonalDataField): StoreNotificationValue? = values[field]

    fun preferredNotification(): StoreNotificationValue? =
        values[PersonalDataField.SUPPORT_PHONE] ?: values[PersonalDataField.SUPPORT_EMAIL]
}

@Repository
internal class StoreSupportProfileChangeRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun currentVersion(storeId: UUID): Long =
        jdbcTemplate
            .query(
                "SELECT version FROM merchant_store_support_profile WHERE store_id = ?",
                { rs, _ -> rs.getLong("version") },
                storeId,
            ).singleOrNull() ?: notFound()

    fun lock(storeId: UUID): StoreProfileChangeRow =
        jdbcTemplate
            .query(
                """
                SELECT store_id,
                       legal_display_name_ciphertext, legal_display_name_key_version, legal_display_name_aad_version,
                       masked_display_name,
                       public_display_name_ciphertext, public_display_name_key_version, public_display_name_aad_version,
                       masked_public_display_name,
                       public_phone_ciphertext, public_phone_key_version, public_phone_aad_version, masked_public_phone,
                       support_phone_ciphertext, support_phone_key_version, support_phone_aad_version, masked_support_phone,
                       support_email_ciphertext, support_email_key_version, support_email_aad_version, masked_support_email,
                       legal_representative_ciphertext, legal_representative_key_version, legal_representative_aad_version,
                       masked_legal_representative,
                       settlement_account_reference_ciphertext, settlement_account_reference_key_version,
                       settlement_account_reference_aad_version, masked_settlement_account_reference,
                       version
                  FROM merchant_store_support_profile
                 WHERE store_id = ? FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    StoreProfileChangeRow(
                        rs.getObject("store_id", UUID::class.java),
                        buildMap {
                            rs
                                .value("public_display_name", "masked_public_display_name", ProfileNotificationChannel.EMAIL)
                                ?.let { put(PersonalDataField.PUBLIC_DISPLAY_NAME, it) }
                            rs
                                .value("public_phone", "masked_public_phone", ProfileNotificationChannel.PHONE)
                                ?.let { put(PersonalDataField.PUBLIC_PHONE, it) }
                            rs
                                .value("support_phone", "masked_support_phone", ProfileNotificationChannel.PHONE)
                                ?.let { put(PersonalDataField.SUPPORT_PHONE, it) }
                            rs
                                .value("support_email", "masked_support_email", ProfileNotificationChannel.EMAIL)
                                ?.let { put(PersonalDataField.SUPPORT_EMAIL, it) }
                            rs
                                .value("legal_representative", "masked_legal_representative", ProfileNotificationChannel.EMAIL)
                                ?.let { put(PersonalDataField.LEGAL_REPRESENTATIVE, it) }
                            rs
                                .value(
                                    "settlement_account_reference",
                                    "masked_settlement_account_reference",
                                    ProfileNotificationChannel.EMAIL,
                                )?.let { put(PersonalDataField.SETTLEMENT_ACCOUNT_REFERENCE, it) }
                        },
                        rs.getLong("version"),
                    )
                },
                storeId,
            ).singleOrNull() ?: notFound()

    fun updateValue(
        storeId: UUID,
        value: ProtectedProfileChangeValue,
    ) {
        val prefix =
            when (value.field) {
                PersonalDataField.PUBLIC_DISPLAY_NAME -> "public_display_name"
                PersonalDataField.PUBLIC_PHONE -> "public_phone"
                PersonalDataField.SUPPORT_PHONE -> "support_phone"
                PersonalDataField.SUPPORT_EMAIL -> "support_email"
                PersonalDataField.LEGAL_REPRESENTATIVE -> "legal_representative"
                PersonalDataField.SETTLEMENT_ACCOUNT_REFERENCE -> "settlement_account_reference"
                else -> throw IllegalArgumentException("Unsupported store profile field")
            }
        jdbcTemplate.update(
            """
            UPDATE merchant_store_support_profile
               SET ${prefix}_ciphertext = ?, ${prefix}_key_version = ?, ${prefix}_aad_version = ?,
                   masked_$prefix = ?
             WHERE store_id = ?
            """.trimIndent(),
            value.encrypted.ciphertext,
            value.encrypted.keyVersion,
            value.encrypted.aadVersion,
            value.masked,
            storeId,
        )
    }

    fun updatePublicText(
        storeId: UUID,
        description: String?,
        pickupInstructions: String?,
    ) {
        if (description != null) {
            jdbcTemplate.update("UPDATE merchant_store_support_profile SET public_description = ? WHERE store_id = ?", description, storeId)
        }
        if (pickupInstructions != null) {
            jdbcTemplate.update(
                "UPDATE merchant_store_support_profile SET pickup_instructions = ? WHERE store_id = ?",
                pickupInstructions,
                storeId,
            )
        }
    }

    fun advanceVersion(
        storeId: UUID,
        expected: Long,
        next: Long,
        now: Instant,
    ) {
        if (
            jdbcTemplate.update(
                "UPDATE merchant_store_support_profile SET version = ?, updated_at = ? WHERE store_id = ? AND version = ?",
                next,
                Timestamp.from(now),
                storeId,
                expected,
            ) != 1
        ) {
            throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Store profile version is stale")
        }
    }

    fun replaceExactIndexes(
        storeId: UUID,
        type: ExactSearchCriterionType,
        indexes: List<BlindIndex>,
        now: Instant,
    ) {
        jdbcTemplate.update(
            "DELETE FROM merchant_store_support_profile_exact_index WHERE store_id = ? AND criterion_type = ?",
            storeId,
            type.name,
        )
        indexes.forEach { index ->
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_support_profile_exact_index (
                    store_id, criterion_type, index_key_version, blind_index, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                storeId,
                type.name,
                index.keyVersion,
                index.digestBytes(),
                Timestamp.from(now),
            )
        }
    }

    @Suppress("LongParameterList")
    fun insertHistory(
        id: UUID,
        storeId: UUID,
        supportChangeId: UUID,
        purpose: String,
        risk: String,
        previous: Long,
        current: Long,
        before: String,
        after: String,
        now: Instant,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO merchant_store_profile_change_history (
                id, store_id, support_profile_change_id, purpose, risk_class, previous_version,
                current_version, masked_before, masked_after, changed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            storeId,
            supportChangeId,
            purpose,
            risk,
            previous,
            current,
            before,
            after,
            Timestamp.from(now),
        )
    }

    fun insertResetIntent(
        id: UUID,
        storeId: UUID,
        historyId: UUID,
        now: Instant,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO merchant_store_profile_reset_intent (
                id, store_id, profile_change_history_id, intent_type, state, created_at
            ) VALUES (?, ?, ?, 'ACCESS_REREGISTRATION', 'REQUESTED', ?)
            """.trimIndent(),
            id,
            storeId,
            historyId,
            Timestamp.from(now),
        )
    }

    fun insertTarget(
        id: UUID,
        historyId: UUID,
        kind: ProfileNotificationTargetKind,
        value: StoreNotificationValue,
        now: Instant,
    ): OwnerProfileNotificationTarget {
        jdbcTemplate.update(
            """
            INSERT INTO merchant_store_profile_notification_target (
                id, profile_change_history_id, target_kind, channel_type, destination_ciphertext,
                destination_key_version, destination_aad_version, masked_destination, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            historyId,
            kind.name,
            value.channel.name,
            value.encrypted.ciphertext,
            value.encrypted.keyVersion,
            value.encrypted.aadVersion,
            value.masked,
            Timestamp.from(now),
        )
        return OwnerProfileNotificationTarget(id, kind, value.channel, value.masked)
    }

    fun findResult(profileChangeId: UUID): OwnerProfileChangeResult? =
        findResult(
            "merchant_store_profile_change_history",
            "merchant_store_profile_notification_target",
            profileChangeId,
        )

    fun notificationTarget(targetId: UUID): StoreOwnerNotificationSnapshot =
        jdbcTemplate
            .query(
                """
                SELECT target.target_kind, target.channel_type, target.destination_ciphertext,
                       target.destination_key_version, target.destination_aad_version, history.store_id, history.purpose
                  FROM merchant_store_profile_notification_target target
                  JOIN merchant_store_profile_change_history history ON history.id = target.profile_change_history_id
                 WHERE target.id = ?
                """.trimIndent(),
                { rs, _ ->
                    StoreOwnerNotificationSnapshot(
                        rs.getObject("store_id", UUID::class.java),
                        rs.getString("purpose"),
                        ProfileNotificationTargetKind.valueOf(rs.getString("target_kind")),
                        ProfileNotificationChannel.valueOf(rs.getString("channel_type")),
                        EncryptedPersonalData(
                            rs.getString("destination_ciphertext"),
                            rs.getInt("destination_key_version"),
                            rs.getInt("destination_aad_version"),
                        ),
                    )
                },
                targetId,
            ).singleOrNull() ?: notFound()

    private fun findResult(
        historyTable: String,
        targetTable: String,
        profileChangeId: UUID,
    ): OwnerProfileChangeResult? {
        val result =
            jdbcTemplate
                .query(
                    "SELECT id, previous_version, current_version, masked_before, masked_after FROM $historyTable " +
                        "WHERE support_profile_change_id = ?",
                    { rs, _ ->
                        OwnerProfileChangeResult(
                            rs.getObject("id", UUID::class.java),
                            rs.getLong("previous_version"),
                            rs.getLong("current_version"),
                            rs.getString("masked_before"),
                            rs.getString("masked_after"),
                            emptyList(),
                        )
                    },
                    profileChangeId,
                ).singleOrNull() ?: return null
        val targets =
            jdbcTemplate.query(
                "SELECT id, target_kind, channel_type, masked_destination FROM $targetTable " +
                    "WHERE profile_change_history_id = ? " +
                    "ORDER BY CASE target_kind WHEN 'OLD' THEN 0 WHEN 'NEW' THEN 1 ELSE 2 END, channel_type, id",
                { rs, _ ->
                    OwnerProfileNotificationTarget(
                        rs.getObject("id", UUID::class.java),
                        ProfileNotificationTargetKind.valueOf(rs.getString("target_kind")),
                        ProfileNotificationChannel.valueOf(rs.getString("channel_type")),
                        rs.getString("masked_destination"),
                    )
                },
                result.ownerChangeId,
            )
        return result.copy(notificationTargets = targets)
    }

    private fun java.sql.ResultSet.value(
        prefix: String,
        maskedColumn: String,
        channel: ProfileNotificationChannel,
    ): StoreNotificationValue? =
        getString("${prefix}_ciphertext")?.let {
            StoreNotificationValue(
                channel,
                EncryptedPersonalData(it, getInt("${prefix}_key_version"), getInt("${prefix}_aad_version")),
                getString(maskedColumn),
            )
        }

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store profile was not found")
}

internal data class StoreOwnerNotificationSnapshot(
    val ownerId: UUID,
    val purpose: String,
    val kind: ProfileNotificationTargetKind,
    val channel: ProfileNotificationChannel,
    val encrypted: EncryptedPersonalData,
)
