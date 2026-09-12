import { SupportWorkPicker } from "./SupportWorkPicker";
import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import type { components } from "../../api/schema";
import { SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, ButtonLink, Checkbox, InlineNotice, LoadingState, PageHeading, SelectField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { useSupportCommand } from "./useSupportCommand";
import { dataAccessReasonLabels, personalFieldLabels, personalFieldsBySubject, verificationPurposeLabels } from "./supportSecurityLabels";
type Session = components["schemas"]["VerificationSessionResource"];
type Field = components["schemas"]["SupportPersonalDataField"];
type Reason = components["schemas"]["DataAccessReasonCode"];
type Reveal = components["schemas"]["RevealedPersonalDataResource"];
const grantLabels: Record<components["schemas"]["DataAccessGrantState"], string> = { REQUESTED: "요청됨", APPROVAL_PENDING: "별도 승인 대기", ACTIVE: "열람 가능", DENIED: "열람 거부", CONSUMED: "열람 횟수 소진", EXPIRED: "기한 만료", REVOKED: "열람 철회" };
/** Field-scoped requests and a shareable, raw-data-free grant inspection entry. */
export function SupportDataAccessWorkspace({ session, initialGrantId }: { session?: Session | null; initialGrantId?: string }) {
  const [fields, setFields] = useState<Field[]>([]);
  const [reason, setReason] = useState<Reason>("CASE_HANDLING");
  const [grantId, setGrantId] = useState(initialGrantId ?? "");
  const [opened, setOpened] = useState(0);
  const command = useSupportCommand(`data-access-request:${session?.caseId ?? ""}`, () => {});
  const [inspectionLocked, setInspectionLocked] = useState(false);
  const locked = command.busy || command.pending || inspectionLocked;
  const sessionValid = session?.state === "VERIFIED" && session.actionScope === "PERSONAL_DATA_REVEAL" && Date.parse(session.expiresAt) > Date.now();
  const available = session ? personalFieldsBySubject[session.subjectType] : [];
  function requestGrant() {
    if (!session || !sessionValid || !fields.length || locked) return;
    const body = { verificationSessionId: session.sessionId, purpose: session.purpose, fields, reasonCode: reason };
    const caseId = session.caseId;
    command.submit(JSON.stringify({ caseId, body }), async key => { const grant = unwrap(await operationsApi.POST("/support/cases/{caseId}/data-access-grants", { params: { path: { caseId }, header: { "Idempotency-Key": key } }, body })); setGrantId(grant.grantId); setOpened(n => n + 1); }, () => {});
  }
  return <section className="management-workspace"><h2>제한형 개인정보 열람</h2>
    {sessionValid && session ? <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); requestGrant(); }}><fieldset className="catalog-fieldset" disabled={locked}><legend>필요한 정보만 선택</legend><p>{verificationPurposeLabels[session.purpose]} · 기본 정보는 10분/3회, 민감 정보는 별도 승인 후 5분/1회 열람할 수 있습니다.</p>{available.map(field => { const sensitive = !["CUSTOMER_DISPLAY_NAME", "STORE_LEGAL_DISPLAY_NAME", "COURIER_DISPLAY_NAME"].includes(field); return <Checkbox key={field} label={personalFieldLabels[field]} description={sensitive ? "강화 본인확인과 별도 승인 필요" : "기본 본인확인 필요"} checked={fields.includes(field)} disabled={sensitive && session.achievedLevel !== "ENHANCED"} onCheckedChange={checked => setFields(values => checked ? [...values, field] : values.filter(value => value !== field))} />; })}<SelectField label="열람 요청 사유" value={reason} onValueChange={value => setReason(value as Reason)}>{Object.entries(dataAccessReasonLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField><Button type="submit" loading={command.busy} disabled={!fields.length}>선택한 정보 열람 요청</Button></fieldset></form> : !initialGrantId ? <InlineNotice title="개인정보 열람 목적의 본인확인이 필요합니다" description="열람할 대상과 목적에 맞는 인증을 먼저 완료해 주세요. 상담 조치용 인증으로는 열람을 요청할 수 없습니다." /> : null}
    {command.failure ? <ErrorState error={command.failure} /> : null}{command.pending ? <InlineNotice tone="warning" title="열람 요청 응답을 확인하지 못했습니다" description="같은 대상과 필드로 요청 결과를 확인해 주세요." action={<Button disabled={command.busy} onClick={() => void command.retry()}>같은 열람 요청 확인</Button>} /> : null}
    <SupportWorkPicker kind="DATA_ACCESS" caseId={session?.caseId} disabled={locked} onSelect={item => { setGrantId(item.requestId); setOpened(n => n + 1); }} />
    {grantId ? <GrantInspection key={`${grantId}:${opened}`} grantId={grantId} onBusyChange={setInspectionLocked} /> : null}
  </section>;
}

function GrantInspection({ grantId, onBusyChange }: { grantId: string; onBusyChange: (value: boolean) => void }) {
  const [reason, setReason] = useState<Exclude<Reason, "CONTACT_CONFIRMATION">>("CASE_HANDLING");
  const [notice, setNotice] = useState("");
  const [raw, setRaw] = useState<Reveal | null>(null);
  const [fields, setFields] = useState<Field[] | null>(null);
  const [revealBusy, setRevealBusy] = useState(false);
  const [revealError, setRevealError] = useState<unknown>(null);
  const [uncertain, setUncertain] = useState(false);
  const inFlight = useRef(false);
  const mounted = useRef(true);
  const generation = useRef(0);
  const [, tick] = useState(0);
  const read = useResource(useCallback(() => operationsApi.GET("/support/data-access-grants/{grantId}", { params: { path: { grantId } } }).then(unwrap), [grantId]));
  const current = read.state.status === "ready" ? read.state.value : null;
  const grant = current?.grant;
  const expired = grant?.expiresAt ? Date.parse(grant.expiresAt) <= Date.now() : false;
  const command = useSupportCommand(`data-access-approval:${grantId}`, () => read.reload());
  const busy = command.busy || revealBusy;
  const blocked = busy || command.pending;
  useEffect(() => { onBusyChange(blocked || uncertain || Boolean(raw)); return () => onBusyChange(false); }, [blocked, uncertain, raw, onBusyChange]);
  const selected = fields ?? grant?.fields ?? [];
  const clear = useCallback(() => { generation.current++; setRaw(null); }, []);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; }; }, []);
  useEffect(() => { if (read.state.status === "failed") clear(); }, [read.state.status, clear]);
  useEffect(() => {
    let away = document.hidden;
    const leave = (event: Event) => { if (event.type === "blur" && event.target instanceof Element) return; away = true; clear(); };
    const resume = (event: Event) => { if (event.target instanceof Element || !away) return; away = false; clear(); read.reload(); };
    const visibility = (event: Event) => { if (document.hidden) leave(event); else if (away) { away = false; clear(); read.reload(); } };
    window.addEventListener("blur", leave); window.addEventListener("focus", resume); document.addEventListener("visibilitychange", visibility);
    return () => { window.removeEventListener("blur", leave); window.removeEventListener("focus", resume); document.removeEventListener("visibilitychange", visibility); };
  }, [clear, read.reload]);
  useEffect(() => {
    if (!grant?.expiresAt) return;
    if (expired) { clear(); return; }
    const timer = window.setTimeout(() => { clear(); tick(n => n + 1); }, Math.min(Date.parse(grant.expiresAt) - Date.now() + 1, 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [grant?.expiresAt, expired, clear]);
  useEffect(() => {
    if (!raw) return;
    const timer = window.setTimeout(clear, 60_000);
    const permissionCheck = window.setInterval(read.refresh, 15_000);
    return () => { window.clearTimeout(timer); window.clearInterval(permissionCheck); };
  }, [raw, clear, read.refresh]);
  function decide(decision: "APPROVE" | "DENY") {
    if (blocked || !grant || grant.state !== "APPROVAL_PENDING" || current?.viewerRole !== "APPROVER") return;
    const body = { decision, expectedVersion: grant.version, reasonCode: reason };
    command.submit(JSON.stringify({ grantId, body }), key => operationsApi.POST("/support/data-access-grants/{grantId}/approvals", { params: { path: { grantId }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => setNotice(decision === "APPROVE" ? "열람 요청을 승인했습니다" : "열람 요청을 거부했습니다"));
  }
  async function reveal() {
    if (blocked || inFlight.current || uncertain || !grant || current?.viewerRole !== "REQUESTER" || grant.state !== "ACTIVE" || expired || !selected.length || !selected.every(field => grant.fields.includes(field))) return;
    clear(); const token = generation.current; inFlight.current = true; setRevealBusy(true); setRevealError(null);
    try {
      const response = unwrap(await operationsApi.POST("/support/data-access-grants/{grantId}/reveals", { params: { path: { grantId }, header: { "Idempotency-Key": new SubmissionIntent().keyFor(grantId) } }, body: { fields: selected } }));
      if (token === generation.current && !document.hidden && grant.expiresAt && Date.now() < Date.parse(grant.expiresAt)) setRaw(response);
    } catch (error) { if (mounted.current) { setRevealError(error); setUncertain(true); } }
    finally { inFlight.current = false; if (mounted.current) { setRevealBusy(false); read.refresh(); } }
  }
  return <div className="management-workspace">{notice ? <p role="status">{notice}</p> : null}{command.failure ? <ErrorState error={command.failure} /> : null}{command.pending ? <InlineNotice tone="warning" title="열람 판정 응답을 확인하지 못했습니다" description="기존 요청과 같은 판정의 결과를 확인해 주세요." action={<Button disabled={busy} onClick={() => void command.retry()}>같은 열람 판정 확인</Button>} /> : null}{revealError ? <ErrorState error={revealError} /> : null}{uncertain ? <InlineNotice tone="warning" title="원문 열람 응답을 확인하지 못했습니다" description="열람 횟수가 이미 사용되었을 수 있습니다. 원문 응답은 재생되지 않으므로 현재 상태와 감사 이력을 확인해 주세요." /> : null}
    {read.state.status === "loading" ? <LoadingState label="열람 요청과 현재 권한을 확인하는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : current && grant ? <article className="surface-card management-card management-workspace"><h3>현재 열람 요청</h3><p className="support-case-reference">열람 요청 ID {grant.grantId}</p><StatusText domain="grant" state={expired ? "EXPIRED" : grant.state} label={grantLabels[expired ? "EXPIRED" : grant.state]} /><dl className="detail-list"><div><dt>상담</dt><dd><ButtonLink variant="ghost" to={`/support/cases/${encodeURIComponent(grant.caseId)}`}>상담 건 열기</ButtonLink></dd></div><div><dt>대상</dt><dd className="support-case-reference">{grant.subjectId}</dd></div><div><dt>목적</dt><dd>{verificationPurposeLabels[grant.purpose]}</dd></div><div><dt>필드 범위</dt><dd>{grant.fields.map(field => personalFieldLabels[field]).join(", ")}</dd></div><div><dt>사용 횟수</dt><dd>{grant.reservedReveals}/{grant.maxReveals}</dd></div><div><dt>요청</dt><dd>{fullDateTime.format(new Date(grant.requestedAt))}</dd></div><div><dt>만료</dt><dd>{grant.expiresAt ? fullDateTime.format(new Date(grant.expiresAt)) : "승인 후 시작"}</dd></div></dl>
      {expired ? <InlineNotice title="열람 기한이 지났습니다" description="필요하면 현재 상담과 본인확인을 다시 확인하고 새 요청을 만들어 주세요." /> : grant.state === "APPROVAL_PENDING" ? current.viewerRole === "REQUESTER" ? <InlineNotice title="다른 권한 있는 담당자의 승인이 필요합니다" description="검토 주소를 통해 요청 범위와 현재 상태를 확인할 수 있습니다." action={<ButtonLink variant="secondary" to={`/support/data-access/${encodeURIComponent(grantId)}`}>열람 요청 검토 주소</ButtonLink>} /> : <section className="management-workspace"><h4>별도 담당자의 열람 판정</h4><SelectField label="열람 판정 사유" value={reason} onValueChange={value => setReason(value as typeof reason)} disabled={blocked}>{Object.entries(dataAccessReasonLabels).filter(([value]) => value !== "CONTACT_CONFIRMATION").map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField><div className="button-row"><Button disabled={blocked} onClick={() => decide("APPROVE")}>열람 승인</Button><Button variant="secondary" disabled={blocked} onClick={() => decide("DENY")}>열람 거부</Button></div></section> : null}
      {grant.state === "ACTIVE" && !expired && current.viewerRole === "REQUESTER" && !uncertain ? <fieldset className="catalog-fieldset" disabled={blocked || read.refreshing}><legend>이번에 열람할 정보</legend>{grant.fields.map(field => <Checkbox key={field} label={personalFieldLabels[field]} checked={selected.includes(field)} onCheckedChange={checked => { clear(); setFields(checked ? [...selected, field] : selected.filter(value => value !== field)); }} />)}<Button loading={revealBusy} disabled={!selected.length} onClick={() => void reveal()}>선택한 정보 한시 열람</Button></fieldset> : null}
      <Button variant="ghost" disabled={blocked} onClick={() => { clear(); read.reload(); }}>열람 요청 상태 새로고침</Button>
    </article> : null}
    {raw && current && !expired ? <section className="support-reveal" aria-label="한시 열람 정보"><h3>최대 60초 뒤 자동으로 숨겨지는 정보</h3>{Object.entries(raw.values).map(([field, value]) => <p key={field}>{personalFieldLabels[field as Field]} <strong>{value}</strong></p>)}<Button variant="secondary" onClick={clear}>지금 지우기</Button></section> : null}
  </div>;
}

/** Shareable approval handoff: URL contains the request identifier only. */
export function SupportDataAccessPage() {
  const { grantId = "" } = useParams();
  return <div className="management-workspace"><PageHeading title="개인정보 열람 요청 검토" action={<ButtonLink variant="secondary" to="/support/cases">상담 목록</ButtonLink>} /><SupportDataAccessWorkspace key={grantId} initialGrantId={grantId} /></div>;
}
