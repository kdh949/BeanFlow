import { useCallback, useEffect, useRef, useState } from "react";
import { SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, InlineNotice, LoadingState, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
const labels = { PENDING_APPROVAL: "승인 대기", EXECUTED: "복구 실행됨", REJECTED: "반려됨", EXPIRED: "기한 만료", STALE: "현재 상태가 달라짐" };
/** Proposal reads expose the current decision boundary before a second operator acts. */
export function PaymentRepairWorkspace({ caseId }: { caseId?: string }) {
  const [caseInput, setCaseInput] = useState("");
  const [proposalId, setProposalId] = useState("");
  const [request, setRequest] = useState<{ id: string } | null>(null);
  const [reason, setReason] = useState("");
  const [decisionReason, setDecisionReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [created, setCreated] = useState(false);
  const createIntent = useRef(new SubmissionIntent());
  const decisionIntent = useRef(new SubmissionIntent());
  const [, tick] = useState(0);
  const detail = useResource(useCallback(async () => {
    if (!request) return null;
    const [proposal, actor] = await Promise.all([
      operationsApi.GET("/operations/reprocessing-repair-proposals/{proposalId}", { params: { path: { proposalId: request.id } } }).then(unwrap),
      operationsApi.GET("/operations/me").then(unwrap),
    ]);
    return { proposal, actor };
  }, [request]));
  const current = detail.state.status === "ready" ? detail.state.value : null;
  const proposal = current?.proposal;
  const expired = proposal ? Date.now() >= Date.parse(proposal.expiresAt) : false;
  useEffect(() => {
    if (!proposal || proposal.state !== "PENDING_APPROVAL") return;
    const remaining = Date.parse(proposal.expiresAt) - Date.now();
    if (remaining <= 0) return;
    const timer = window.setTimeout(() => tick(value => value + 1), Math.min(remaining + 1, 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [proposal]);
  async function create() {
    const target = caseId ?? caseInput.trim();
    if (busy || !target || !reason.trim()) return;
    setBusy(true); setFailure(null); setCreated(false);
    const body = { reason: reason.trim() };
    try {
      const result = unwrap(await operationsApi.POST("/operations/reprocessing-cases/{caseId}/repair-proposals", { params: { path: { caseId: target }, header: { "Idempotency-Key": createIntent.current.keyFor(JSON.stringify({ target, ...body })) } }, body }));
      createIntent.current.complete(); setCreated(true); setReason(""); setProposalId(result.proposalId); setRequest({ id: result.proposalId });
    } catch (error) { setFailure(error); } finally { setBusy(false); }
  }
  async function decide(decision: "APPROVE" | "REJECT") {
    if (busy || !proposal || !current || expired || proposal.state !== "PENDING_APPROVAL" || current.actor.operatorId === proposal.proposedBy || !decisionReason.trim()) return;
    setBusy(true); setFailure(null);
    const body = { decision, reason: decisionReason.trim() };
    try {
      unwrap(await operationsApi.POST("/operations/reprocessing-repair-proposals/{proposalId}/decisions", { params: { path: { proposalId: proposal.proposalId }, header: { "Idempotency-Key": decisionIntent.current.keyFor(JSON.stringify({ id: proposal.proposalId, ...body })) } }, body }));
      decisionIntent.current.complete(); setDecisionReason("");
    } catch (error) { setFailure(error); } finally { detail.reload(); setBusy(false); }
  }
  return <section className="management-workspace"><h2>누락 환불 복구 제안</h2>
    <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); void create(); }}><fieldset className="catalog-fieldset" disabled={busy}><legend>복구 제안 생성</legend>{caseId ? <p className="support-case-reference">복구 건 {caseId}</p> : <TextField label="환불 설정 복구 건 ID" value={caseInput} onValueChange={setCaseInput} required />}<TextField label="복구 제안 사유" value={reason} onValueChange={setReason} maxLength={500} required /><Button type="submit" loading={busy} disabled={!(caseId ?? caseInput.trim()) || !reason.trim()}>복구 제안 생성</Button></fieldset></form>
    {created ? <p role="status">복구 제안을 만들었습니다. 다른 담당자의 판정이 필요합니다.</p> : null}
    <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); setRequest({ id: proposalId.trim() }); setFailure(null); }}><TextField label="복구 제안 ID" value={proposalId} onValueChange={setProposalId} disabled={busy} required /><Button type="submit" variant="secondary" disabled={busy || !proposalId.trim()}>현재 복구 제안 조회</Button></form>
    {failure ? <ErrorState error={failure} /> : null}
    {detail.state.status === "loading" ? <LoadingState label="현재 복구 제안과 담당자를 확인하는 중" /> : detail.state.status === "failed" ? <ErrorState error={detail.state.error} retry={detail.reload} /> : current && proposal ? <>
      <article className="surface-card management-card"><h3>현재 복구 제안</h3><StatusText state={proposal.state} label={labels[proposal.state]} /><p className="support-case-reference">{proposal.proposalId}</p><p>누락된 취소 환불 기록을 복구하고 기존 환불의 결과 조회를 준비합니다.</p><dl className="detail-list"><div><dt>복구 건</dt><dd className="support-case-reference">{proposal.caseId}</dd></div><div><dt>제안자</dt><dd className="support-case-reference">{proposal.proposedBy}</dd></div><div><dt>생성</dt><dd>{fullDateTime.format(new Date(proposal.createdAt))}</dd></div><div><dt>승인 기한</dt><dd>{fullDateTime.format(new Date(proposal.expiresAt))}</dd></div>{proposal.decidedBy ? <div><dt>판정자</dt><dd className="support-case-reference">{proposal.decidedBy}</dd></div> : null}{proposal.decidedAt ? <div><dt>판정</dt><dd>{fullDateTime.format(new Date(proposal.decidedAt))}</dd></div> : null}</dl><p>문의 코드 {proposal.correlationId}</p></article>
      {proposal.state === "EXECUTED" ? <InlineNotice title="환불 결과 조회를 위한 복구가 실행되었습니다" description="실제 환불 완료 여부는 주문 후속 처리에서 다시 확인해 주세요." /> : proposal.state === "PENDING_APPROVAL" ? expired ? <InlineNotice title="복구 제안의 승인 기한이 지났습니다" description="현재 복구 건을 다시 확인하고 새 제안을 준비해 주세요." /> : proposal.proposedBy === current.actor.operatorId ? <InlineNotice title="제안자와 다른 담당자가 판정해야 합니다" description="다른 권한 있는 담당자에게 이 제안 ID로 검토를 요청해 주세요." /> : <section className="surface-card management-card"><h3>다른 담당자의 복구 판정</h3><TextField label="복구 판정 사유" value={decisionReason} onValueChange={setDecisionReason} maxLength={500} disabled={busy} required /><div className="button-row"><Button disabled={busy || !decisionReason.trim()} onClick={() => void decide("APPROVE")}>복구 승인</Button><Button variant="secondary" disabled={busy || !decisionReason.trim()} onClick={() => void decide("REJECT")}>복구 반려</Button></div></section> : null}
      <Button variant="ghost" disabled={busy} onClick={detail.reload}>복구 제안 상태 다시 확인</Button>
    </> : null}
  </section>;
}
