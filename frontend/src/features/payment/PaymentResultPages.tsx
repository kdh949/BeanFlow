import { RefreshCw, XCircle } from "lucide-react";
import { useLayoutEffect, useMemo } from "react";
import { Link, Navigate, useParams, useSearchParams } from "react-router";
import { ApiRequestError } from "../../api/client";
import { ErrorState, LoadingState, StatusText, SuccessMark } from "../../design-system";
import { PageHeading } from "../../design-system";
import { won } from "../../lib/format";
import { ButtonLink } from "../../design-system";
import { checkCallback, hasCallbackQuery, type PaymentCallback } from "./paymentAttempt";
import { type Payment, type PaymentResolution, usePaymentResolution } from "./usePaymentResolution";

type PaymentSuccessLocation = Pick<Location, "pathname" | "search" | "hash">;
type PaymentSuccessHistory = Pick<History, "state" | "replaceState">;

/**
 * The provider callback query is removed before any command is sent, so a reload
 * or a shared URL cannot replay it.
 */
export function clearPaymentSuccessQuery(
  currentLocation: PaymentSuccessLocation = window.location,
  currentHistory: PaymentSuccessHistory = window.history,
) {
  if (!currentLocation.search) return;
  currentHistory.replaceState(
    currentHistory.state,
    "",
    `${currentLocation.pathname}${currentLocation.hash}`,
  );
}

export function PaymentSuccessPage() {
  const { paymentId = "" } = useParams();
  const [searchParams] = useSearchParams();

  const callbackCheck = useMemo(
    () => (hasCallbackQuery(searchParams) ? checkCallback(paymentId, searchParams) : null),
    // The query is cleared from the URL right after mount; the first read owns
    // this decision for the rest of the page lifecycle.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [paymentId],
  );
  const callback: PaymentCallback | null = callbackCheck?.valid ? callbackCheck.callback : null;

  useLayoutEffect(() => clearPaymentSuccessQuery(), []);

  const { resolution, refresh } = usePaymentResolution(paymentId, callback);

  if (callbackCheck && !callbackCheck.valid) {
    return (
      <div className="customer-page result-page">
        <ErrorState error={new ApiRequestError(400, callbackCheck.code, callbackMessage(callbackCheck.code))} />
        <ButtonLink size="xl" block to="/app/orders">주문 상태 보기</ButtonLink>
      </div>
    );
  }

  return <PaymentResultView resolution={resolution} refresh={refresh} />;
}

function callbackMessage(code: "INVALID_PAYMENT_CALLBACK" | "PAYMENT_CALLBACK_MISMATCH") {
  return code === "PAYMENT_CALLBACK_MISMATCH"
    ? "결제창에서 돌아온 정보가 주문과 일치하지 않습니다. 주문 상태를 확인해 주세요."
    : "결제 결과 정보가 올바르지 않습니다. 주문 상태를 확인해 주세요.";
}

function PaymentResultView({ resolution, refresh }: { resolution: PaymentResolution; refresh: () => void }) {
  if (resolution.phase === "confirming") return <LoadingState label="결제를 안전하게 승인하는 중" />;
  if (resolution.phase === "failed") {
    return (
      <div className="customer-page result-page">
        <ErrorState error={resolution.error} retry={refresh} />
        <ButtonLink variant="secondary" block to="/app/orders">주문 상태 보기</ButtonLink>
      </div>
    );
  }

  const { payment } = resolution;
  if (resolution.phase === "manual-review") {
    return <ManualReviewPaymentView payment={payment} />;
  }
  // Returning to the success URL does not make the payment approved. A declined
  // or still-unpaid payment is reported as such; only APPROVED says "완료".
  if (resolution.phase === "declined" || resolution.phase === "retryable") {
    const retryable = resolution.phase === "retryable";
    return (
      <div className="customer-page result-page">
        <span className="failure-mark"><XCircle size={36} /></span>
        <span className="context-label">결제 중단</span>
        <h1>{retryable ? "아직 결제가 끝나지 않았어요" : "결제를 완료하지 못했어요"}</h1>
        <p>{retryable
          ? "결제창이 닫혔거나 결제가 진행되지 않았어요. 주문 상태를 확인해 주세요."
          : "결제가 승인되지 않았습니다. 주문 상태를 확인해 주세요."}</p>
        <StatusText state={payment.approvalState} label={retryable ? "결제 전" : undefined} />
        <ButtonLink size="xl" block to={orderTrackingPath(payment.orderReference)}>
          주문 상태 보기
        </ButtonLink>
        <Link className="text-link" to="/app/help">도움이 필요해요</Link>
      </div>
    );
  }

  const pending = resolution.phase === "pending";
  return (
    <div className="customer-page result-page">
      {pending ? <span className="pending-mark"><RefreshCw className="spin" size={30} /></span> : <SuccessMark />}
      <span className="context-label">{pending ? "결제 확인" : "주문 확인"}</span>
      <h1>{pending ? "결제 결과를 확인하고 있어요" : "결제가 완료됐어요"}</h1>
      <p>{pending
        ? "같은 결제를 다시 시도하지 않아도 됩니다. 결제 상태를 조회해 결과를 확인하고 있어요."
        : "매장에서 주문을 확인하면 픽업 준비 상태를 알려드릴게요."}</p>
      <StatusText state={payment.approvalState} />
      <div className="result-summary surface-card">
        <div><span>주문 번호</span><code>{payment.orderReference}</code></div>
        <div><span>승인 금액</span><strong>{payment.approvedAmountKrw == null ? "확인 중" : won.format(payment.approvedAmountKrw)}</strong></div>
        {payment.recovery ? (
          <div>
            <span>복구 상태</span>
            <StatusText state={payment.recovery.state} />
          </div>
        ) : null}
      </div>
      <ButtonLink size="xl" block to={orderTrackingPath(payment.orderReference)}>주문 상태 보기</ButtonLink>
    </div>
  );
}

export function PaymentFailPage() {
  const { paymentId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const code = publicFailureCode(searchParams.get("code") ?? "PAYMENT_AUTH_FAILED");

  // A fail callback never confirms. It only reads the server-owned state.
  const { resolution, refresh } = usePaymentResolution(paymentId, null);

  if (resolution.phase === "confirming") return <LoadingState label="결제 상태를 확인하는 중" />;
  if (resolution.phase === "failed") {
    return (
      <div className="customer-page result-page">
        <ErrorState error={resolution.error} retry={refresh} />
        <ButtonLink variant="secondary" block to="/app/orders">주문 상태 보기</ButtonLink>
      </div>
    );
  }
  if (resolution.phase === "approved") {
    return <Navigate replace to={`/app/payments/${paymentId}/success`} />;
  }
  if (resolution.phase === "manual-review") {
    return <ManualReviewPaymentView payment={resolution.payment} />;
  }
  if (resolution.phase === "pending") {
    return (
      <div className="customer-page result-page">
        <span className="pending-mark"><RefreshCw className="spin" size={30} /></span>
        <span className="context-label">결제 확인</span>
        <h1>결제 결과를 확인하고 있어요</h1>
        <p>같은 결제를 다시 시도하지 마세요. 서버가 현재 결제 상태를 확인하고 있습니다.</p>
        <StatusText state={resolution.payment.approvalState} />
        <ButtonLink variant="secondary" block to={orderTrackingPath(resolution.payment.orderReference)}>주문 상태 보기</ButtonLink>
      </div>
    );
  }

  return (
    <div className="customer-page result-page">
      <span className="failure-mark"><XCircle size={36} /></span>
      <span className="context-label">결제 중단</span>
      <h1>결제를 완료하지 못했어요</h1>
      <p>{failureMessage(code)}</p>
      <code className="failure-code">{code}</code>
      <ButtonLink
        size="xl"
        block
        to={orderTrackingPath(resolution.payment.orderReference)}
      >
        주문 상태 보기
      </ButtonLink>
      <Link className="text-link" to="/app/help">도움이 필요해요</Link>
    </div>
  );
}

function ManualReviewPaymentView({ payment }: { payment: Payment }) {
  return (
    <div className="customer-page result-page">
      <span className="pending-mark"><RefreshCw size={30} /></span>
      <span className="context-label">결제 검토</span>
      <h1>결제 확인에 시간이 더 필요해요</h1>
      <p>같은 결제를 다시 시도하지 마세요. 주문 상태를 확인하거나 도움이 필요하면 문의해 주세요.</p>
      <StatusText state={payment.approvalState} />
      <div className="result-summary surface-card">
        <div><span>주문 번호</span><code>{payment.orderReference}</code></div>
      </div>
      <ButtonLink size="xl" block to={orderTrackingPath(payment.orderReference)}>주문 상태 보기</ButtonLink>
      <Link className="text-link" to="/app/help">도움이 필요해요</Link>
    </div>
  );
}

function orderTrackingPath(orderReference: string): string {
  return `/app/orders/${orderReference}`;
}

export function failureMessage(code: string) {
  const messages: Record<string, string> = {
    PAY_PROCESS_CANCELED: "결제를 취소했습니다. 주문서에서 다시 진행할 수 있어요.",
    PAY_PROCESS_ABORTED: "결제 인증이 중단됐습니다. 다른 카드나 간편결제로 다시 시도해 주세요.",
    REJECT_CARD_COMPANY: "카드사에서 승인을 거절했습니다. 카드사에 확인하거나 다른 수단을 이용해 주세요.",
  };
  return messages[code] ?? "결제 인증을 마치지 못했습니다. 주문서에서 안전하게 다시 시도할 수 있어요.";
}

/** Only the documented public SDK codes are echoed; anything else is generic. */
export function publicFailureCode(code: string) {
  return ["PAY_PROCESS_CANCELED", "PAY_PROCESS_ABORTED", "REJECT_CARD_COMPANY"].includes(code)
    ? code
    : "PAYMENT_AUTH_FAILED";
}

export function CustomerHelpPage() {
  return (
    <div className="customer-page">
      <PageHeading title="도움이 필요하신가요?" description="결제 결과가 확인 중이면 같은 결제를 반복하지 말고 주문 상태를 새로고침해 주세요." />
      <section className="surface-card help-card">
        <strong>결제·환불 문의</strong>
        <p>문의할 때 화면의 문의 코드와 주문 번호를 알려주세요. 카드 번호나 인증 정보는 보내지 마세요.</p>
      </section>
    </div>
  );
}
