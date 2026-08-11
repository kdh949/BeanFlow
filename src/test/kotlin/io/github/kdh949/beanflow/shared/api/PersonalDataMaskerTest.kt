package io.github.kdh949.beanflow.shared.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class PersonalDataMaskerTest {
    @Test
    fun `phone exposes only the final four digits`() {
        assertThat(PersonalDataMasker.maskPhone("+821012345678")).isEqualTo("***-****-5678")
        assertThat(PersonalDataMasker.maskPhone("+12025550123")).isEqualTo("***-****-0123")
    }

    @Test
    fun `email exposes fixed fragments rather than component lengths`() {
        assertThat(PersonalDataMasker.maskEmail("donghyun@example.com")).isEqualTo("d***@e***.com")
        assertThat(PersonalDataMasker.maskEmail("a@x.com")).isEqualTo("*@*.com")
        assertThat(PersonalDataMasker.maskEmail("name@example")).isEqualTo("n***@e***")
    }

    @Test
    fun `display labels mask by Unicode code point`() {
        assertThat(PersonalDataMasker.maskDisplayLabel("김동현")).isEqualTo("김*현")
        assertThat(PersonalDataMasker.maskDisplayLabel("AB")).isEqualTo("A*")
        assertThat(PersonalDataMasker.maskDisplayLabel("A😀B")).isEqualTo("A*B")
        assertThat(PersonalDataMasker.maskDisplayLabel("김")).isEqualTo("*")
    }

    @Test
    fun `invalid display labels fail without echoing plaintext`() {
        listOf("", "private\u0000name", "가".repeat(201)).forEach { raw ->
            assertThatThrownBy { PersonalDataMasker.maskDisplayLabel(raw) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(FailureCode.INVALID_REQUEST)
                    assertThat(failure.message).isEqualTo("Personal data is invalid")
                    assertThat(failure.message).doesNotContain("private", "가가가")
                }
        }
    }
}
