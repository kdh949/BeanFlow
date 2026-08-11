package io.github.kdh949.beanflow.shared.api

import java.util.UUID

data class RevealPersonalDataCommand(
    val subjectId: UUID,
    val fields: Set<PersonalDataField>,
)

class RevealedPersonalData(
    val subjectId: UUID,
    values: Map<PersonalDataField, String>,
) {
    val values: Map<PersonalDataField, String> = values.toMap()

    init {
        require(this.values.isNotEmpty()) { "Revealed personal data must contain at least one field" }
        require(this.values.values.none(String::isEmpty)) { "Revealed personal data must not contain empty values" }
    }

    override fun toString(): String = "RevealedPersonalData(subjectId=$subjectId, fields=${values.keys}, values=<redacted>)"
}
