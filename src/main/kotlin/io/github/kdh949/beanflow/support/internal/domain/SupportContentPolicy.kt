package io.github.kdh949.beanflow.support.internal.domain

/**
 * S20 only stores operationally necessary, non-sensitive Support text. Rejection deliberately
 * has a fixed message so a secret-bearing request is never reflected into a response or log.
 */
internal object SupportContentPolicy {
    fun note(value: String): String = normalize(value, NOTE_MAX_LENGTH)

    fun interactionSummary(value: String): String = normalize(value, INTERACTION_SUMMARY_MAX_LENGTH)

    fun reason(value: String): String = normalize(value, REASON_MAX_LENGTH)

    private fun normalize(
        raw: String,
        maxLength: Int,
    ): String {
        val normalized = raw.trim()
        if (normalized.length !in 1..maxLength || normalized.any(::isControl) || containsForbiddenValue(normalized)) {
            throw IllegalArgumentException("Support content is not permitted")
        }
        return normalized
    }

    private fun isControl(character: Char): Boolean = character.code < 0x20 || character.code == 0x7f

    private fun containsForbiddenValue(value: String): Boolean =
        FORBIDDEN_PATTERNS.any { it.containsMatchIn(value) } || containsPaymentCardNumber(value)

    private fun containsPaymentCardNumber(value: String): Boolean =
        CARD_NUMBER_CANDIDATE.findAll(value).any { candidate ->
            val digits = candidate.value.filter(Char::isDigit)
            digits
                .reversed()
                .mapIndexed { index, digit ->
                    val numeric = digit.digitToInt()
                    if (index % 2 == 0) numeric else (numeric * 2).let { if (it > 9) it - 9 else it }
                }.sum() % 10 == 0
        }

    private const val NOTE_MAX_LENGTH = 2_000
    private const val INTERACTION_SUMMARY_MAX_LENGTH = 1_000
    private const val REASON_MAX_LENGTH = 500

    private val FORBIDDEN_PATTERNS =
        listOf(
            Regex("""(?i)\b(?:password|passcode|pwd)\s*[:=]\s*\S+"""),
            Regex("""(?i)\b(?:otp|one[- ]time(?:\s+password|\s+code)?|verification\s+code)\s*[:=]?\s*\d{4,10}\b"""),
            Regex("""(?i)\b(?:access|refresh|api|pg)\s*token\s*[:=]\s*[A-Za-z0-9._~-]{8,}\b"""),
            Regex("""(?i)\bbearer\s+[A-Za-z0-9._~-]{8,}\b"""),
            Regex("""(?i)\b(?:cvc|cvv|security[ -]?code|card[ -]?security[ -]?code)\s*(?:number|no\.?)?\s*[:=#-]?\s*\d{3,4}\b"""),
            Regex("""(?<!\d)\d{2,6}-\d{2,6}-\d{2,8}(?!\d)"""),
            Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
            Regex("""(?<!\d)(?:\+?82[-\s]?)?0?1[0-9][-\s]?\d{3,4}[-\s]?\d{4}(?!\d)"""),
            Regex(
                """(?:서울(?:특별시)?|부산(?:광역시)?|대구(?:광역시)?|인천(?:광역시)?|광주(?:광역시)?|대전(?:광역시)?|울산(?:광역시)?|세종(?:특별자치시)?|경기도|강원(?:특별자치도)?|충청[남북]도|전라[남북]도|경상[남북]도|제주(?:특별자치도)?)[^\n]{0,80}?(?:[가-힣A-Za-z]+(?:로|길)\s*\d+|\d+(?:번지|호)?)""",
            ),
        )

    private val CARD_NUMBER_CANDIDATE =
        Regex("""(?<!\d)(?:\d[\p{Zs}\-‐‑‒–—]?){12,18}\d(?!\d)""")
}
