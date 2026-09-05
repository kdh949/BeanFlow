import { ArrowLeft, FileSearch, RotateCcw } from "lucide-react";
import { useState } from "react";
import { Button, ButtonLink, EmptyState, InlineNotice, PageHeading, TextAreaField } from "../../design-system";
import { StatusText } from "../../presentation/shared";
import { shortDateTime, won } from "../../lib/format";

export type StoreDisputeDetail = {
  disputeId: string;
  settlementItemReference: string;
  state: "FILED" | "UNDER_REVIEW" | "ACCEPTED" | "REJECTED" | "WITHDRAWN";
  filedAt: string;
  decidedAt: string | null;
  expectedAdjustmentKrw: number;
  heldAmountKrw: number;
  reasonSummary: string;
  decisionSummary: string | null;
  evidenceCount: number;
};

export type StoreDisputeDetailPageProps = {
  scenario?: "contract-pending" | "ready";
  dispute?: StoreDisputeDetail;
  onRequestReappeal?: (reason: string) => Promise<void>;
};

export function StoreDisputeDetailPage({ scenario = "contract-pending", dispute, onRequestReappeal }: StoreDisputeDetailPageProps) {
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [failed, setFailed] = useState(false);

  async function submit() {
    if (!onRequestReappeal || reason.trim().length < 10) return;
    setSubmitting(true);
    setFailed(false);
    try {
      await onRequestReappeal(reason.trim());
      setSubmitted(true);
      setReason("");
    } catch {
      setFailed(true);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="console-page dispute-detail-page">
      <ButtonLink to="/store/disputes" variant="ghost"><ArrowLeft size={17} aria-hidden="true" /> 이의제기 목록</ButtonLink>
      <PageHeading title="이의제기 내용" />
      {scenario === "contract-pending" || !dispute ? (
        <InlineNotice tone="danger" announce="assertive" title="상세 내용을 준비하고 있습니다" description="지금은 이의제기 목록만 확인할 수 있습니다." />
      ) : (
        <div className="console-detail-grid">
          <section className="surface-card order-panel">
            <div className="panel-heading"><div><span className="context-label">{dispute.settlementItemReference}</span><h2>{dispute.disputeId}</h2></div><StatusText state={dispute.state} /></div>
            <dl className="detail-list"><div><dt>요청 금액</dt><dd className="bf-num">{won.format(dispute.expectedAdjustmentKrw)}</dd></div><div><dt>보류 금액</dt><dd className="bf-num">{won.format(dispute.heldAmountKrw)}</dd></div><div><dt>첨부 자료</dt><dd>{dispute.evidenceCount}건</dd></div></dl>
            <div className="dispute-summary"><FileSearch aria-hidden="true" /><div><strong>신청 사유</strong><p>{dispute.reasonSummary}</p></div></div>
            {dispute.decisionSummary ? <div className="dispute-summary"><RotateCcw aria-hidden="true" /><div><strong>검토 결과</strong><p>{dispute.decisionSummary}</p></div></div> : <InlineNotice title="검토 중입니다" description="검토가 끝나면 다시 검토를 요청할 수 있는지 확인할 수 있습니다." />}
            <p className="form-footnote">신청 {shortDateTime.format(new Date(dispute.filedAt))}{dispute.decidedAt ? ` · 검토 완료 ${shortDateTime.format(new Date(dispute.decidedAt))}` : ""}</p>
          </section>
          <section className="surface-card action-panel">
            <h2>다시 검토 요청</h2>
            {dispute.state === "REJECTED" ? <><p>새로 확인된 자료나 내용이 있을 때 요청해 주세요.</p><TextAreaField label="다시 검토할 이유" value={reason} onValueChange={(value) => { setReason(value); setSubmitted(false); setFailed(false); }} /><Button loading={submitting} disabled={!onRequestReappeal || reason.trim().length < 10} onClick={() => void submit()}>다시 검토 요청</Button></> : <EmptyState title="지금은 다시 검토를 요청할 수 없습니다" description="기각된 건만 새 근거를 붙여 요청할 수 있습니다." />}
            {submitted ? <p className="operation-success" role="status">요청을 보냈습니다</p> : null}
            {failed ? <InlineNotice tone="danger" announce="assertive" title="요청을 보내지 못했습니다" description="현재 상태와 요청 기간을 확인한 뒤 다시 시도해 주세요." /> : null}
          </section>
        </div>
      )}
    </div>
  );
}
