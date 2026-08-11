package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileRevealOperations
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
internal class StoreSupportProfileRevealService(
    private val repository: StoreSupportProfileRevealRepository,
    private val decryptor: OwnerPersonalDataDecryptOperations,
) : StoreSupportProfileRevealOperations {
    override fun reveal(command: RevealPersonalDataCommand): RevealedPersonalData {
        require(command.fields.isNotEmpty() && command.fields.all { it in ALLOWED_FIELDS }) { "Store reveal field is invalid" }
        val encrypted = repository.load(command.subjectId, command.fields)
        if (encrypted.keys !=
            command.fields
        ) {
            throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store support profile field was not found")
        }
        return decryptor.decrypt(PersonalDataOwnerContext.MERCHANT, command.subjectId, encrypted)
    }

    private companion object {
        val ALLOWED_FIELDS = setOf(PersonalDataField.LEGAL_DISPLAY_NAME, PersonalDataField.SUPPORT_PHONE, PersonalDataField.SUPPORT_EMAIL)
    }
}

@Repository
internal class StoreSupportProfileRevealRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun load(
        storeId: UUID,
        fields: Set<PersonalDataField>,
    ): Map<PersonalDataField, EncryptedPersonalData> =
        try {
            jdbcTemplate
                .query(
                    """
                    SELECT legal_display_name_ciphertext, legal_display_name_key_version, legal_display_name_aad_version,
                           support_phone_ciphertext, support_phone_key_version, support_phone_aad_version,
                           support_email_ciphertext, support_email_key_version, support_email_aad_version
                      FROM merchant_store_support_profile
                     WHERE store_id = ?
                    """.trimIndent(),
                    { rs, _ ->
                        buildMap {
                            fields.forEach { field ->
                                val prefix =
                                    when (field) {
                                        PersonalDataField.LEGAL_DISPLAY_NAME -> "legal_display_name"
                                        PersonalDataField.SUPPORT_PHONE -> "support_phone"
                                        PersonalDataField.SUPPORT_EMAIL -> "support_email"
                                        else -> error("Unsupported store field")
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
                    storeId,
                ).singleOrNull() ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store support profile was not found")
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store support profile reveal is unavailable").also {
                it.initCause(failure)
            }
        }
}
