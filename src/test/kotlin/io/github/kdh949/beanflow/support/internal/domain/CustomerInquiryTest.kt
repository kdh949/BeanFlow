package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class CustomerInquiryTest {
    @Test fun `public text preserves safe lines and rejects sensitive content or controls`() {
        assertThat(CustomerInquiryContent.message(" 문의 내용\r\n추가 설명 ")).isEqualTo("문의 내용\n추가 설명")
        listOf("otp: 123456", "password=secret", "a@example.invalid", "010-1234-5678", "내용\u0000", "가".repeat(2001)).forEach { value ->
            assertThatIllegalArgumentException().isThrownBy { CustomerInquiryContent.message(value) }
        }
        assertThatIllegalArgumentException().isThrownBy { CustomerInquiryContent.title("제목\n추가") }
        assertThatIllegalArgumentException().isThrownBy { CustomerInquiryContent.title("가".repeat(101)) }
    }

    @Test fun `claim is unique and replies require current version and non terminal case`() {
        val now = Instant.parse("2026-09-11T00:00:00Z")
        val inquiry =
            CustomerInquiry(UUID.randomUUID(), UUID.randomUUID(), "문의", CustomerInquiryCategory.OTHER, null, null, null, 0, now, now, 1)
        inquiry.claim(UUID.randomUUID(), 0, now)
        assertThatIllegalStateException().isThrownBy { inquiry.claim(UUID.randomUUID(), 1, now) }
        assertThatIllegalStateException().isThrownBy { inquiry.append(0, SupportCaseState.OPEN, now) }
        assertThatIllegalStateException().isThrownBy { inquiry.append(1, SupportCaseState.RESOLVED, now) }
        inquiry.append(1, SupportCaseState.WAITING, now)
        assertThat(inquiry.version).isEqualTo(2)
    }
}
