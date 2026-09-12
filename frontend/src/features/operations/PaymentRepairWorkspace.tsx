import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, InlineNotice, LoadingState, SelectField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
const labels = { PENDING_APPROVAL: "승인 대기", EXECUTED: "복구 실행됨", REJECTED: "반려됨", EXPIRED: "기한 만료", STALE: "현재 상태가 달라짐" };
type CaseItem = components["schemas"]["SetupRecoveryCaseItem"];
type ProposalItem = components["schemas"]["SetupRepairProposalItem"];
type Pending = { kind: "create"; target: string; body: { reason: string }; key: string } | { kind: "decide"; target: string; body: { reason: string; decision: "APPROVE" | "REJECT" }; key: string };

/** Directory selections carry internal identifiers; decisions still read current authority and proposal state. */
export function PaymentRepairWorkspace({ caseId, onLockChange }: { caseId?: string; onLockChange?: (locked: boolean) => void }) {
  const [selectedCase, setSelectedCase] = useState<CaseItem | null>(null);
  const [selectedProposal, setSelectedProposal] = useState<ProposalItem | null>(null);
  const [request, setRequest] = useState<{ id: string } | null>(null);
  const [reason, setReason] = useState("");
  const [decisionReason, setDecisionReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [created, setCreated] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [revision, setRevision] = useState(0);
  const submitting = useRef(false);
  const intent = useRef(new SubmissionIntent());
  const locked = busy || pending !== null;
  useEffect(() => { onLockChange?.(locked); }, [locked, onLockChange]);
  const detail = useResource(useCallback(async () => {
    if (!request) return null;
    const [proposal, actor] = await Promise.all([
      operationsApi.GET("/operations/reprocessing-repair-proposals/{proposalId}", { params: { path: { proposalId: request.id } } }).then(unwrap),
      operationsApi.GET("/operations/me").then(unwrap),
    ]);
    return { proposal, actor };
  }, [request]));
  const linkedCase = useResource(useCallback(async () => {
    void revision;
    if (!caseId) return null;
    return unwrap(await operationsApi.GET("/operations/payment-setup-recovery-cases", { params: { query: { caseId, limit: 1 } } })).items.find(item => item.caseId === caseId) ?? null;
  }, [caseId, revision]));
  const canCreate = caseId ? linkedCase.state.status === "ready" && linkedCase.state.value?.canPropose === true : selectedCase?.canPropose === true;
  const current = detail.state.status === "ready" ? detail.state.value : null;
  const proposal = current?.proposal;
  async function create() {
    const target = caseId ?? selectedCase?.caseId;
    if (locked || !canCreate || !target || !reason.trim()) return;
    const body = { reason: reason.trim() };
    await submit({ kind: "create", target, body, key: intent.current.keyFor(JSON.stringify({ kind: "create", target, ...body })) });
  }
  async function decide(decision: "APPROVE" | "REJECT") {
    if (locked || !proposal || !current || proposal.state !== "PENDING_APPROVAL" || current.actor.operatorId === proposal.proposedBy || !decisionReason.trim()) return;
    const body = { decision, reason: decisionReason.trim() };
    await submit({ kind: "decide", target: proposal.proposalId, body, key: intent.current.keyFor(JSON.stringify({ kind: "decide", target: proposal.proposalId, ...body })) });
  }
  async function submit(command: Pending) {
    if (submitting.current) return;
    submitting.current = true; setBusy(true); setFailure(null); setCreated(false);
    let unresolved = pending !== null;
    try {
      if (command.kind === "create") {
        const result = unwrap(await operationsApi.POST("/operations/reprocessing-cases/{caseId}/repair-proposals", { params: { path: { caseId: command.target }, header: { "Idempotency-Key": command.key } }, body: command.body }));
        setCreated(true); setReason(""); setDecisionReason(""); setRequest({ id: result.proposalId });
        setSelectedProposal(selectedCase ? { proposal: result, order: selectedCase.order } : null);
      } else {
        unwrap(await operationsApi.POST("/operations/reprocessing-repair-proposals/{proposalId}/decisions", { params: { path: { proposalId: command.target }, header: { "Idempotency-Key": command.key } }, body: command.body }));
        setDecisionReason(""); detail.reload();
      }
      unresolved = false; setPending(null); intent.current.complete(); setRevision(value => value + 1);
    } catch (error) {
      setFailure(error);
      const terminal = error instanceof ApiRequestError && ["REPROCESSING_PROPOSAL_EXPIRED", "REPROCESSING_PROPOSAL_STALE", "REPROCESSING_NOT_SAFE", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_MANUAL_REVIEW_REQUIRED"].includes(error.code);
      unresolved = !terminal && (unresolved || !(error instanceof ApiRequestError) || error.status >= 500 || error.status === 408 || error.code === "IDEMPOTENCY_REQUEST_IN_PROGRESS");
      if (unresolved) setPending(command); else { setPending(null); setSelectedCase(null); intent.current.complete(); detail.reload(); setRevision(value => value + 1); }
    } finally { submitting.current = false; setBusy(false); }
  }
  return <section className="management-workspace"><h2>누락 환불 복구 제안</h2>
    {!caseId ? <RecoveryCases disabled={locked} revision={revision} selected={selectedCase} onSelect={item => { setSelectedCase(item); setReason(""); setFailure(null); setCreated(false); }} /> : <><p>위 주문에서 확인된 환불 처리 정보의 복구를 검토합니다.</p>{linkedCase.state.status === "loading" ? <LoadingState label="현재 복구 건의 제안 가능 여부를 확인하는 중" /> : linkedCase.state.status === "failed" ? <ErrorState error={linkedCase.state.error} retry={locked ? undefined : linkedCase.reload} /> : !canCreate ? <InlineNotice title="현재 복구 건은 새 제안을 만들 수 없습니다" description="복구 건의 현재 상태와 기존 제안을 확인해 주세요." /> : null}</>}
    <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); void create(); }}><fieldset className="catalog-fieldset" disabled={locked}><legend>복구 제안 생성</legend>{selectedCase ? <p>선택한 주문: {selectedCase.order.storeName} · {selectedCase.order.publicReference}</p> : null}<TextField label="복구 제안 사유" value={reason} onValueChange={setReason} maxLength={500} required /><Button type="submit" loading={busy} disabled={!canCreate || !reason.trim()}>복구 제안 생성</Button></fieldset></form>
    {created ? <p role="status">복구 제안을 만들었습니다. 다른 담당자의 판정이 필요합니다.</p> : null}
    <RepairProposals caseId={caseId} disabled={locked} revision={revision} onSelect={item => { setSelectedProposal(item); setRequest({ id: item.proposal.proposalId }); setDecisionReason(""); setFailure(null); setCreated(false); }} />
    {pending ? <section className="surface-card management-card"><InlineNotice tone="warning" title="복구 요청 결과를 확인하지 못했습니다" description="대상과 요청 내용을 유지합니다. 같은 요청으로 결과를 확인해 주세요." /><Button loading={busy} onClick={() => void submit(pending)}>같은 복구 요청 결과 확인</Button></section> : null}
    {failure ? <ErrorState error={failure} /> : null}
    {detail.state.status === "loading" ? <LoadingState label="현재 복구 제안과 담당자를 확인하는 중" /> : detail.state.status === "failed" ? <ErrorState error={detail.state.error} retry={locked ? undefined : detail.reload} /> : current && proposal ? <>
      <article className="surface-card management-card"><h3>현재 복구 제안</h3><StatusText state={proposal.state} label={labels[proposal.state]} />{selectedProposal ? <p>{selectedProposal.order.storeName} · {selectedProposal.order.publicReference}</p> : null}<p>누락된 취소 환불 기록을 복구하고 기존 환불의 결과 조회를 준비합니다.</p><dl className="detail-list"><div><dt>제안자</dt><dd>{proposal.proposedBy === current.actor.operatorId ? "내가 만든 제안" : "다른 담당자의 제안"}</dd></div><div><dt>생성</dt><dd>{fullDateTime.format(new Date(proposal.createdAt))}</dd></div><div><dt>승인 기한</dt><dd>{fullDateTime.format(new Date(proposal.expiresAt))}</dd></div>{proposal.decidedAt ? <div><dt>판정</dt><dd>{fullDateTime.format(new Date(proposal.decidedAt))}</dd></div> : null}</dl><p>문의 코드 {proposal.correlationId}</p></article>
      {proposal.state === "EXECUTED" ? <InlineNotice title="환불 결과 조회를 위한 복구가 실행되었습니다" description="실제 환불 완료 여부는 주문 후속 처리에서 다시 확인해 주세요." /> : proposal.state === "EXPIRED" ? <InlineNotice title="복구 제안의 승인 기한이 지났습니다" description="현재 복구 건을 다시 확인하고 새 제안을 준비해 주세요." /> : proposal.state === "PENDING_APPROVAL" ? proposal.proposedBy === current.actor.operatorId ? <InlineNotice title="제안자와 다른 담당자가 판정해야 합니다" description="다른 권한 있는 담당자가 승인 대기 목록에서 검토할 수 있습니다." /> : <section className="surface-card management-card"><h3>다른 담당자의 복구 판정</h3><TextField label="복구 판정 사유" value={decisionReason} onValueChange={setDecisionReason} maxLength={500} disabled={locked} required /><div className="button-row"><Button disabled={locked || !decisionReason.trim()} onClick={() => void decide("APPROVE")}>복구 승인</Button><Button variant="secondary" disabled={locked || !decisionReason.trim()} onClick={() => void decide("REJECT")}>복구 반려</Button></div></section> : null}
      <Button variant="ghost" disabled={locked} onClick={detail.reload}>복구 제안 상태 다시 확인</Button>
    </> : null}
  </section>;
}

function RecoveryCases({ disabled, revision, selected, onSelect }: { disabled: boolean; revision: number; selected: CaseItem | null; onSelect: (item: CaseItem | null) => void }) {
  const [query, setQuery] = useState<{ status?: CaseItem["status"]; cursor?: string }>({ status: "OPEN" });
  const resource = useResource(useCallback(async () => { void revision; return unwrap(await operationsApi.GET("/operations/payment-setup-recovery-cases", { params: { query: { ...query, limit: 20 } } })); }, [query, revision]));
  function changeQuery(next: typeof query) { onSelect(null); setQuery(next); }
  return <section className="surface-card management-card"><h3>환불 복구 대상 목록</h3><fieldset className="catalog-fieldset" disabled={disabled}><legend>복구 대상 찾기</legend><SelectField label="복구 대상 상태" value={query.status ?? ""} onValueChange={value => changeQuery({ status: value ? value as CaseItem["status"] : undefined })}><option value="">전체</option><option value="OPEN">확인 대기</option><option value="MANUAL_REVIEW">수동 검토</option><option value="RUNNING">처리 중</option><option value="RESOLVED">해결됨</option></SelectField>
    {resource.state.status === "loading" ? <LoadingState label="환불 복구 대상을 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <>
      {resource.state.value.items.length === 0 ? <EmptyState title="해당 상태의 복구 대상이 없습니다" description="다른 상태를 선택하거나 목록을 새로고침해 주세요." /> : resource.state.value.items.map(item => <article className="management-card" key={item.caseId}><h4>{item.order.storeName}</h4><p>{item.order.publicReference}</p><p>주문 {fullDateTime.format(new Date(item.order.createdAt))}</p><StatusText state={item.status} /><Button variant="secondary" aria-pressed={selected?.caseId === item.caseId} disabled={!item.canPropose} onClick={() => { if (item.canPropose) onSelect(item); }}>{item.order.storeName} · {item.order.publicReference} 복구 대상 선택</Button></article>)}
      <div className="button-row">{query.cursor ? <Button variant="ghost" onClick={() => changeQuery({ status: query.status })}>복구 대상 처음으로</Button> : null}{resource.state.value.nextCursor ? <Button variant="secondary" onClick={() => { if (resource.state.status === "ready") changeQuery({ ...query, cursor: resource.state.value.nextCursor ?? undefined }); }}>다음 복구 대상</Button> : null}<Button variant="ghost" onClick={() => { onSelect(null); resource.reload(); }}>복구 대상 새로고침</Button></div>
    </>}</fieldset></section>;
}
function RepairProposals({ caseId, disabled, revision, onSelect }: { caseId?: string; disabled: boolean; revision: number; onSelect: (item: ProposalItem) => void }) {
  const [query, setQuery] = useState<{ state?: ProposalItem["proposal"]["state"]; cursor?: string }>({ state: "PENDING_APPROVAL" });
  const resource = useResource(useCallback(async () => { void revision; return unwrap(await operationsApi.GET("/operations/reprocessing-repair-proposals", { params: { query: { ...query, caseId, limit: 20 } } })); }, [query, caseId, revision]));
  return <section className="surface-card management-card"><h3>복구 제안 목록</h3><fieldset className="catalog-fieldset" disabled={disabled}><legend>검토할 제안 찾기</legend><SelectField label="복구 제안 상태" value={query.state ?? ""} onValueChange={value => setQuery({ state: value ? value as ProposalItem["proposal"]["state"] : undefined })}><option value="">전체</option>{Object.entries(labels).map(([state, label]) => <option key={state} value={state}>{label}</option>)}</SelectField>
    {resource.state.status === "loading" ? <LoadingState label="복구 제안 목록을 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <>
      {resource.state.value.items.length === 0 ? <EmptyState title="해당 상태의 복구 제안이 없습니다" description="다른 상태를 선택하거나 목록을 새로고침해 주세요." /> : resource.state.value.items.map(item => <article className="management-card" key={item.proposal.proposalId}><h4>{item.order.storeName}</h4><p>{item.order.publicReference}</p><StatusText state={item.proposal.state} label={labels[item.proposal.state]} /><p>제안 {fullDateTime.format(new Date(item.proposal.createdAt))} · 승인 기한 {fullDateTime.format(new Date(item.proposal.expiresAt))}</p><Button variant="secondary" onClick={() => onSelect(item)}>{item.order.storeName} · {item.order.publicReference} 제안 검토</Button></article>)}
      <div className="button-row">{query.cursor ? <Button variant="ghost" onClick={() => setQuery({ state: query.state })}>복구 제안 처음으로</Button> : null}{resource.state.value.nextCursor ? <Button variant="secondary" onClick={() => { if (resource.state.status === "ready") setQuery({ ...query, cursor: resource.state.value.nextCursor ?? undefined }); }}>다음 복구 제안</Button> : null}<Button variant="ghost" onClick={resource.reload}>복구 제안 목록 새로고침</Button></div>
    </>}</fieldset></section>;
}
