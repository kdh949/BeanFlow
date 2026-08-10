package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.payment.api.ExternalPaymentView
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Component
internal class PaymentConfirmationResponseFactory(
    private val objectMapper: ObjectMapper,
) {
    fun current(
        view: ExternalPaymentView,
        replay: Boolean,
    ): StoredHttpResponse {
        val status =
            when (view.approvalState) {
                "APPROVED" -> 200
                "FAILED" -> 422
                "READY", "APPROVING", "UNKNOWN", "RECONCILING", "MANUAL_REVIEW" -> 202
                else -> 409
            }
        if (status == 422) {
            return error(
                FailureCode.PAYMENT_DECLINED,
                "Provider declined the payment",
                view.correlationId,
                replay,
            )
        }
        return StoredHttpResponse(
            status = status,
            body = objectMapper.writeValueAsString(view.toResponse()),
            replay = replay,
        )
    }

    fun error(
        code: FailureCode,
        message: String,
        correlationId: String,
        replay: Boolean = false,
    ): StoredHttpResponse =
        StoredHttpResponse(
            status =
                when (code) {
                    FailureCode.PAYMENT_DECLINED -> 422
                    else -> 409
                },
            body = objectMapper.writeValueAsString(ErrorResponse(code.name, message, correlationId)),
            replay = replay,
        )

    fun confirmationBody(
        paymentId: UUID,
        orderId: UUID,
        approvalState: String,
        approvedAmountKrw: Long?,
        currency: String,
        recoveryState: String,
        now: Instant,
        correlationId: String,
    ): String =
        objectMapper.writeValueAsString(
            PaymentConfirmationResponse(
                paymentId,
                orderId,
                "EXTERNAL",
                approvalState,
                approvedAmountKrw,
                currency,
                PaymentRecoveryResponse(recoveryState, now).takeUnless { recoveryState == "NOT_REQUIRED" },
                now,
                correlationId,
            ),
        )
}

private fun ExternalPaymentView.toResponse(): PaymentConfirmationResponse =
    PaymentConfirmationResponse(
        paymentId = paymentId,
        orderId = orderId,
        type = type,
        approvalState = approvalState,
        approvedAmountKrw = approvedAmountKrw,
        currency = currency,
        recovery =
            PaymentRecoveryResponse(recoveryState, updatedAt).takeUnless {
                recoveryState == "NOT_REQUIRED"
            },
        updatedAt = updatedAt,
        correlationId = correlationId,
    )
