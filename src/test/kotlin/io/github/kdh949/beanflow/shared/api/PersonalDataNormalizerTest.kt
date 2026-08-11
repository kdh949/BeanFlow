package io.github.kdh949.beanflow.shared.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.IDN
import java.nio.charset.StandardCharsets

internal class PersonalDataNormalizerTest {
    @Test
    fun `Korean domestic full-width and international phone forms produce one domain-separated value`() {
        val domestic = PersonalDataNormalizer.normalize(ExactSearchCriterionType.PHONE, " ０１０－１２３４－５６７８ ")
        val international = PersonalDataNormalizer.normalize(ExactSearchCriterionType.PHONE, "+82 (10) 1234.5678")
        val doubleZero = PersonalDataNormalizer.normalize(ExactSearchCriterionType.PHONE, "0082-10-1234-5678")

        assertThat(domestic.canonicalBytes()).containsExactly(*international.canonicalBytes())
        assertThat(doubleZero.canonicalBytes()).containsExactly(*international.canonicalBytes())
        assertThat(String(domestic.canonicalBytes(), StandardCharsets.UTF_8))
            .isEqualTo("beanflow-exact-search:v1|5:PHONE|13:+821012345678")
        assertThat(domestic.toString()).doesNotContain("821012345678")
    }

    @Test
    fun `email applies NFKC locale-independent case folding and IDNA domain conversion`() {
        val ascii = PersonalDataNormalizer.normalize(ExactSearchCriterionType.EMAIL, " ＴＥＳＴ＠ＥＸＡＭＰＬＥ．ＣＯＭ ")
        val unicode = PersonalDataNormalizer.normalize(ExactSearchCriterionType.EMAIL, "User@도메인.한국")
        val punycode =
            PersonalDataNormalizer.normalize(
                ExactSearchCriterionType.EMAIL,
                "user@${IDN.toASCII("도메인.한국")}",
            )

        assertThat(String(ascii.canonicalBytes(), StandardCharsets.UTF_8))
            .isEqualTo("beanflow-exact-search:v1|5:EMAIL|16:test@example.com")
        assertThat(unicode.canonicalBytes()).containsExactly(*punycode.canonicalBytes())
        assertThat(unicode.canonicalBytes()).isNotEqualTo(ascii.canonicalBytes())
    }

    @Test
    fun `invalid values fail generically without echoing submitted data`() {
        listOf(
            ExactSearchCriterionType.PHONE to "+82-10-secret",
            ExactSearchCriterionType.PHONE to "1234567",
            ExactSearchCriterionType.PHONE to "+1234567890123456",
            ExactSearchCriterionType.EMAIL to "private@example.com@evil.test",
            ExactSearchCriterionType.EMAIL to "private name@example.com",
            ExactSearchCriterionType.EMAIL to "private\u0000@example.com",
        ).forEach { (type, raw) ->
            assertThatThrownBy { PersonalDataNormalizer.normalize(type, raw) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(FailureCode.INVALID_REQUEST)
                    assertThat(failure.message).isEqualTo("Search criterion is invalid")
                    assertThat(failure.message).doesNotContain(raw, "private", "secret")
                }
        }
    }
}
