import { useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { ErrorState } from "../../components/Ui";
import { won } from "../../lib/format";
import { Button } from "../../design-system";

type CancellationPreview = components["schemas"]["CustomerCancellationPreview"];
type CancellationReasonCode = components["schemas"]["CancellationReasonCode"];

const REASONS: Array<{ code: CancellationReasonCode; label: string }> = [
  { code: "CHANGED_MIND", label: "마음이 바뀌었어요" },
  { code: "ORDER_MISTAKE", label: "주문을 잘못했어요" },
  { code: "WAIT_TOO_LONG", label: "기다리기 어려워요" },
  { code: "PICKUP_TIME_CONFLICT", label: "픽업 시간이 안 맞아요" },
  { code: "PAYMENT_ISSUE", label: "결제에 문제가 있어요" },
  { code: "OTHER", label: "기타" },
];

/**
 * The cancellation command and the refund are separate outcomes. A 202 means the
 * order is cancelled and the refund is still moving; it is never shown as a
 * completed refund.
 */
export function CancelOrderPanel({ orderReference, preview, onCancelled }: {
  orderReference: string;
  preview?: CancellationPreview;
  onCancelled: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [reasonCode, setReasonCode] = useState<CancellationReasonCode>("CHANGED_MIND");
  const [detail, setDetail] = useState("");
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const intent = useRef(new SubmissionIntent());

  async function cancel() {
    const body = { reasonCode, ...(detail.trim() ? { detail: detail.trim() } : {}) };
    setSubmitting(true);
    setFailure(null);
    try {
      const result = await customerApi.POST("/me/orders/{orderReference}/cancellations", {
        params: {
          path: { orderReference },
          header: {
            "Idempotency-Key": intent.current.keyFor(JSON.stringify({ orderReference, ...body })),
            ...(await customerCsrfHeader()),
          },
        },
        body,
      });
      unwrap(result);
      intent.current.complete();
      setOpen(false);
      onCancelled();
    } catch (error) {
      if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") intent.current.rotate();
      setFailure(error);
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) {
    return (
      <Button variant="ghost" block onClick={() => setOpen(true)}>
        주문 취소
      </Button>
    );
  }

  return (
    <section className="surface-card cancel-panel" aria-label="주문 취소">
      <strong>주문을 취소할까요?</strong>
      <p>매장이 수락하기 전까지 주문 전체를 취소할 수 있어요.</p>
      {preview ? (
        <dl className="cancel-preview">
          <div><dt>예상 환불 금액</dt><dd>{won.format(preview.cashRefundAmountKrw)}</dd></div>
          <div><dt>예상 포인트 복구</dt><dd>{preview.restoredPoints.toLocaleString("ko-KR")}P</dd></div>
        </dl>
      ) : null}
      {preview ? <p className="form-footnote">예상 금액이며 실제 환불 결과는 취소한 뒤 확인할 수 있어요.</p> : null}

      <label htmlFor="cancel-reason">취소 사유</label>
      <select id="cancel-reason" value={reasonCode} onChange={(event) => {
        setReasonCode(event.target.value as CancellationReasonCode);
        intent.current.rotate();
      }}>
        {REASONS.map((reason) => <option key={reason.code} value={reason.code}>{reason.label}</option>)}
      </select>

      <label htmlFor="cancel-detail">자세한 사유 (선택)</label>
      <textarea
        id="cancel-detail"
        value={detail}
        maxLength={200}
        onChange={(event) => {
          setDetail(event.target.value);
          intent.current.rotate();
        }}
      />

      {failure ? <ErrorState error={failure} /> : null}
      <div className="cancel-actions">
        <Button variant="ghost" onClick={() => setOpen(false)}>그대로 두기</Button>
        <Button loading={submitting} onClick={() => void cancel()}>
          {submitting ? "취소하는 중" : "주문 취소하기"}
        </Button>
      </div>
    </section>
  );
}
