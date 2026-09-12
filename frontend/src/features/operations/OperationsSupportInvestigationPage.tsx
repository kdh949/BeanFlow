import { caseCategoryLabels } from "../support/supportCaseLabels";
import { useCallback, useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { ApiRequestError, unwrap } from "../../api/client";
import { Button, ButtonLink, Checkbox, EmptyState, InlineNotice, LoadingState, PageHeading, SelectField, TextAreaField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { won, fullDateTime } from "../../lib/format";
import { profileDigest, profilePurposes, validProfileValues } from "../../lib/supportProfilePayload";
import { supportDigest } from "../../lib/supportOrderPayload";
import { useResource } from "../shared/useResource";
import { useExpired } from "../support/useSupportExpiry";
import { useSupportCommand } from "../support/useSupportCommand";
import { ProfileFields, useProfileValues } from "../support/SupportProfileChangeWorkspace";
type Compensation = components["schemas"]["OperationsSupportCompensationReview"];
type Decision = components["schemas"]["OperationsSupportInvestigationDecision"];
const investigationStates: Record<string, string> = { OPEN: "검토 중", APPROVED: "승인", DENIED: "반려", RETURNED: "수정 요청", ESCALATED: "추가 조사", EXPIRED: "만료", STALE: "조건 변경" };
const approvalStates: Record<string, string> = { AWAITING_SUPPORT_MANAGER: "상담 승인 대기", AWAITING_OPERATIONS: "운영 검토 대기", READY_FOR_EXECUTION: "실행 준비", REVISION_REQUIRED: "수정 필요", REASSIGNMENT_REQUIRED: "담당자 재배정 필요", DENIED: "반려", MANUAL_REVIEW: "수동 확인 필요", STALE: "조건 변경", EXPIRED: "만료", EXECUTED: "실행 완료" };
const stale = () => new ApiRequestError(409, "SUPPORT_ACTION_REQUEST_STALE", "현재 조사와 승인안이 일치하지 않습니다");

/** Locates the current revision's investigation and records a separated operator decision. */
export function OperationsSupportInvestigationPage() {
  const [params] = useSearchParams(), [id, setId] = useState(params.get("requestId") ?? ""), [busy, setBusy] = useState(false);
  const [showQueue, setShowQueue] = useState(!id);
  return <div className="console-page management-workspace"><PageHeading title="상담 요청 운영 검토" /><p>상담 요청의 현재 승인안을 확인하고 운영 조사 결정을 기록합니다.</p>
    <Button variant="secondary" disabled={busy} onClick={() => setShowQueue(open => !open)}>{showQueue ? "검토 목록 닫기" : "검토 목록에서 선택"}</Button>
    {showQueue ? <InvestigationQueue disabled={busy} onSelect={requestId => { if (!busy) { setId(requestId); setShowQueue(false); } }} /> : null}
    {id ? <Investigation key={id} id={id} onBusyChange={setBusy} /> : <EmptyState title="검토할 요청을 선택해 주세요" description="업무 종류와 상담 접수 시각을 확인해 현재 검토할 요청을 선택합니다." />}
  </div>;
}
function Investigation({ id, onBusyChange }: { id: string; onBusyChange: (busy: boolean) => void }) {
  const read = useResource(useCallback(async () => {
    const { request, profile, compensation } = unwrap(await operationsApi.GET("/operations/support-action-requests/{requestId}/review", { params: { path: { requestId: id } } }));
    const workflow = unwrap(await operationsApi.GET("/operations/investigations", { params: { query: { supportActionRequestId: id, revisionNumber: request.revisionNumber } } }));
    if (request.requestId !== id || workflow.investigation.supportActionRequestId !== id || workflow.investigation.revisionNumber !== request.revisionNumber) throw stale();
    if (workflow.canDecide && request.state === "AWAITING_OPERATIONS") {
      if (request.action === "PROFILE_CHANGE" && (!profile || profile.payloadDigest !== request.actionPayloadDigest || profile.currentProfileVersion !== request.targetVersion)) throw stale();
      if (request.action === "GOODWILL_COMPENSATION" && (!compensation || compensation.payloadDigest !== request.actionPayloadDigest || compensation.currentTargetVersion !== request.targetVersion)) throw stale();
    }
    return { request, workflow, profile, compensation };
  }, [id]));
  const value = read.state.status === "ready" ? read.state.value : null, profile = value?.profile;
  const raw = useProfileValues(`${id}:${profile?.purpose}:${value?.request.requestVersion}:${read.state.status}`);
  const [digest, setDigest] = useState(""), [checked, setChecked] = useState(false), [decision, setDecision] = useState<Decision>("APPROVE"), [reason, setReason] = useState(""), [evidence, setEvidence] = useState(""), [preparing, setPreparing] = useState(false), [error, setError] = useState<unknown>(null), [result, setResult] = useState<components["schemas"]["OperationsSupportInvestigationDecisionResource"] | null>(null);
  const preparingRef = useRef(false), command = useSupportCommand(`operations-investigation:${id}`, () => { raw.clear(); setChecked(false); });
  const busy = preparing || command.busy || command.pending;
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false); }, [busy, onBusyChange]);
  useEffect(() => { let live = true; setDigest(""); if (profile && validProfileValues(profile.purpose, raw.values)) void profileDigest(profile.subjectId, profile.expectedProfileVersion, profile.purpose, raw.values).then(hash => { if (live) setDigest(hash); }, failure => { if (live) setError(failure); }); return () => { live = false; }; }, [profile?.subjectId, profile?.purpose, profile?.expectedProfileVersion, raw.values]);
  const expired = useExpired(value?.workflow.investigation.expiresAt);
  const canDecide = !!value?.workflow.canDecide && value.request.state === "AWAITING_OPERATIONS" && !expired && !result;
  const reviewed = profile ? digest === profile.payloadDigest : !!value?.compensation && checked;
  async function decide() {
    if (!value || !canDecide || busy || preparingRef.current || !reason.trim() || !evidence.trim() || (decision === "APPROVE" && !reviewed)) return;
    preparingRef.current = true; setPreparing(true); setError(null);
    try {
      const body = { expectedVersion: value.workflow.investigation.version, decision, reason: reason.trim(), evidenceDigest: await supportDigest(evidence.trim()) }, investigationId = value.workflow.investigation.investigationId;
      await command.submit(JSON.stringify({ investigationId, body }), key => operationsApi.POST("/operations/investigations/{investigationId}/decisions", { params: { path: { investigationId }, header: { "Idempotency-Key": key } }, body }).then(unwrap).then(response => setResult(response)), () => {});
    } catch (failure) { setError(failure); } finally { raw.clear(); preparingRef.current = false; setPreparing(false); }
  }
  return <section className="surface-card management-card management-workspace" aria-label="현재 운영 조사"><h2>현재 승인안 검토</h2>
    {result ? <><p role="status">운영 결정을 기록했습니다</p><p>조사 결과 · <StatusText state={result.state} label={investigationStates[result.state]} /></p><p>상담 승인 상태 · <StatusText state={result.supportRequestState} label={approvalStates[result.supportRequestState]} /></p><p>승인 후 실제 정보 변경과 혜택 지급은 담당자의 실행 결과에서 확인합니다.</p></> : null}
    {command.failure ? <ErrorState error={command.failure} /> : null}{error ? <ErrorState error={error} /> : null}
    {command.pending ? <InlineNotice tone="warning" title="운영 결정 결과를 확인하지 못했습니다" description="같은 요청으로 결과를 확인할 때까지 다른 명령을 잠급니다." action={<Button loading={command.busy} onClick={() => void command.retry()}>같은 운영 결정 확인</Button>} /> : null}
    {read.state.status === "loading" ? <LoadingState label="현재 승인안과 운영 조사를 읽는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : value ? <>
      <p className="support-case-reference">조사 ID {value.workflow.investigation.investigationId}</p><p>승인안 {value.request.revisionNumber} · 조사 버전 {result?.version ?? value.workflow.investigation.version}</p>
      {!result ? <p><StatusText state={value.workflow.investigation.state} label={investigationStates[value.workflow.investigation.state]} /> · <StatusText state={value.request.state} label={approvalStates[value.request.state]} /></p> : null}
      <dl className="detail-list"><dt>상담 건</dt><dd className="support-case-reference">{value.request.caseId}</dd><dt>검토 내용 해시</dt><dd className="support-case-reference">{value.request.actionPayloadDigest}</dd><dt>요청 근거 해시</dt><dd className="support-case-reference">{value.request.evidenceDigest}</dd></dl>
      <div className="button-row"><Button variant="secondary" disabled={busy} onClick={() => { raw.clear(); setResult(null); setChecked(false); read.reload(); }}>운영 조사 새로고침</Button><ButtonLink variant="secondary" to={`/support/follow-up?caseId=${value.request.caseId}`}>상담 후속 업무</ButtonLink></div>
      {canDecide ? <>
        {profile ? <><h3>{profilePurposes[profile.purpose].label}</h3><p>현재 프로필 버전 {value.profile!.currentProfileVersion}</p><ProfileFields purpose={profile.purpose} values={raw.values} onChange={raw.setValues} disabled={busy} /><p role="status">{reviewed ? "입력한 내용이 승인안과 일치합니다" : "승인할 내용을 다시 입력해 승인안과 대조해 주세요"}</p></> : value.compensation ? <CompensationReview value={value.compensation} checked={checked} onChecked={setChecked} disabled={busy} /> : <InlineNotice tone="warning" title="이 요청은 직접 승인할 수 없습니다" description="현재 승인 정책에서 운영 검토 내용을 제공하는 정보 정정·고객 보상 요청인지 확인하고, 수정 요청 또는 추가 조사로 돌려보내 주세요." />}
        <form className="operation-form" onSubmit={event => { event.preventDefault(); void decide(); }}><SelectField label="운영 검토 결정" value={decision} onValueChange={next => setDecision(next as Decision)} disabled={busy}><option value="APPROVE">승인</option><option value="DENY">반려</option><option value="RETURN_FOR_REVISION">수정 요청</option><option value="ESCALATE">추가 조사</option></SelectField><TextAreaField label="운영 검토 사유" value={reason} onValueChange={setReason} required maxLength={500} disabled={busy} description="개인정보나 인증 원문을 기록하지 않습니다." /><TextField label="운영 검토 증빙 참조" value={evidence} onValueChange={setEvidence} required maxLength={500} disabled={busy} description="확인한 기록의 참조를 해시로 전송합니다." /><Button type="submit" disabled={busy || !reason.trim() || !evidence.trim() || (decision === "APPROVE" && !reviewed)}>운영 검토 결정 기록</Button></form>
      </> : !result ? <InlineNotice title="현재 계정에서 결정할 수 없습니다" description="요청자·상담 승인자·실행자와 다른 운영 검토자의 권한과 승인안의 유효 상태를 확인해 주세요." /> : null}
    </> : null}
  </section>;
}
function CompensationReview({ value, checked, onChecked, disabled }: { value: Compensation; checked: boolean; onChecked: (value: boolean) => void; disabled: boolean }) {
  return <div className="management-workspace"><h3>고객 보상 조건</h3><p>{value.benefitType === "POINT" ? "포인트" : "쿠폰"} {won.format(value.amountKrw)}</p><p>비용 책임 · {{ PLATFORM: "플랫폼", STORE: "매장", SHARED: "분담", UNDETERMINED: "미확정" }[value.terms.responsibility]} · 플랫폼 {value.terms.platformShareBps / 100}% · 매장 {value.terms.storeShareBps / 100}%</p>
    {value.terms.evidenceBasis ? <p>비용 근거 · {{ STORE_CONSENT: "매장 동의", OPERATIONS_FINDING: "운영 조사 결과", CONTRACTUAL_RULE: "계약 규칙" }[value.terms.evidenceBasis]}</p> : null}
    {value.couponTemplate ? <p>쿠폰 사용 기한 {value.couponTemplate.validityDays}일 · 최소 사용 금액 {won.format(value.couponTemplate.minimumEligibleSubtotalKrw)}</p> : null}
    <dl className="detail-list"><dt>사고 ID</dt><dd className="support-case-reference">{value.incidentId}</dd><dt>관련 주문</dt><dd className="support-case-reference">{value.orderId ?? "관련 주문 없음"}</dd>{value.terms.costEvidenceDigest ? <><dt>비용 근거 해시</dt><dd className="support-case-reference">{value.terms.costEvidenceDigest}</dd></> : null}</dl><Checkbox label="보상 혜택과 비용 조건을 확인했습니다" checked={checked} onCheckedChange={onChecked} disabled={disabled} /></div>;
}

function InvestigationQueue({ disabled, onSelect }: { disabled: boolean; onSelect: (requestId: string) => void }) {
  const [state, setState] = useState<components["schemas"]["OperationsSupportInvestigationState"] | "">("OPEN");
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const cursor = cursors.at(-1);
  const read = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/investigation-queue", { params: { query: { state: state || undefined, cursor, limit: 20 } } })), [state, cursor]));
  const labels: Record<components["schemas"]["SupportActionType"], string> = { ORDER_CANCELLATION: "주문 취소", PICKUP_RESCHEDULE: "픽업 시간 변경", POST_ACCEPTANCE_RESOLUTION: "수락 후 주문 해결", GOODWILL_COMPENSATION: "고객 불편 보상", PROFILE_CHANGE: "정보 정정" };
  return <fieldset className="catalog-fieldset management-workspace" disabled={disabled}><legend>운영 검토 요청</legend><SelectField label="검토 상태" value={state} onValueChange={value => { setState(value as typeof state); setCursors([undefined]); }}><option value="">전체 상태</option>{Object.entries(investigationStates).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
    {read.state.status === "loading" ? <LoadingState label="운영 검토 요청을 확인하는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : <>
      {read.state.value.items.length ? read.state.value.items.map(item => <article className="surface-card management-card" key={item.investigationId}><strong>{labels[item.request.action]}</strong><p>{caseCategoryLabels[item.request.caseCategory]} · {fullDateTime.format(new Date(item.request.caseOpenedAt))} 상담 접수</p><p>승인안 {item.request.revisionNumber} · {fullDateTime.format(new Date(item.expiresAt))}까지</p><StatusText state={item.state} label={investigationStates[item.state]} />{!item.canDecide ? <p>현재 계정에서는 검토 내용만 확인할 수 있습니다.</p> : null}<Button type="button" variant="secondary" onClick={() => onSelect(item.request.requestId)}>이 검토 열기</Button></article>) : <EmptyState title="현재 조회 구간에 검토 요청이 없습니다" description={read.state.value.nextCursor ? "다음 조회 구간을 확인해 주세요." : "현재 권한과 상태에 해당하는 검토 요청이 등록되면 여기에서 확인할 수 있습니다."} />}
      <div className="button-row"><Button type="button" variant="ghost" disabled={cursors.length < 2} onClick={() => setCursors(list => list.slice(0, -1))}>이전 검토</Button><Button type="button" variant="secondary" disabled={!read.state.value.nextCursor} onClick={() => { const next = read.state.status === "ready" ? read.state.value.nextCursor : null; if (next) setCursors(list => [...list, next]); }}>다음 검토</Button><Button type="button" variant="ghost" onClick={read.reload}>검토 목록 새로고침</Button></div>
    </>}
  </fieldset>;
}
