import { Info, RefreshCw } from "lucide-react";
import type { components } from "../../api/schema";
import { StatusBadge } from "../../components/Ui";
import { won } from "../../lib/format";

type RefundRecovery = components["schemas"]["CancellationRefundRecoverySummary"];

const STATE_COPY: Record<RefundRecovery["state"], { title: string; description: string }> = {
  NOT_REQUIRED: { title: "환불할 금액이 없어요", description: "이 취소로 돌려드릴 결제 금액이 없습니다." },
  REQUESTED: { title: "환불을 요청했어요", description: "카드사·간편결제사에 환불을 요청했습니다." },
  PROCESSING: { title: "환불을 처리하고 있어요", description: "결제사 처리 시간에 따라 시간이 더 걸릴 수 있어요." },
  SUCCEEDED: { title: "환불이 완료됐어요", description: "결제하신 수단으로 환불 금액이 돌아갔습니다." },
};

/**
 * The cancelled order and its refund are reported separately. Anything that is
 * not SUCCEEDED stays visibly in progress instead of reading as money returned.
 */
export function RefundProgress({ recovery }: { recovery: RefundRecovery }) {
  const copy = STATE_COPY[recovery.state];
  const delayed = recovery.noticeCode === "REFUND_DELAYED";
  return (
    <section className="surface-card refund-progress" aria-label="환불 진행 상태">
      <div className="refund-progress-head">
        <strong>{copy.title}</strong>
        <StatusBadge state={recovery.state} />
      </div>
      <p>{copy.description}</p>
      {delayed ? (
        <p className="refund-delay-note" role="status">
          <Info size={16} aria-hidden="true" /> 환불이 예상보다 늦어지고 있어요. 같은 요청을 반복하지 않아도 됩니다.
        </p>
      ) : null}
      {recovery.cancellationRequestedRefundAmountKrw !== undefined ? (
        <dl className="refund-amounts">
          <div><dt>환불 요청 금액</dt><dd>{won.format(recovery.cancellationRequestedRefundAmountKrw)}</dd></div>
          {recovery.remainingRefundableAmountKrw !== undefined ? (
            <div><dt>남은 환불 가능 금액</dt><dd>{won.format(recovery.remainingRefundableAmountKrw)}</dd></div>
          ) : null}
        </dl>
      ) : null}
      {recovery.state === "PROCESSING" || recovery.state === "REQUESTED" ? (
        <p className="form-footnote"><RefreshCw size={14} aria-hidden="true" /> 결과가 확정되면 이 화면에서 바로 확인할 수 있어요.</p>
      ) : null}
    </section>
  );
}
