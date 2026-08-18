package io.github.kdh949.beanflow.support.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class SupportSha256Test {
    @Test
    fun `UTF-8 문자열의 기존 SHA-256 hex 결과를 유지한다`() {
        assertThat(SupportSha256.utf8("BeanFlow 고객지원"))
            .isEqualTo("15787b1158c39af23b602e5d910e619eb84686bf57bfa9d2b046e0d1cff4d75c")
    }

    @Test
    fun `임의 byte payload의 lowercase hex 결과를 유지한다`() {
        assertThat(SupportSha256.bytes(byteArrayOf(0, 1, -1)))
            .isEqualTo("26a66b061e8f48f39927c312f25293959729eee95978e2892d49d3512a5cc092")
    }
}
