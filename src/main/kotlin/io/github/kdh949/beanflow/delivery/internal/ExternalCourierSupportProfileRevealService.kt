package io.github.kdh949.beanflow.delivery.internal

import io.github.kdh949.beanflow.delivery.api.ExternalCourierSupportProfileRevealOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.EncryptedPersonalData
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OwnerPersonalDataDecryptOperations
import io.github.kdh949.beanflow.shared.api.PersonalDataField
import io.github.kdh949.beanflow.shared.api.PersonalDataOwnerContext
import io.github.kdh949.beanflow.shared.api.RevealPersonalDataCommand
import io.github.kdh949.beanflow.shared.api.RevealedPersonalData
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class ExternalCourierSupportProfileRevealService(
    private val repository: ExternalCourierSupportProfileRevealRepository,
    private val decryptor: OwnerPersonalDataDecryptOperations,
) : ExternalCourierSupportProfileRevealOperations {
    override fun reveal(command: RevealPersonalDataCommand): RevealedPersonalData {
        require(command.fields.isNotEmpty() && command.fields.all { it in ALLOWED_FIELDS }) { "Courier reveal field is invalid" }
        val encrypted = repository.load(command.subjectId, command.fields)
        if (encrypted.keys !=
            command.fields
        ) {
            throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Courier support profile field was not found")
        }
        return decryptor.decrypt(PersonalDataOwnerContext.DELIVERY, command.subjectId, encrypted)
    }

    private companion object {
        val ALLOWED_FIELDS =
            setOf(
                PersonalDataField.DISPLAY_NAME,
                PersonalDataField.PROVIDER_COURIER_REFERENCE,
                PersonalDataField.RELAY_PHONE,
                PersonalDataField.RELAY_EMAIL,
            )
    }
}

@Repository
internal class ExternalCourierSupportProfileRevealRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun load(
        courierId: UUID,
        fields: Set<PersonalDataField>,
    ): Map<PersonalDataField, EncryptedPersonalData> =
        try {
            jdbcTemplate
                .query(
                    """
                    SELECT provider_courier_reference_ciphertext, provider_courier_reference_key_version,
                           provider_courier_reference_aad_version,
                           display_name_ciphertext, display_name_key_version, display_name_aad_version,
                           relay_phone_ciphertext, relay_phone_key_version, relay_phone_aad_version,
                           relay_email_ciphertext, relay_email_key_version, relay_email_aad_version
                      FROM delivery_external_courier_support_profile
                     WHERE external_courier_id = ?
                    """.trimIndent(),
                    { rs, _ ->
                        buildMap {
                            fields.forEach { field ->
                                val prefix =
                                    when (field) {
                                        PersonalDataField.PROVIDER_COURIER_REFERENCE -> "provider_courier_reference"
                                        PersonalDataField.DISPLAY_NAME -> "display_name"
                                        PersonalDataField.RELAY_PHONE -> "relay_phone"
                                        PersonalDataField.RELAY_EMAIL -> "relay_email"
                                        else -> error("Unsupported courier field")
                                    }
                                rs.getString("${prefix}_ciphertext")?.let { ciphertext ->
                                    put(
                                        field,
                                        EncryptedPersonalData(
                                            ciphertext,
                                            rs.getInt("${prefix}_key_version"),
                                            rs.getInt("${prefix}_aad_version"),
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    courierId,
                ).singleOrNull() ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Courier support profile was not found")
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Courier support profile reveal is unavailable").also {
                it.initCause(failure)
            }
        }
}
