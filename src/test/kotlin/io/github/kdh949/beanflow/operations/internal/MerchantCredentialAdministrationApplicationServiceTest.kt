package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.MerchantCredentialMembershipRole
import io.github.kdh949.beanflow.operations.api.MerchantCredentialSecurityPort
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

internal class MerchantCredentialAdministrationApplicationServiceTest {
    private val security = mock(MerchantCredentialSecurityPort::class.java)
    private val transactions = mock(MerchantCredentialAdministrationTransactions::class.java)
    private val random = mock(SecureRandom::class.java)
    private val registry = SimpleMeterRegistry()
    private val service =
        MerchantCredentialAdministrationApplicationService(
            security,
            transactions,
            MerchantCredentialMetrics(registry),
            Clock.systemUTC(),
            random,
        )

    @Test
    fun `terminal create replay is rejected before secret generation and hashing`() {
        val command =
            CreateMerchantAccountCommand(
                UUID.randomUUID(),
                "merchant-create-replay",
                "REPLAY.MERCHANT",
                "Replay Merchant",
                UUID.randomUUID(),
                MerchantCredentialMembershipRole.OWNER,
                "Resolve repeated create",
            )
        `when`(security.canonicalizeLoginId(command.loginId)).thenReturn("replay.merchant")
        val canonicalCommand = command.copy(loginId = "replay.merchant")
        doThrow(replayFailure()).`when`(transactions).precheckCreate(canonicalCommand)

        assertThatThrownBy { service.create(command) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.TEMPORARY_PASSWORD_NOT_REPLAYABLE)

        verify(security).canonicalizeLoginId(command.loginId)
        verifyNoMoreInteractions(security)
        verifyNoInteractions(random)
        assertThat(
            registry
                .get(
                    "beanflow.operations.merchant_credential.command",
                ).tag("operation", "CREATE")
                .tag("outcome", "failed")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `terminal reset replay is rejected before secret generation and hashing`() {
        val command =
            MerchantCredentialMutationCommand(
                UUID.randomUUID(),
                "merchant-reset-replay",
                UUID.randomUUID(),
                "Resolve repeated reset",
            )
        doThrow(replayFailure()).`when`(transactions).precheckReset(command)

        assertThatThrownBy { service.resetTemporaryPassword(command) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.TEMPORARY_PASSWORD_NOT_REPLAYABLE)

        verifyNoInteractions(security, random)
        assertThat(
            registry
                .get(
                    "beanflow.operations.merchant_credential.command",
                ).tag("operation", "RESET_TEMPORARY_PASSWORD")
                .tag("outcome", "failed")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }

    private fun replayFailure() =
        DomainFailure(
            FailureCode.TEMPORARY_PASSWORD_NOT_REPLAYABLE,
            "Temporary password cannot be replayed",
            targetReference = UUID.randomUUID().toString(),
        )
}
