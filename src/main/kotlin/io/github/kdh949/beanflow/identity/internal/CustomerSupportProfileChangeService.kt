package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileChangeOperations
import io.github.kdh949.beanflow.identity.api.PrepareCustomerCredentialReset
import io.github.kdh949.beanflow.identity.api.PrepareCustomerDisplayNameCorrection
import io.github.kdh949.beanflow.identity.api.PrepareCustomerLegalNameCorrection
import io.github.kdh949.beanflow.identity.api.PrepareCustomerPrimaryPhoneChange
import io.github.kdh949.beanflow.identity.api.PreparedCustomerProfileChange
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
internal class CustomerSupportProfileChangeService(
    private val repository: CustomerSupportProfileChangeRepository,
    private val crypto: PersonalDataCryptoPort,
    private val blindIndexes: KeyedBlindIndexPort,
    private val identifiers: IdentifierSource,
    private val clock: Clock,
) : CustomerSupportProfileChangeOperations {
    @Transactional(readOnly = true)
    override fun currentVersion(customerId: UUID): Long = repository.currentVersion(customerId)

    override fun resolveNotificationTarget(targetId: UUID): ResolvedProfileNotificationTarget {
        val target = repository.notificationTarget(targetId)
        val field =
            if (target.channel == ProfileNotificationChannel.PHONE) {
                PersonalDataField.PRIMARY_PHONE
            } else {
                PersonalDataField.PRIMARY_EMAIL
            }
        val destination =
            crypto.decrypt(
                target.encrypted,
                PersonalDataEncryptionContext(PersonalDataOwnerContext.IDENTITY, target.ownerId, field),
            )
        return ResolvedProfileNotificationTarget(targetId, target.kind, target.channel, destination)
    }

    override fun prepareDisplayName(
        command: PrepareCustomerDisplayNameCorrection,
    ): PreparedCustomerProfileChange.DisplayName =
        PreparedCustomerProfileChange.DisplayName(
            command.profileChangeId,
            command.customerId,
            requireVersion(command.expectedVersion),
            protectLabel(command.customerId, PersonalDataField.DISPLAY_NAME, command.displayName),
        )

    override fun prepareLegalName(
        command: PrepareCustomerLegalNameCorrection,
    ): PreparedCustomerProfileChange.LegalName =
        PreparedCustomerProfileChange.LegalName(
            command.profileChangeId,
            command.customerId,
            requireVersion(command.expectedVersion),
            protectLabel(command.customerId, PersonalDataField.LEGAL_NAME, command.legalName),
        )

    override fun preparePrimaryPhone(
        command: PrepareCustomerPrimaryPhoneChange,
    ): PreparedCustomerProfileChange.PrimaryPhone {
        val version = requireVersion(command.expectedVersion)
        val normalized = PersonalDataNormalizer.normalize(ExactSearchCriterionType.PHONE, command.primaryPhone)
        val canonicalPhone = PersonalDataNormalizer.normalizePhoneForMasking(command.primaryPhone)
        val encrypted = encrypt(command.customerId, PersonalDataField.PRIMARY_PHONE, canonicalPhone)
        return PreparedCustomerProfileChange.PrimaryPhone(
            command.profileChangeId,
            command.customerId,
            version,
            ProtectedProfileChangeValue(
                PersonalDataField.PRIMARY_PHONE,
                encrypted,
                PersonalDataMasker.maskPhone(canonicalPhone),
                blindIndexes.generate(normalized, blindIndexes.activeSearchKeyVersions()),
            ),
        )
    }

    override fun prepareCredentialReset(
        command: PrepareCustomerCredentialReset,
    ): PreparedCustomerProfileChange.CredentialReset =
        PreparedCustomerProfileChange.CredentialReset(
            command.profileChangeId,
            command.customerId,
            requireVersion(command.expectedVersion),
        )

    @Transactional(propagation = Propagation.MANDATORY)
    override fun apply(prepared: PreparedCustomerProfileChange): OwnerProfileChangeResult =
        try {
            repository.findResult(prepared.profileChangeId)?.let { return it }
            val row = repository.lock(prepared.customerId)
            if (row.version != prepared.expectedVersion) stale()
            when (prepared) {
                is PreparedCustomerProfileChange.DisplayName -> applyValue(prepared, row, "CUSTOMER_DISPLAY_NAME", "R1")
                is PreparedCustomerProfileChange.LegalName -> applyValue(prepared, row, "CUSTOMER_LEGAL_NAME_TYPO", "R2")
                is PreparedCustomerProfileChange.PrimaryPhone -> applyValue(prepared, row, "CUSTOMER_PRIMARY_PHONE", "R3")
                is PreparedCustomerProfileChange.CredentialReset -> applyReset(prepared, row)
            }
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer profile change is unavailable").also {
                it.initCause(failure)
            }
        }

    private fun applyValue(
        prepared: PreparedCustomerProfileChange,
        row: CustomerProfileChangeRow,
        purpose: String,
        risk: String,
    ): OwnerProfileChangeResult {
        val value =
            when (prepared) {
                is PreparedCustomerProfileChange.DisplayName -> prepared.value
                is PreparedCustomerProfileChange.LegalName -> prepared.value
                is PreparedCustomerProfileChange.PrimaryPhone -> prepared.value
                is PreparedCustomerProfileChange.CredentialReset -> error("Reset does not contain a protected value")
            }
        val before = row.masked(value.field) ?: "NOT_SET"
        val nextVersion = Math.addExact(row.version, 1)
        repository.update(row.customerId, row.version, value, nextVersion, clock.instant())
        if (value.field == PersonalDataField.PRIMARY_PHONE) {
            repository.replaceExactIndexes(row.customerId, ExactSearchCriterionType.PHONE, value.exactIndexes, clock.instant())
        }
        val historyId = identifiers.next()
        repository.insertHistory(
            historyId,
            row.customerId,
            prepared.profileChangeId,
            purpose,
            risk,
            row.version,
            nextVersion,
            before,
            value.masked,
            clock.instant(),
        )
        val targets =
            if (value.field == PersonalDataField.PRIMARY_PHONE) {
                buildList {
                    row.primaryPhone?.let {
                        add(repository.insertTarget(identifiers.next(), historyId, ProfileNotificationTargetKind.OLD, it, clock.instant()))
                    }
                    add(
                        repository.insertTarget(
                            identifiers.next(),
                            historyId,
                            ProfileNotificationTargetKind.NEW,
                            NotificationValue(ProfileNotificationChannel.PHONE, value.encrypted, value.masked),
                            clock.instant(),
                        ),
                    )
                }
            } else {
                listOfNotNull(row.preferredNotificationValue()?.let {
                    repository.insertTarget(identifiers.next(), historyId, ProfileNotificationTargetKind.CURRENT, it, clock.instant())
                })
            }
        return OwnerProfileChangeResult(historyId, row.version, nextVersion, before, value.masked, targets)
    }

    private fun applyReset(
        prepared: PreparedCustomerProfileChange.CredentialReset,
        row: CustomerProfileChangeRow,
    ): OwnerProfileChangeResult {
        val now = clock.instant()
        val historyId = identifiers.next()
        repository.insertHistory(
            historyId,
            row.customerId,
            prepared.profileChangeId,
            "CUSTOMER_CREDENTIAL_RESET",
            "R4",
            row.version,
            row.version,
            "CREDENTIAL_PROTECTED",
            "RESET_REQUESTED",
            now,
        )
        repository.insertResetIntent(identifiers.next(), row.customerId, historyId, now)
        val targets =
            listOfNotNull(row.preferredNotificationValue()?.let {
                repository.insertTarget(identifiers.next(), historyId, ProfileNotificationTargetKind.CURRENT, it, now)
            })
        return OwnerProfileChangeResult(
            historyId,
            row.version,
            row.version,
            "CREDENTIAL_PROTECTED",
            "RESET_REQUESTED",
            targets,
        )
    }

    private fun protectLabel(
        customerId: UUID,
        field: PersonalDataField,
        raw: String,
    ): ProtectedProfileChangeValue {
        val normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        require(normalized.length in 1..200 && normalized.none(Char::isISOControl)) { "Profile label is invalid" }
        return ProtectedProfileChangeValue(
            field,
            encrypt(customerId, field, normalized),
            PersonalDataMasker.maskDisplayLabel(normalized),
        )
    }

    private fun encrypt(
        customerId: UUID,
        field: PersonalDataField,
        plaintext: String,
    ): EncryptedPersonalData =
        crypto.encrypt(
            plaintext.toByteArray(StandardCharsets.UTF_8),
            PersonalDataEncryptionContext(PersonalDataOwnerContext.IDENTITY, customerId, field),
        )

    private fun requireVersion(version: Long): Long = version.also { require(it >= 0) { "Profile version cannot be negative" } }

    private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Customer profile version is stale")
}

internal data class NotificationValue(
    val channel: ProfileNotificationChannel,
    val encrypted: EncryptedPersonalData,
    val masked: String,
)

internal data class CustomerProfileChangeRow(
    val customerId: UUID,
    val displayName: NotificationValue,
    val legalName: NotificationValue?,
    val primaryPhone: NotificationValue?,
    val primaryEmail: NotificationValue?,
    val version: Long,
) {
    fun masked(field: PersonalDataField): String? =
        when (field) {
            PersonalDataField.DISPLAY_NAME -> displayName.masked
            PersonalDataField.LEGAL_NAME -> legalName?.masked
            PersonalDataField.PRIMARY_PHONE -> primaryPhone?.masked
            else -> null
        }

    fun preferredNotificationValue(): NotificationValue? = primaryPhone ?: primaryEmail
}

@Repository
internal class CustomerSupportProfileChangeRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun currentVersion(customerId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT version FROM identity_customer_support_profile WHERE customer_id = ?",
            Long::class.java,
            customerId,
        ) ?: notFound()

    fun lock(customerId: UUID): CustomerProfileChangeRow =
        jdbcTemplate
            .query(
                """
                SELECT customer_id,
                       display_name_ciphertext, display_name_key_version, display_name_aad_version, masked_display_name,
                       legal_name_ciphertext, legal_name_key_version, legal_name_aad_version, masked_legal_name,
                       primary_phone_ciphertext, primary_phone_key_version, primary_phone_aad_version, masked_primary_phone,
                       primary_email_ciphertext, primary_email_key_version, primary_email_aad_version, masked_primary_email,
                       version
                  FROM identity_customer_support_profile
                 WHERE customer_id = ?
                   FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    CustomerProfileChangeRow(
                        rs.getObject("customer_id", UUID::class.java),
                        rs.notificationValue("display_name", ProfileNotificationChannel.EMAIL),
                        rs.optionalNotificationValue("legal_name", ProfileNotificationChannel.EMAIL),
                        rs.optionalNotificationValue("primary_phone", ProfileNotificationChannel.PHONE),
                        rs.optionalNotificationValue("primary_email", ProfileNotificationChannel.EMAIL),
                        rs.getLong("version"),
                    )
                },
                customerId,
            ).singleOrNull() ?: notFound()

    fun update(
        customerId: UUID,
        expectedVersion: Long,
        value: ProtectedProfileChangeValue,
        nextVersion: Long,
        now: Instant,
    ) {
        val prefix =
            when (value.field) {
                PersonalDataField.DISPLAY_NAME -> "display_name"
                PersonalDataField.LEGAL_NAME -> "legal_name"
                PersonalDataField.PRIMARY_PHONE -> "primary_phone"
                else -> throw IllegalArgumentException("Unsupported customer profile field")
            }
        val maskedColumn = if (value.field == PersonalDataField.LEGAL_NAME) "masked_legal_name" else "masked_$prefix"
        val changed =
            jdbcTemplate.update(
                """
                UPDATE identity_customer_support_profile
                   SET ${prefix}_ciphertext = ?, ${prefix}_key_version = ?, ${prefix}_aad_version = ?,
                       $maskedColumn = ?, updated_at = ?, version = ?
                 WHERE customer_id = ? AND version = ?
                """.trimIndent(),
                value.encrypted.ciphertext,
                value.encrypted.keyVersion,
                value.encrypted.aadVersion,
                value.masked,
                Timestamp.from(now),
                nextVersion,
                customerId,
                expectedVersion,
            )
        if (changed != 1) throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Customer profile version is stale")
    }

    fun replaceExactIndexes(
        customerId: UUID,
        type: ExactSearchCriterionType,
        indexes: List<BlindIndex>,
        now: Instant,
    ) {
        jdbcTemplate.update(
            "DELETE FROM identity_customer_support_profile_exact_index WHERE customer_id = ? AND criterion_type = ?",
            customerId,
            type.name,
        )
        indexes.forEach { index ->
            jdbcTemplate.update(
                """
                INSERT INTO identity_customer_support_profile_exact_index (
                    customer_id, criterion_type, index_key_version, blind_index, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                customerId,
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
        customerId: UUID,
        supportProfileChangeId: UUID,
        purpose: String,
        risk: String,
        previousVersion: Long,
        currentVersion: Long,
        maskedBefore: String,
        maskedAfter: String,
        now: Instant,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO identity_customer_profile_change_history (
                id, customer_id, support_profile_change_id, purpose, risk_class, previous_version,
                current_version, masked_before, masked_after, changed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            customerId,
            supportProfileChangeId,
            purpose,
            risk,
            previousVersion,
            currentVersion,
            maskedBefore,
            maskedAfter,
            Timestamp.from(now),
        )
    }

    fun insertResetIntent(
        id: UUID,
        customerId: UUID,
        historyId: UUID,
        now: Instant,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO identity_customer_profile_reset_intent (
                id, customer_id, profile_change_history_id, intent_type, state, created_at
            ) VALUES (?, ?, ?, 'CREDENTIAL_RESET', 'REQUESTED', ?)
            """.trimIndent(),
            id,
            customerId,
            historyId,
            Timestamp.from(now),
        )
    }

    fun insertTarget(
        id: UUID,
        historyId: UUID,
        kind: ProfileNotificationTargetKind,
        value: NotificationValue,
        now: Instant,
    ): OwnerProfileNotificationTarget {
        jdbcTemplate.update(
            """
            INSERT INTO identity_customer_profile_notification_target (
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

    fun findResult(profileChangeId: UUID): OwnerProfileChangeResult? {
        val history =
            jdbcTemplate
                .query(
                    """
                    SELECT id, previous_version, current_version, masked_before, masked_after
                      FROM identity_customer_profile_change_history
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
                  FROM identity_customer_profile_notification_target
                 WHERE profile_change_history_id = ?
                 ORDER BY CASE target_kind WHEN 'OLD' THEN 0 WHEN 'NEW' THEN 1 ELSE 2 END,
                          channel_type, id
                """.trimIndent(),
                { rs, _ ->
                    OwnerProfileNotificationTarget(
                        rs.getObject("id", UUID::class.java),
                        ProfileNotificationTargetKind.valueOf(rs.getString("target_kind")),
                        ProfileNotificationChannel.valueOf(rs.getString("channel_type")),
                        rs.getString("masked_destination"),
                    )
                },
                history.ownerChangeId,
            )
        return history.copy(notificationTargets = targets)
    }

    fun notificationTarget(targetId: UUID): CustomerOwnerNotificationSnapshot =
        jdbcTemplate
            .query(
                """
                SELECT target.target_kind, target.channel_type, target.destination_ciphertext,
                       target.destination_key_version, target.destination_aad_version, history.customer_id
                  FROM identity_customer_profile_notification_target target
                  JOIN identity_customer_profile_change_history history ON history.id = target.profile_change_history_id
                 WHERE target.id = ?
                """.trimIndent(),
                { rs, _ ->
                    CustomerOwnerNotificationSnapshot(
                        rs.getObject("customer_id", UUID::class.java),
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

    private fun java.sql.ResultSet.notificationValue(
        prefix: String,
        channel: ProfileNotificationChannel,
    ): NotificationValue = requireNotNull(optionalNotificationValue(prefix, channel))

    private fun java.sql.ResultSet.optionalNotificationValue(
        prefix: String,
        channel: ProfileNotificationChannel,
    ): NotificationValue? =
        getString("${prefix}_ciphertext")?.let {
            NotificationValue(
                channel,
                EncryptedPersonalData(it, getInt("${prefix}_key_version"), getInt("${prefix}_aad_version")),
                getString(if (prefix == "legal_name") "masked_legal_name" else "masked_$prefix"),
            )
        }

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Customer profile was not found")
}

internal data class CustomerOwnerNotificationSnapshot(
    val ownerId: UUID,
    val kind: ProfileNotificationTargetKind,
    val channel: ProfileNotificationChannel,
    val encrypted: EncryptedPersonalData,
)
