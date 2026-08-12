package io.github.kdh949.beanflow.delivery.internal

import io.github.kdh949.beanflow.delivery.api.ExternalCourierSupportProfileChangeOperations
import io.github.kdh949.beanflow.delivery.api.PrepareCourierDisplayNameCorrection
import io.github.kdh949.beanflow.delivery.api.PrepareCourierPayoutReferenceChange
import io.github.kdh949.beanflow.delivery.api.PrepareCourierProviderIdentityChange
import io.github.kdh949.beanflow.delivery.api.PrepareCourierProviderReregistration
import io.github.kdh949.beanflow.delivery.api.PrepareCourierRelayContactCorrection
import io.github.kdh949.beanflow.delivery.api.PreparedCourierProfileChange
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
import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class ExternalCourierSupportProfileChangeService(
    private val repository: ExternalCourierSupportProfileChangeRepository,
    private val crypto: PersonalDataCryptoPort,
    private val blindIndexes: KeyedBlindIndexPort,
    private val identifiers: IdentifierSource,
    private val clock: Clock,
) : ExternalCourierSupportProfileChangeOperations {
    @Transactional(readOnly = true)
    override fun currentVersion(externalCourierId: UUID): Long = repository.currentVersion(externalCourierId)

    override fun resolveNotificationTarget(targetId: UUID): ResolvedProfileNotificationTarget {
        val target = repository.notificationTarget(targetId)
        val field =
            if (target.channel == ProfileNotificationChannel.PHONE) PersonalDataField.RELAY_PHONE else PersonalDataField.RELAY_EMAIL
        val destination =
            crypto.decrypt(
                target.encrypted,
                PersonalDataEncryptionContext(PersonalDataOwnerContext.DELIVERY, target.ownerId, field),
            )
        return ResolvedProfileNotificationTarget(targetId, target.kind, target.channel, destination)
    }

    override fun prepareDisplayName(
        command: PrepareCourierDisplayNameCorrection,
    ): PreparedCourierProfileChange.DisplayName =
        PreparedCourierProfileChange.DisplayName(
            command.profileChangeId,
            command.externalCourierId,
            requireVersion(command.expectedVersion),
            protectLabel(command.externalCourierId, PersonalDataField.DISPLAY_NAME, command.displayName),
        )

    override fun prepareRelayContact(
        command: PrepareCourierRelayContactCorrection,
    ): PreparedCourierProfileChange.RelayContact {
        val values =
            buildList {
                command.relayPhone?.let { add(protectContact(command.externalCourierId, PersonalDataField.RELAY_PHONE, it)) }
                command.relayEmail?.let { add(protectContact(command.externalCourierId, PersonalDataField.RELAY_EMAIL, it)) }
            }
        require(values.isNotEmpty()) { "Courier relay-contact correction is empty" }
        return PreparedCourierProfileChange.RelayContact(
            command.profileChangeId,
            command.externalCourierId,
            requireVersion(command.expectedVersion),
            values,
        )
    }

    override fun prepareProviderIdentity(
        command: PrepareCourierProviderIdentityChange,
    ): PreparedCourierProfileChange.ProviderIdentity =
        PreparedCourierProfileChange.ProviderIdentity(
            command.profileChangeId,
            command.externalCourierId,
            requireVersion(command.expectedVersion),
            protectOpaque(
                command.externalCourierId,
                PersonalDataField.PROVIDER_COURIER_REFERENCE,
                command.providerCourierReference,
            ),
        )

    override fun preparePayoutReference(
        command: PrepareCourierPayoutReferenceChange,
    ): PreparedCourierProfileChange.PayoutReference =
        PreparedCourierProfileChange.PayoutReference(
            command.profileChangeId,
            command.externalCourierId,
            requireVersion(command.expectedVersion),
            protectOpaque(command.externalCourierId, PersonalDataField.PAYOUT_REFERENCE, command.payoutReference),
        )

    override fun prepareProviderReregistration(
        command: PrepareCourierProviderReregistration,
    ): PreparedCourierProfileChange.ProviderReregistration =
        PreparedCourierProfileChange.ProviderReregistration(
            command.profileChangeId,
            command.externalCourierId,
            requireVersion(command.expectedVersion),
        )

    @Transactional(propagation = Propagation.MANDATORY)
    override fun apply(prepared: PreparedCourierProfileChange): OwnerProfileChangeResult =
        try {
            repository.findResult(prepared.profileChangeId)?.let { return it }
            val row = repository.lock(prepared.externalCourierId)
            if (row.version != prepared.expectedVersion) stale()
            if (prepared is PreparedCourierProfileChange.ProviderReregistration) return applyReset(prepared, row)

            val values = prepared.values()
            val before = values.mapNotNull { row.values[it.field]?.masked }.ifEmpty { listOf("NOT_SET") }.joinToString(";").take(1000)
            val after = values.map(ProtectedProfileChangeValue::masked).joinToString(";").take(1000)
            values.forEach { repository.updateValue(row.externalCourierId, it) }
            values.filter { it.field in INDEXED_FIELDS }.forEach { value ->
                repository.replaceExactIndexes(
                    row.externalCourierId,
                    value.field.criterionType(),
                    value.exactIndexes,
                    clock.instant(),
                )
            }
            val nextVersion = Math.addExact(row.version, 1)
            repository.advanceVersion(row.externalCourierId, row.version, nextVersion, clock.instant())
            val historyId = identifiers.next()
            repository.insertHistory(
                historyId,
                row.externalCourierId,
                prepared.profileChangeId,
                prepared.purpose(),
                prepared.risk(),
                row.version,
                nextVersion,
                before,
                after,
                clock.instant(),
            )
            val contacts = values.filter { it.field in INDEXED_FIELDS }
            val targets =
                if (contacts.isNotEmpty()) {
                    buildList {
                        contacts.forEach { value ->
                            row.values[value.field]?.let {
                                add(repository.insertTarget(identifiers.next(), historyId, ProfileNotificationTargetKind.OLD, it, clock.instant()))
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
                    listOfNotNull(row.preferredNotification()?.let {
                        repository.insertTarget(identifiers.next(), historyId, ProfileNotificationTargetKind.CURRENT, it, clock.instant())
                    })
                }
            OwnerProfileChangeResult(historyId, row.version, nextVersion, before, after, targets)
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Courier profile change is unavailable").also {
                it.initCause(failure)
            }
        }

    private fun applyReset(
        prepared: PreparedCourierProfileChange.ProviderReregistration,
        row: CourierProfileChangeRow,
    ): OwnerProfileChangeResult {
        val now = clock.instant()
        val historyId = identifiers.next()
        repository.insertHistory(
            historyId,
            row.externalCourierId,
            prepared.profileChangeId,
            "COURIER_PROVIDER_REREGISTRATION",
            "R4",
            row.version,
            row.version,
            "PROVIDER_ACCESS_PROTECTED",
            "REREGISTRATION_REQUESTED",
            now,
        )
        repository.insertResetIntent(identifiers.next(), row.externalCourierId, historyId, now)
        val targets =
            listOfNotNull(row.preferredNotification()?.let {
                repository.insertTarget(identifiers.next(), historyId, ProfileNotificationTargetKind.CURRENT, it, now)
            })
        return OwnerProfileChangeResult(
            historyId,
            row.version,
            row.version,
            "PROVIDER_ACCESS_PROTECTED",
            "REREGISTRATION_REQUESTED",
            targets,
        )
    }

    private fun protectLabel(
        courierId: UUID,
        field: PersonalDataField,
        raw: String,
    ): ProtectedProfileChangeValue {
        val normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        require(normalized.length in 1..200 && normalized.none(Char::isISOControl)) { "Courier label is invalid" }
        return ProtectedProfileChangeValue(
            field,
            encrypt(courierId, field, normalized),
            PersonalDataMasker.maskDisplayLabel(normalized),
        )
    }

    private fun protectContact(
        courierId: UUID,
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
            encrypt(courierId, field, canonical),
            masked,
            blindIndexes.generate(normalized, blindIndexes.activeSearchKeyVersions()),
        )
    }

    private fun protectOpaque(
        courierId: UUID,
        field: PersonalDataField,
        raw: String,
    ): ProtectedProfileChangeValue {
        val normalized =
            Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC).also {
                require(it.matches(Regex("^[A-Za-z0-9:_-]{4,200}$"))) { "Courier opaque reference is invalid" }
            }
        return ProtectedProfileChangeValue(field, encrypt(courierId, field, normalized), "***${normalized.takeLast(4)}")
    }

    private fun encrypt(
        courierId: UUID,
        field: PersonalDataField,
        plaintext: String,
    ): EncryptedPersonalData =
        crypto.encrypt(
            plaintext.toByteArray(StandardCharsets.UTF_8),
            PersonalDataEncryptionContext(PersonalDataOwnerContext.DELIVERY, courierId, field),
        )

    private fun requireVersion(version: Long): Long = version.also { require(it >= 0) }

    private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Courier profile version is stale")

    private companion object {
        val INDEXED_FIELDS = setOf(PersonalDataField.RELAY_PHONE, PersonalDataField.RELAY_EMAIL)
    }
}

private fun PreparedCourierProfileChange.values(): List<ProtectedProfileChangeValue> =
    when (this) {
        is PreparedCourierProfileChange.DisplayName -> listOf(value)
        is PreparedCourierProfileChange.RelayContact -> values
        is PreparedCourierProfileChange.ProviderIdentity -> listOf(value)
        is PreparedCourierProfileChange.PayoutReference -> listOf(value)
        is PreparedCourierProfileChange.ProviderReregistration -> emptyList()
    }

private fun PreparedCourierProfileChange.purpose(): String =
    when (this) {
        is PreparedCourierProfileChange.DisplayName -> "COURIER_DISPLAY_NAME"
        is PreparedCourierProfileChange.RelayContact -> "COURIER_RELAY_CONTACT"
        is PreparedCourierProfileChange.ProviderIdentity -> "COURIER_PROVIDER_IDENTITY"
        is PreparedCourierProfileChange.PayoutReference -> "COURIER_PAYOUT_REFERENCE"
        is PreparedCourierProfileChange.ProviderReregistration -> "COURIER_PROVIDER_REREGISTRATION"
    }

private fun PreparedCourierProfileChange.risk(): String =
    when (this) {
        is PreparedCourierProfileChange.DisplayName -> "R1"
        is PreparedCourierProfileChange.RelayContact -> "R2"
        is PreparedCourierProfileChange.ProviderIdentity,
        is PreparedCourierProfileChange.PayoutReference,
        -> "R3"
        is PreparedCourierProfileChange.ProviderReregistration -> "R4"
    }

internal data class CourierNotificationValue(
    val channel: ProfileNotificationChannel,
    val encrypted: EncryptedPersonalData,
    val masked: String,
)

private fun ProtectedProfileChangeValue.notification(): CourierNotificationValue =
    CourierNotificationValue(field.criterionType().channel(), encrypted, masked)

private fun PersonalDataField.criterionType(): ExactSearchCriterionType =
    when (this) {
        PersonalDataField.RELAY_PHONE -> ExactSearchCriterionType.PHONE
        PersonalDataField.RELAY_EMAIL -> ExactSearchCriterionType.EMAIL
        else -> throw IllegalArgumentException("Courier field is not a relay contact")
    }

private fun ExactSearchCriterionType.channel(): ProfileNotificationChannel =
    if (this == ExactSearchCriterionType.PHONE) ProfileNotificationChannel.PHONE else ProfileNotificationChannel.EMAIL

internal data class CourierProfileChangeRow(
    val externalCourierId: UUID,
    val values: Map<PersonalDataField, CourierNotificationValue>,
    val version: Long,
) {
    fun preferredNotification(): CourierNotificationValue? =
        values[PersonalDataField.RELAY_PHONE] ?: values[PersonalDataField.RELAY_EMAIL]
}

@Repository
internal class ExternalCourierSupportProfileChangeRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun currentVersion(courierId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT version FROM delivery_external_courier_support_profile WHERE external_courier_id = ?",
            Long::class.java,
            courierId,
        ) ?: notFound()

    fun lock(courierId: UUID): CourierProfileChangeRow =
        jdbcTemplate
            .query(
                """
                SELECT external_courier_id,
                       display_name_ciphertext, display_name_key_version, display_name_aad_version, masked_display_name,
                       relay_phone_ciphertext, relay_phone_key_version, relay_phone_aad_version, masked_relay_phone,
                       relay_email_ciphertext, relay_email_key_version, relay_email_aad_version, masked_relay_email,
                       provider_courier_reference_ciphertext, provider_courier_reference_key_version,
                       provider_courier_reference_aad_version, masked_provider_courier_reference,
                       payout_reference_ciphertext, payout_reference_key_version, payout_reference_aad_version,
                       masked_payout_reference, version
                  FROM delivery_external_courier_support_profile
                 WHERE external_courier_id = ? FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    CourierProfileChangeRow(
                        rs.getObject("external_courier_id", UUID::class.java),
                        buildMap {
                            rs.value("display_name", "masked_display_name", ProfileNotificationChannel.EMAIL)
                                ?.let { put(PersonalDataField.DISPLAY_NAME, it) }
                            rs.value("relay_phone", "masked_relay_phone", ProfileNotificationChannel.PHONE)
                                ?.let { put(PersonalDataField.RELAY_PHONE, it) }
                            rs.value("relay_email", "masked_relay_email", ProfileNotificationChannel.EMAIL)
                                ?.let { put(PersonalDataField.RELAY_EMAIL, it) }
                            rs.value(
                                "provider_courier_reference",
                                "masked_provider_courier_reference",
                                ProfileNotificationChannel.EMAIL,
                            )?.let { put(PersonalDataField.PROVIDER_COURIER_REFERENCE, it) }
                            rs.value("payout_reference", "masked_payout_reference", ProfileNotificationChannel.EMAIL)
                                ?.let { put(PersonalDataField.PAYOUT_REFERENCE, it) }
                        },
                        rs.getLong("version"),
                    )
                },
                courierId,
            ).singleOrNull() ?: notFound()

    fun updateValue(
        courierId: UUID,
        value: ProtectedProfileChangeValue,
    ) {
        val prefix =
            when (value.field) {
                PersonalDataField.DISPLAY_NAME -> "display_name"
                PersonalDataField.RELAY_PHONE -> "relay_phone"
                PersonalDataField.RELAY_EMAIL -> "relay_email"
                PersonalDataField.PROVIDER_COURIER_REFERENCE -> "provider_courier_reference"
                PersonalDataField.PAYOUT_REFERENCE -> "payout_reference"
                else -> throw IllegalArgumentException("Unsupported courier profile field")
            }
        val maskedColumn = if (value.field == PersonalDataField.DISPLAY_NAME) "masked_display_name" else "masked_$prefix"
        jdbcTemplate.update(
            "UPDATE delivery_external_courier_support_profile SET ${prefix}_ciphertext = ?, " +
                "${prefix}_key_version = ?, ${prefix}_aad_version = ?, $maskedColumn = ? WHERE external_courier_id = ?",
            value.encrypted.ciphertext,
            value.encrypted.keyVersion,
            value.encrypted.aadVersion,
            value.masked,
            courierId,
        )
    }

    fun advanceVersion(
        courierId: UUID,
        expected: Long,
        next: Long,
        now: Instant,
    ) {
        if (
            jdbcTemplate.update(
                "UPDATE delivery_external_courier_support_profile SET version = ?, updated_at = ? " +
                    "WHERE external_courier_id = ? AND version = ?",
                next,
                now,
                courierId,
                expected,
            ) != 1
        ) {
            throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Courier profile version is stale")
        }
    }

    fun replaceExactIndexes(
        courierId: UUID,
        type: ExactSearchCriterionType,
        indexes: List<BlindIndex>,
        now: Instant,
    ) {
        jdbcTemplate.update(
            "DELETE FROM delivery_external_courier_support_profile_exact_index " +
                "WHERE external_courier_id = ? AND criterion_type = ?",
            courierId,
            type.name,
        )
        indexes.forEach { index ->
            jdbcTemplate.update(
                """
                INSERT INTO delivery_external_courier_support_profile_exact_index (
                    external_courier_id, criterion_type, index_key_version, blind_index, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                courierId,
                type.name,
                index.keyVersion,
                index.digestBytes(),
                now,
            )
        }
    }

    @Suppress("LongParameterList")
    fun insertHistory(
        id: UUID,
        courierId: UUID,
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
            INSERT INTO delivery_courier_profile_change_history (
                id, external_courier_id, support_profile_change_id, purpose, risk_class,
                previous_version, current_version, masked_before, masked_after, changed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            courierId,
            supportChangeId,
            purpose,
            risk,
            previous,
            current,
            before,
            after,
            now,
        )
    }

    fun insertResetIntent(
        id: UUID,
        courierId: UUID,
        historyId: UUID,
        now: Instant,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO delivery_courier_profile_reset_intent (
                id, external_courier_id, profile_change_history_id, intent_type, state, created_at
            ) VALUES (?, ?, ?, 'PROVIDER_REREGISTRATION', 'REQUESTED', ?)
            """.trimIndent(),
            id,
            courierId,
            historyId,
            now,
        )
    }

    fun insertTarget(
        id: UUID,
        historyId: UUID,
        kind: ProfileNotificationTargetKind,
        value: CourierNotificationValue,
        now: Instant,
    ): OwnerProfileNotificationTarget {
        jdbcTemplate.update(
            """
            INSERT INTO delivery_courier_profile_notification_target (
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
            now,
        )
        return OwnerProfileNotificationTarget(id, kind, value.channel, value.masked)
    }

    fun findResult(profileChangeId: UUID): OwnerProfileChangeResult? {
        val result =
            jdbcTemplate
                .query(
                    """
                    SELECT id, previous_version, current_version, masked_before, masked_after
                      FROM delivery_courier_profile_change_history
                     WHERE support_profile_change_id = ?
                    """.trimIndent(),
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
                """
                SELECT id, target_kind, channel_type, masked_destination
                  FROM delivery_courier_profile_notification_target
                 WHERE profile_change_history_id = ?
                 ORDER BY target_kind, channel_type, id
                """.trimIndent(),
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

    fun notificationTarget(targetId: UUID): CourierOwnerNotificationSnapshot =
        jdbcTemplate
            .query(
                """
                SELECT target.target_kind, target.channel_type, target.destination_ciphertext,
                       target.destination_key_version, target.destination_aad_version, history.external_courier_id
                  FROM delivery_courier_profile_notification_target target
                  JOIN delivery_courier_profile_change_history history ON history.id = target.profile_change_history_id
                 WHERE target.id = ?
                """.trimIndent(),
                { rs, _ ->
                    CourierOwnerNotificationSnapshot(
                        rs.getObject("external_courier_id", UUID::class.java),
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

    private fun java.sql.ResultSet.value(
        prefix: String,
        maskedColumn: String,
        channel: ProfileNotificationChannel,
    ): CourierNotificationValue? =
        getString("${prefix}_ciphertext")?.let {
            CourierNotificationValue(
                channel,
                EncryptedPersonalData(it, getInt("${prefix}_key_version"), getInt("${prefix}_aad_version")),
                getString(maskedColumn),
            )
        }

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Courier profile was not found")
}

internal data class CourierOwnerNotificationSnapshot(
    val ownerId: UUID,
    val kind: ProfileNotificationTargetKind,
    val channel: ProfileNotificationChannel,
    val encrypted: EncryptedPersonalData,
)
