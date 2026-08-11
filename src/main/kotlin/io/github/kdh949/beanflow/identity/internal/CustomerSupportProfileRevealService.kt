package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileRevealOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.EncryptedPersonalData
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.PersonalDataField
import io.github.kdh949.beanflow.shared.api.PersonalDataOwnerContext
import io.github.kdh949.beanflow.shared.api.RevealPersonalDataCommand
import io.github.kdh949.beanflow.shared.api.RevealedPersonalData
import io.github.kdh949.beanflow.shared.internal.OwnerPersonalDataDecryptor
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class CustomerSupportProfileRevealService(
    private val repository: CustomerSupportProfileRevealRepository,
    private val decryptor: OwnerPersonalDataDecryptor,
) : CustomerSupportProfileRevealOperations {
    override fun reveal(command: RevealPersonalDataCommand): RevealedPersonalData {
        require(command.fields.isNotEmpty() && command.fields.all { it in ALLOWED_FIELDS }) { "Customer reveal field is invalid" }
        val encrypted = repository.load(command.subjectId, command.fields)
        if (encrypted.keys !=
            command.fields
        ) {
            throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Customer support profile field was not found")
        }
        return decryptor.decrypt(PersonalDataOwnerContext.IDENTITY, command.subjectId, encrypted)
    }

    private companion object {
        val ALLOWED_FIELDS = setOf(PersonalDataField.DISPLAY_NAME, PersonalDataField.PRIMARY_PHONE, PersonalDataField.PRIMARY_EMAIL)
    }
}

@Repository
internal class CustomerSupportProfileRevealRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun load(
        customerId: UUID,
        fields: Set<PersonalDataField>,
    ): Map<PersonalDataField, EncryptedPersonalData> =
        try {
            jdbcTemplate
                .query(
                    """
                    SELECT display_name_ciphertext, display_name_key_version, display_name_aad_version,
                           primary_phone_ciphertext, primary_phone_key_version, primary_phone_aad_version,
                           primary_email_ciphertext, primary_email_key_version, primary_email_aad_version
                      FROM identity_customer_support_profile
                     WHERE customer_id = ?
                    """.trimIndent(),
                    { rs, _ ->
                        buildMap {
                            fields.forEach { field ->
                                val prefix =
                                    when (field) {
                                        PersonalDataField.DISPLAY_NAME -> "display_name"
                                        PersonalDataField.PRIMARY_PHONE -> "primary_phone"
                                        PersonalDataField.PRIMARY_EMAIL -> "primary_email"
                                        else -> error("Unsupported customer field")
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
                    customerId,
                ).singleOrNull() ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Customer support profile was not found")
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer support profile reveal is unavailable").also {
                it.initCause(failure)
            }
        }
}
