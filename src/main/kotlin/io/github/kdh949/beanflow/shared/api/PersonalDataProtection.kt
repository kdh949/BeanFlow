package io.github.kdh949.beanflow.shared.api

import java.net.IDN
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

enum class ExactSearchCriterionType {
    PHONE,
    EMAIL,
}

enum class PersonalDataOwnerContext {
    IDENTITY,
    MERCHANT,
    DELIVERY,
}

enum class PersonalDataField {
    DISPLAY_NAME,
    PRIMARY_PHONE,
    PRIMARY_EMAIL,
    LEGAL_DISPLAY_NAME,
    SUPPORT_PHONE,
    SUPPORT_EMAIL,
    PROVIDER_COURIER_REFERENCE,
    RELAY_PHONE,
    RELAY_EMAIL,
}

data class PersonalDataEncryptionContext(
    val ownerContext: PersonalDataOwnerContext,
    val subjectId: UUID,
    val field: PersonalDataField,
    val aadVersion: Int = 1,
) {
    init {
        require(aadVersion == 1) { "Unsupported personal-data AAD version" }
    }

    fun associatedData(): ByteArray {
        val owner = ownerContext.name
        val subject = subjectId.toString()
        val fieldName = field.name
        return listOf(
            "beanflow-personal-data:v$aadVersion",
            "${owner.toByteArray(StandardCharsets.UTF_8).size}:$owner",
            "${subject.toByteArray(StandardCharsets.UTF_8).size}:$subject",
            "${fieldName.toByteArray(StandardCharsets.UTF_8).size}:$fieldName",
        ).joinToString("|").toByteArray(StandardCharsets.UTF_8)
    }
}

data class EncryptedPersonalData(
    val ciphertext: String,
    val keyVersion: Int,
    val aadVersion: Int,
) {
    init {
        require(keyVersion > 0) { "Personal-data key version must be positive" }
        require(aadVersion == 1) { "Unsupported personal-data AAD version" }
        val encodedVersion =
            CIPHERTEXT_PATTERN
                .matchEntire(ciphertext)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        require(encodedVersion == keyVersion) { "Personal-data ciphertext version metadata is inconsistent" }
    }

    override fun toString(): String = "EncryptedPersonalData(keyVersion=$keyVersion, aadVersion=$aadVersion, ciphertext=<redacted>)"

    private companion object {
        val CIPHERTEXT_PATTERN = Regex("^vault:v([1-9][0-9]*):[^\\s]{1,16000}$")
    }
}

class BlindIndex(
    val keyVersion: Int,
    digest: ByteArray,
) {
    private val value = digest.copyOf()

    init {
        require(keyVersion > 0) { "Blind-index key version must be positive" }
        require(value.size == 32) { "Blind index must be a SHA-256 digest" }
    }

    fun digestBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is BlindIndex && keyVersion == other.keyVersion && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * keyVersion + value.contentHashCode()

    override fun toString(): String = "BlindIndex(keyVersion=$keyVersion, digest=<redacted>)"
}

interface PersonalDataCryptoPort {
    fun encrypt(
        plaintext: ByteArray,
        context: PersonalDataEncryptionContext,
    ): EncryptedPersonalData

    fun decrypt(
        encrypted: EncryptedPersonalData,
        context: PersonalDataEncryptionContext,
    ): ByteArray

    fun rewrap(
        encrypted: EncryptedPersonalData,
        context: PersonalDataEncryptionContext,
    ): EncryptedPersonalData
}

interface KeyedBlindIndexPort {
    fun writeKeyVersion(): Int

    fun activeSearchKeyVersions(): Set<Int>

    fun generate(
        normalizedValue: NormalizedExactSearchValue,
        keyVersions: Set<Int>,
    ): List<BlindIndex>
}

class ProtectedProfileExactQuery(
    val criterionType: ExactSearchCriterionType,
    indexes: List<BlindIndex>,
    val limit: Int,
) {
    val indexes: List<BlindIndex> = indexes.sortedBy(BlindIndex::keyVersion).toList()

    init {
        require(this.indexes.isNotEmpty() && this.indexes.size <= 8) { "Exact query requires one to eight key versions" }
        require(
            this.indexes
                .map(BlindIndex::keyVersion)
                .distinct()
                .size == this.indexes.size,
        ) {
            "Exact query key versions must be unique"
        }
        require(limit in 1..21) { "Exact query limit must be between one and twenty-one" }
    }

    override fun toString(): String =
        "ProtectedProfileExactQuery(type=$criterionType, versions=${indexes.map(BlindIndex::keyVersion)}, value=<redacted>, limit=$limit)"
}

class NormalizedExactSearchValue internal constructor(
    val criterionType: ExactSearchCriterionType,
    canonicalBytes: ByteArray,
) {
    private val canonical = canonicalBytes.copyOf()

    fun canonicalBytes(): ByteArray = canonical.copyOf()

    override fun toString(): String = "NormalizedExactSearchValue(type=$criterionType, value=<redacted>)"
}

object PersonalDataNormalizer {
    private const val DOMAIN = "beanflow-exact-search:v1"
    private val phonePattern = Regex("^\\+[1-9][0-9]{7,14}$")
    private val emailLocalPattern = Regex("^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$")

    fun normalize(
        criterionType: ExactSearchCriterionType,
        rawValue: String,
    ): NormalizedExactSearchValue {
        val normalized =
            when (criterionType) {
                ExactSearchCriterionType.PHONE -> normalizePhone(rawValue)
                ExactSearchCriterionType.EMAIL -> normalizeEmail(rawValue)
            }
        val normalizedBytes = normalized.toByteArray(StandardCharsets.UTF_8)
        val type = criterionType.name
        val canonical = "$DOMAIN|${type.length}:$type|${normalizedBytes.size}:$normalized"
        return NormalizedExactSearchValue(criterionType, canonical.toByteArray(StandardCharsets.UTF_8))
    }

    internal fun normalizePhoneForMasking(rawValue: String): String = normalizePhone(rawValue)

    internal fun normalizeEmailForMasking(rawValue: String): String = normalizeEmail(rawValue)

    private fun normalizePhone(rawValue: String): String {
        val nfkc = Normalizer.normalize(rawValue, Normalizer.Form.NFKC).trim()
        if (nfkc.isEmpty()) invalid()
        val compact = StringBuilder(nfkc.length)
        nfkc.codePoints().forEach { codePoint ->
            when {
                Character.isISOControl(codePoint) -> invalid()
                Character.isWhitespace(codePoint) || codePoint in PHONE_SEPARATORS -> Unit
                codePoint == '+'.code || Character.isDigit(codePoint) -> compact.appendCodePoint(codePoint)
                else -> invalid()
            }
        }
        val ascii = compact.toString()
        val international =
            when {
                ascii.startsWith("+") -> ascii
                ascii.startsWith("00") -> "+${ascii.drop(2)}"
                ascii.startsWith("0") -> "+82${ascii.drop(1)}"
                else -> invalid()
            }
        if (!phonePattern.matches(international)) invalid()
        return international
    }

    private fun normalizeEmail(rawValue: String): String {
        val nfkc = Normalizer.normalize(rawValue, Normalizer.Form.NFKC).trim()
        if (nfkc.isEmpty() || nfkc.any { it.isISOControl() || it.isWhitespace() }) invalid()
        val separator = nfkc.indexOf('@')
        if (separator <= 0 || separator != nfkc.lastIndexOf('@') || separator == nfkc.lastIndex) invalid()
        val local = nfkc.substring(0, separator).lowercase(Locale.ROOT)
        val unicodeDomain = nfkc.substring(separator + 1).lowercase(Locale.ROOT)
        if (local.length > 64 || !emailLocalPattern.matches(local) || local.startsWith('.') || local.endsWith('.') || ".." in local) {
            invalid()
        }
        val asciiDomain =
            try {
                IDN.toASCII(unicodeDomain, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) {
                invalid()
            }
        if (
            asciiDomain.isEmpty() ||
            asciiDomain.length > 253 ||
            asciiDomain.startsWith('.') ||
            asciiDomain.endsWith('.') ||
            asciiDomain.split('.').any { it.isEmpty() || it.length > 63 } ||
            local.length + 1 + asciiDomain.length > 320
        ) {
            invalid()
        }
        return "$local@$asciiDomain"
    }

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Search criterion is invalid")

    private val PHONE_SEPARATORS = setOf('-'.code, '.'.code, '('.code, ')'.code)
}

object PersonalDataMasker {
    fun maskPhone(normalizedPhone: String): String {
        val phone = PersonalDataNormalizer.normalizePhoneForMasking(normalizedPhone)
        return "***-****-${phone.takeLast(4)}"
    }

    fun maskEmail(normalizedEmail: String): String {
        val email = PersonalDataNormalizer.normalizeEmailForMasking(normalizedEmail)
        val (local, domain) = email.split('@', limit = 2)
        val labels = domain.split('.')
        val maskedDomain =
            maskFixed(labels.first()) +
                labels
                    .lastOrNull()
                    ?.takeIf { labels.size > 1 }
                    ?.let { ".$it" }
                    .orEmpty()
        return "${maskFixed(local)}@$maskedDomain"
    }

    fun maskDisplayLabel(rawLabel: String): String {
        val normalized = Normalizer.normalize(rawLabel.trim(), Normalizer.Form.NFKC)
        val codePoints = normalized.codePoints().toArray()
        if (codePoints.size !in 1..200 || codePoints.any(Character::isISOControl)) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Personal data is invalid")
        }
        return when (codePoints.size) {
            1 -> "*"
            2 -> String(codePoints, 0, 1) + "*"
            else -> String(codePoints, 0, 1) + "*".repeat(codePoints.size - 2) + String(codePoints, codePoints.size - 1, 1)
        }
    }

    private fun maskFixed(component: String): String =
        if (component.codePointCount(0, component.length) <= 1) {
            "*"
        } else {
            String(component.codePoints().limit(1).toArray(), 0, 1) + "***"
        }
}
