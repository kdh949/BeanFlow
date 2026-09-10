import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { Button, ButtonLink, Checkbox, EmptyState, InlineNotice, LoadingState, PageHeading, SelectField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { useExpired } from "./useSupportExpiry";
import { useSupportCommand } from "./useSupportCommand";
import { personalFieldLabels, personalFieldsBySubject, verificationPurposeLabels } from "./supportSecurityLabels";
type Case = { caseId: string; state: string; subjectLinks: readonly { linkId: string; subjectId: string; subjectType: string }[] };
type Field = components["schemas"]["SupportPersonalDataField"];
type Reason = components["schemas"]["BreakGlassReasonCode"];
type Reveal = components["schemas"]["BreakGlassRevealResource"];
const reasons: Record<Reason, { label: string; purpose: "SAFETY_RESPONSE" | "FRAUD_INVESTIGATION" | "PRIVACY_INCIDENT" }> = { IMMEDIATE_SAFETY: { label: "즉각적인 안전 위협", purpose: "SAFETY_RESPONSE" }, ACTIVE_FRAUD: { label: "진행 중인 부정 사용", purpose: "FRAUD_INVESTIGATION" }, PRIVACY_INCIDENT: { label: "개인정보 사고", purpose: "PRIVACY_INCIDENT" } };
const states: Record<components["schemas"]["BreakGlassState"], string> = { APPROVAL_PENDING: "별도 승인 대기", ACTIVE: "한 번 열람 가능", DENIED: "열람 반려", REVIEW_PENDING: "독립 사후 검토 대기", REVIEWED: "사후 검토 기록 완료", EXPIRED: "열람 기한 만료", REVOKED: "열람 철회" };
const uuid = (value: string) => /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value.trim());

/** Composes emergency single-field requests with separated approval, one-time reveal and post-review. */
export function SupportBreakGlassWorkspace({ supportCase, initialRequestId, onBusyChange }: { supportCase?: Case; initialRequestId?: string; onBusyChange?: (busy: boolean) => void }) {
  const [id, setId] = useState(initialRequestId ?? ""), [lookup, setLookup] = useState(initialRequestId ?? ""), [active, setActive] = useState(false);
  useEffect(() => { onBusyChange?.(active); return () => onBusyChange?.(false); }, [active, onBusyChange]);
  return <section className="management-workspace" aria-label="긴급 개인정보 열람"><h2>긴급 개인정보 열람</h2><InlineNotice tone="warning" title="긴급한 목적에 필요한 한 필드만 요청합니다" description="기존 본인확인과 구분된 예외 업무입니다. 별도 승인 후 2분 안에 한 번 열람하며, 독립된 사후 검토와 보안 통지 대상입니다." />
    <form className="surface-card management-card operation-form" onSubmit={event => { event.preventDefault(); if (!active && uuid(lookup)) setId(lookup.trim()); }}><TextField label="기존 긴급 열람 요청 ID" value={lookup} onValueChange={setLookup} required disabled={active} /><Button type="submit" variant="secondary" disabled={active || !uuid(lookup)}>긴급 요청 열기</Button>{supportCase && id ? <Button variant="ghost" disabled={active} onClick={() => { setId(""); setLookup(""); }}>새 긴급 요청</Button> : null}</form>
    {id ? <Inspection key={id} id={id} caseId={supportCase?.caseId} onBusyChange={setActive} /> : supportCase ? <CreateRequest supportCase={supportCase} onBusyChange={setActive} onCreated={created => { setId(created); setLookup(created); }} /> : <EmptyState title="상담 건에서 긴급 요청을 시작해 주세요" description="기존 요청은 ID로 현재 승인과 사후 검토 상태를 조회합니다." />}
  </section>;
}
function CreateRequest({ supportCase, onCreated, onBusyChange }: { supportCase: Case; onCreated: (id: string) => void; onBusyChange: (busy: boolean) => void }) {
  const links = supportCase.subjectLinks.filter(link => ["CUSTOMER", "STORE", "DELIVERY"].includes(link.subjectType));
  const [linkId, setLinkId] = useState(links[0]?.linkId ?? ""), [selected, setField] = useState<Field | null>(null), [reason, setReason] = useState<Reason>("IMMEDIATE_SAFETY"), [checked, setChecked] = useState(false);
  const link = links.find(item => item.linkId === linkId), fields = link ? personalFieldsBySubject[link.subjectType as keyof typeof personalFieldsBySubject] : [], field = selected && fields.includes(selected) ? selected : fields[0];
  const command = useSupportCommand(() => {}), busy = command.busy || command.pending;
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false); }, [busy, onBusyChange]);
  function submit() {
    if (busy || !link || !field || !checked || ["RESOLVED", "CLOSED"].includes(supportCase.state)) return;
    const caseId = supportCase.caseId, body = { subjectLinkId: linkId, field, purpose: reasons[reason].purpose, reasonCode: reason };
    command.submit(JSON.stringify({ caseId, body }), async key => { const result = unwrap(await operationsApi.POST("/support/cases/{caseId}/break-glass-requests", { params: { path: { caseId }, header: { "Idempotency-Key": key } }, body })); onCreated(result.requestId); }, () => {});
  }
  return <div className="surface-card management-card management-workspace"><h3>긴급 열람 요청 작성</h3>{links.length ? <><SelectField label="긴급 열람 대상" value={linkId} onValueChange={value => { setLinkId(value); setField(null); }} disabled={busy}>{links.map(item => <option key={item.linkId} value={item.linkId}>{item.subjectType === "CUSTOMER" ? "고객" : item.subjectType === "STORE" ? "매장" : "배송 담당자"} · {item.subjectId}</option>)}</SelectField><SelectField label="긴급 열람 필드" value={field ?? ""} onValueChange={value => setField(value as Field)} disabled={busy}>{fields.map(item => <option key={item} value={item}>{personalFieldLabels[item]}</option>)}</SelectField><SelectField label="긴급 열람 사유" value={reason} onValueChange={value => setReason(value as Reason)} disabled={busy}>{Object.entries(reasons).map(([value, descriptor]) => <option key={value} value={value}>{descriptor.label}</option>)}</SelectField><Checkbox label="긴급 상황에서 이 필드가 꼭 필요함을 확인했습니다" checked={checked} onCheckedChange={setChecked} disabled={busy} /><Button disabled={busy || !checked || ["RESOLVED", "CLOSED"].includes(supportCase.state)} onClick={submit}>긴급 열람 승인 요청</Button></> : <EmptyState title="긴급 열람할 대상이 없습니다" description="상담에 고객·매장·배송 담당자를 먼저 연결해 주세요." />}{command.failure ? <ErrorState error={command.failure} /> : null}{command.pending ? <InlineNotice tone="warning" title="긴급 요청 결과를 확인하지 못했습니다" description="같은 요청으로 등록 결과를 확인해 주세요." action={<Button loading={command.busy} onClick={() => void command.retry()}>같은 긴급 요청 확인</Button>} /> : null}</div>;
}
function Inspection({ id, caseId, onBusyChange }: { id: string; caseId?: string; onBusyChange: (busy: boolean) => void }) {
  const read = useResource(useCallback(async () => { const workflow = unwrap(await operationsApi.GET("/support/break-glass-requests/{requestId}/workflow", { params: { path: { requestId: id } } })); if (caseId && workflow.request.caseId !== caseId) throw new ApiRequestError(409, "RESOURCE_STATE_CONFLICT", "현재 상담의 긴급 요청이 아닙니다"); return workflow; }, [id, caseId]));
  const current = read.state.status === "ready" ? read.state.value : null, request = current?.request;
  const [raw, setRaw] = useState<Reveal | null>(null), [revealBusy, setRevealBusy] = useState(false), [revealed, setRevealed] = useState(false), [uncertain, setUncertain] = useState(false), [error, setError] = useState<unknown>(null), [checked, setChecked] = useState(false), [message, setMessage] = useState(""), [review, setReview] = useState<"CONFIRMED" | "ESCALATED">("CONFIRMED");
  const inFlight = useRef(false), generation = useRef(0), mounted = useRef(true), clear = useCallback(() => { generation.current++; setRaw(null); }, []);
  const expired = useExpired(request?.expiresAt ?? undefined), command = useSupportCommand(() => { clear(); setChecked(false); read.reload(); }), busy = revealBusy || command.busy || command.pending;
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false); }, [busy, onBusyChange]);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; }; }, []);
  useEffect(() => { if (read.state.status === "failed" || (current && !current.canViewRevealedValue) || expired) clear(); }, [read.state.status, current?.canViewRevealedValue, expired, clear]);
  useEffect(() => { const leave = (event: Event) => { if (event.type === "blur" && event.target instanceof Element) return; if (event.type !== "visibilitychange" || document.hidden) clear(); }; window.addEventListener("blur", leave); document.addEventListener("visibilitychange", leave); return () => { window.removeEventListener("blur", leave); document.removeEventListener("visibilitychange", leave); }; }, [clear]);
  useEffect(() => { if (!raw) return; const timer = window.setTimeout(clear, 60_000), poll = window.setInterval(read.refresh, 15_000); return () => { window.clearTimeout(timer); window.clearInterval(poll); }; }, [raw, clear, read.refresh]);
  const allowed = (action: components["schemas"]["BreakGlassWorkflowAction"]) => !!current?.allowedActions.includes(action);
  function decide(decision: "APPROVE" | "DENY") {
    if (!request || busy || !allowed("DECIDE") || (decision === "APPROVE" && !checked)) return;
    const body = { decision, expectedVersion: request.version };
    command.submit(JSON.stringify(body), key => operationsApi.POST("/support/break-glass-requests/{requestId}/approvals", { params: { path: { requestId: id }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => setMessage("긴급 열람 승인 결정을 기록했습니다"));
  }
  async function reveal() {
    if (!request || busy || inFlight.current || revealed || uncertain || expired || !allowed("REVEAL")) return;
    clear(); const token = generation.current; inFlight.current = true; setRevealBusy(true); setError(null); setRevealed(true);
    try { const response = unwrap(await operationsApi.POST("/support/break-glass-requests/{requestId}/reveals", { params: { path: { requestId: id }, header: { "Idempotency-Key": new SubmissionIntent().keyFor(id) } }, body: { field: request.field } })); if (mounted.current && token === generation.current && !document.hidden && request.expiresAt && Date.now() < Date.parse(request.expiresAt)) setRaw(response); }
    catch (failure) { if (mounted.current) { setError(failure); setUncertain(true); } }
    finally { inFlight.current = false; if (mounted.current) { setRevealBusy(false); read.refresh(); } }
  }
  function recordReview() {
    if (!request || busy || !allowed("REVIEW")) return;
    const body = { decision: review, expectedVersion: request.version, reasonCode: review === "CONFIRMED" ? "POLICY_CONFIRMED" : "ADDITIONAL_REVIEW_REQUIRED" };
    command.submit(JSON.stringify(body), key => operationsApi.POST("/support/break-glass-requests/{requestId}/reviews", { params: { path: { requestId: id }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => setMessage("사후 검토 결과를 기록했습니다"));
  }
  const displayState = request?.state === "ACTIVE" && expired ? "EXPIRED" : request?.state;
  return <div className="surface-card management-card management-workspace"><h3>긴급 요청 검토</h3>{message ? <p role="status">{message}</p> : null}{command.failure ? <ErrorState error={command.failure} /> : null}{error ? <ErrorState error={error} /> : null}{command.pending ? <InlineNotice tone="warning" title="긴급 요청 결정 결과를 확인하지 못했습니다" description="같은 판정으로 결과를 확인해 주세요." action={<Button loading={command.busy} onClick={() => void command.retry()}>같은 긴급 결정 확인</Button>} /> : null}{uncertain ? <InlineNotice tone="warning" title="긴급 열람 응답을 확인하지 못했습니다" description="한 번의 열람이 이미 소비되었을 수 있습니다. 원문을 다시 요청하지 말고 현재 상태와 감사 이력을 확인해 주세요." /> : null}
    {read.state.status === "loading" ? <LoadingState label="긴급 요청과 현재 권한을 확인하는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : current && request && displayState ? <><p className="support-case-reference">긴급 요청 ID {id}</p><StatusText state={displayState} label={states[displayState]} /><dl className="detail-list"><dt>대상</dt><dd className="support-case-reference">{request.subjectId}</dd><dt>한 필드</dt><dd>{personalFieldLabels[request.field]}</dd><dt>목적</dt><dd>{verificationPurposeLabels[request.purpose]}</dd><dt>긴급 사유</dt><dd>{reasons[request.reasonCode].label}</dd><dt>열람 만료</dt><dd>{request.expiresAt ? fullDateTime.format(new Date(request.expiresAt)) : "별도 승인 후 2분"}</dd></dl>
      <div className="button-row"><Button variant="secondary" disabled={busy} onClick={() => { clear(); read.reload(); }}>긴급 요청 상태 새로고침</Button><ButtonLink variant="secondary" to={`/support/break-glass/${id}`}>긴급 요청 검토 주소</ButtonLink></div>
      {allowed("DECIDE") ? <><Checkbox label="긴급 사유와 최소 필드 범위를 확인했습니다" checked={checked} onCheckedChange={setChecked} disabled={busy} /><div className="button-row"><Button disabled={busy || !checked} onClick={() => decide("APPROVE")}>긴급 열람 승인</Button><Button variant="secondary" disabled={busy} onClick={() => decide("DENY")}>긴급 열람 반려</Button></div></> : null}
      {allowed("REVEAL") && !expired && !revealed && !uncertain ? <Button disabled={busy || read.refreshing} onClick={() => void reveal()}>긴급 정보 한 번 열람</Button> : null}
      {current.postReview ? <p>사후 검토 결과 · {current.postReview.decision === "CONFIRMED" ? "정책 준수 확인" : "추가 검토 필요"} · {fullDateTime.format(new Date(current.postReview.decidedAt))}</p> : null}
      {allowed("REVIEW") ? <div className="operation-form"><SelectField label="긴급 열람 사후 검토" value={review} onValueChange={value => setReview(value as typeof review)} disabled={busy}><option value="CONFIRMED">정책 준수 확인</option><option value="ESCALATED">추가 검토 필요</option></SelectField><p>요청자·승인자와 다른 검토자의 결정을 기록합니다. 추가 검토 필요도 기록 완료와 구분해 남깁니다.</p><Button disabled={busy} onClick={recordReview}>사후 검토 결과 기록</Button></div> : null}
    </> : null}
    {raw && current?.canViewRevealedValue && !expired ? <section className="support-reveal" aria-label="긴급 한시 열람 정보"><h3>최대 60초 뒤 자동으로 숨겨지는 정보</h3><p>{personalFieldLabels[raw.field]} <strong>{raw.value}</strong></p><Button variant="secondary" onClick={clear}>긴급 원문 지금 지우기</Button></section> : null}
  </div>;
}
export function SupportBreakGlassPage() { const { requestId } = useParams(); return <div className="console-page"><PageHeading title="긴급 개인정보 열람 검토" /><SupportBreakGlassWorkspace initialRequestId={requestId} /></div>; }
