import { useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { Button, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { won } from "../../lib/format";

type SettlementDispute = components["schemas"]["SettlementDispute"];

const REASONS: Record<string, string> = {
  DISPUTE_WINDOW_CLOSED: "이의제기 기간이 지났습니다. 확정 다음 날부터 15일 안에만 접수할 수 있습니다.",
  DISPUTE_ALREADY_ACTIVE: "이 명세에는 이미 진행 중인 이의제기가 있습니다. 판정 결과를 기다려 주세요.",
  DISPUTE_REFILE_NOT_ALLOWED: "재접수는 한 번만, 새 증빙과 함께 가능합니다.",
};

/**
 * `expectedAdjustmentKrw` is the owner's claim, not an approved amount. The
 * server validates the confirmed item, the filing window and the limits, and the
 * held amount in the response is the server's answer.
 */
export function DisputeFilingPanel({
  settlementItemId,
  onFiled,
  onClose,
}: {
  settlementItemId: string;
  /** Fired once, right after a successful submit, so the caller can refresh its own data. */
  onFiled: () => void;
  /** The operator dismisses the confirmation explicitly; this panel never unmounts itself. */
  onClose: () => void;
}) {
  const [expectedAdjustmentKrw, setExpectedAdjustmentKrw] = useState(0);
  const [reason, setReason] = useState("");
  const [evidence, setEvidence] = useState("");
  const [filed, setFiled] = useState<SettlementDispute | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const intent = useRef(new SubmissionIntent());

  const evidenceReferences = evidence
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

  async function submit() {
    const fingerprint = JSON.stringify({ settlementItemId, expectedAdjustmentKrw, reason: reason.trim(), evidenceReferences });
    setSubmitting(true);
    setFailure(null);
    try {
      const response = await merchantApi.POST("/settlement-items/{itemId}/disputes", {
        params: {
          path: { itemId: settlementItemId },
          header: { "Idempotency-Key": intent.current.keyFor(fingerprint), ...(await merchantCsrfHeader()) },
        },
        body: { expectedAdjustmentKrw, reason: reason.trim(), evidenceReferences },
      });
      setFiled(unwrap(response));
      intent.current.complete();
      onFiled();
    } catch (error) {
      if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") intent.current.rotate();
      setFailure(error);
    } finally {
      setSubmitting(false);
    }
  }

  if (filed) {
    return (
      <div className="dispute-form-filed">
        <p className="form-footnote" role="status">
          이의제기를 접수했습니다. 보류 금액 {won.format(filed.heldAmountKrw)}은 판정 전까지 정산에 반영되지 않습니다.
        </p>
        <Button variant="ghost" type="button" onClick={onClose}>확인</Button>
      </div>
    );
  }

  const code = failure instanceof ApiRequestError ? failure.code : null;
  return (
    <form
      className="dispute-form"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      <TextField
        label="기대하는 조정 금액 (원)"
        id={`dispute-amount-${settlementItemId}`}
        type="number"
        value={String(expectedAdjustmentKrw)}
        required
        description="입력한 금액은 요청액이며, 접수 가능 금액과 최종 승인 금액은 검토 과정에서 확정됩니다."
        onValueChange={(value) => {
          setExpectedAdjustmentKrw(Number(value));
          intent.current.rotate();
        }}
      />
      <TextAreaField
        label="사유"
        id={`dispute-reason-${settlementItemId}`}
        value={reason}
        maxLength={1000}
        required
        onValueChange={(value) => {
          setReason(value);
          intent.current.rotate();
        }}
      />
      <TextAreaField
        label="증빙 위치 (한 줄에 하나)"
        id={`dispute-evidence-${settlementItemId}`}
        value={evidence}
        required
        placeholder="영수증 보관 위치나 내부 기록 위치를 적어 주세요"
        onValueChange={(value) => {
          setEvidence(value);
          intent.current.rotate();
        }}
      />
      {code && REASONS[code] ? (
        <p className="form-error" role="alert">{REASONS[code]}</p>
      ) : failure ? (
        <ErrorState error={failure} />
      ) : null}
      <Button type="submit" loading={submitting} disabled={!reason.trim() || evidenceReferences.length === 0}>
        {submitting ? "접수 중" : "이의제기 접수"}
      </Button>
    </form>
  );
}
