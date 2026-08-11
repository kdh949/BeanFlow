package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.EncryptedPersonalData
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.PersonalDataCryptoPort
import io.github.kdh949.beanflow.shared.api.PersonalDataEncryptionContext
import io.github.kdh949.beanflow.shared.api.PersonalDataField
import io.github.kdh949.beanflow.shared.api.PersonalDataOwnerContext
import io.github.kdh949.beanflow.shared.api.RevealedPersonalData
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.UUID

@Component
internal class OwnerPersonalDataDecryptor(
    private val crypto: PersonalDataCryptoPort,
) {
    fun decrypt(
        ownerContext: PersonalDataOwnerContext,
        subjectId: UUID,
        encrypted: Map<PersonalDataField, EncryptedPersonalData>,
    ): RevealedPersonalData {
        val values =
            encrypted.mapValues { (field, value) ->
                val plaintext =
                    try {
                        crypto.decrypt(value, PersonalDataEncryptionContext(ownerContext, subjectId, field))
                    } catch (failure: DomainFailure) {
                        throw failure
                    } catch (failure: RuntimeException) {
                        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Personal-data decryption is unavailable").also {
                            it.initCause(failure)
                        }
                    }
                try {
                    decodeUtf8(plaintext)
                } finally {
                    Arrays.fill(plaintext, 0)
                }
            }
        return RevealedPersonalData(subjectId, values)
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .also { require(it.isNotEmpty()) }
        } catch (failure: RuntimeException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Decrypted personal data is invalid").also {
                it.initCause(failure)
            }
        }
}
