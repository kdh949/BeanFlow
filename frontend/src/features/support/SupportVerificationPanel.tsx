import { supportSubjectLabel, isSupportSubjectSelectable, type SupportSubjectDisplaySource } from "./supportCaseLabels";
import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, InlineNotice, LoadingState, SelectField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { useSupportCommand } from "./useSupportCommand";
import { verificationChannelLabels, verificationPurposeLabels } from "./supportSecurityLabels";
type Session = components["schemas"]["VerificationSessionResource"];
type Channel = components["schemas"]["VerificationChannel"];
const challengeLabels: Record<components["schemas"]["VerificationChallengeState"], string> = { PENDING_ISSUE: "발급 접수", ISSUED: "인증 입력 대기", ISSUE_UNKNOWN: "발급 결과 확인 중", VERIFYING: "인증 확인 중", VERIFIED: "인증 수단 확인됨", INVALID: "인증 불일치", VERIFICATION_UNKNOWN: "인증 결과 확인 중", EXPIRED: "기한 만료", REVOKED: "철회됨" };
/** Purpose-bound verification; proofs are transient and never retained for command retries. */
export function SupportVerificationPanel({ caseId, links, disabled, onChange, initialActionScope = "PERSONAL_DATA_REVEAL" }: { initialActionScope?: Session["actionScope"]; caseId: string; links: components["schemas"]["SupportSubjectLink"][]; disabled: boolean; onChange: (session: Session | null) => void }) {
  const eligible = links.filter(link => link.subjectType !== "ORDER");
  const [linkId, setLinkId] = useState("");
  const [level, setLevel] = useState<"BASIC" | "ENHANCED">("ENHANCED");
  const [scope, setScope] = useState<Session["actionScope"]>(initialActionScope);
  const [purpose, setPurpose] = useState<Session["purpose"]>("CONTACT_CONFIRMATION");
  const [lookup, setLookup] = useState("");
  const [request, setRequest] = useState<{ id: string } | null>(null);
  const [channel, setChannel] = useState<Channel>("REGISTERED_PHONE");
  const [proof, setProof] = useState("");
  const [proofBusy, setProofBusy] = useState(false);
  const [proofError, setProofError] = useState<unknown>(null);
  const proofInFlight = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const [clockRevision, tick] = useState(0);
  const read = useResource(useCallback(async () => {
    if (!request || disabled) return null;
    const result = unwrap(await operationsApi.GET("/support/verification-sessions/{sessionId}", { params: { path: { sessionId: request.id } } }));
    if (result.caseId !== caseId || !links.some(link => link.linkId === result.subjectLinkId && link.subjectId === result.subjectId)) throw new ApiRequestError(409, "ORDER_STATE_CONFLICT", "본인확인 대상이 현재 상담과 다릅니다", "VERIFICATION_BINDING");
    return result;
  }, [request, disabled, caseId, links]));
  const current = read.state.status === "ready" ? read.state.value : null;
  const expired = current ? Date.now() >= Date.parse(current.expiresAt) : false;
  const command = useSupportCommand(`verification:${caseId}`, () => { setProof(""); if (request) read.reload(); });
  const busy = command.busy || proofBusy;
  const blocked = busy || command.pending || disabled;
  useEffect(() => { onChange(current && !expired && !disabled ? current : null); }, [current, expired, disabled, onChange]);
  useEffect(() => {
    if (!current || expired) return;
    const boundaries = [current.expiresAt, ...current.challenges.filter(c => c.state === "ISSUED").map(c => c.expiresAt)].map(Date.parse).filter(date => date > Date.now());
    if (!boundaries.length) return;
    const timer = window.setTimeout(() => { setProof(""); tick(n => n + 1); }, Math.min(Math.min(...boundaries) - Date.now() + 1, 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [current, expired, clockRevision]);
  useEffect(() => {
    const clear = (event: Event) => { if (event.type === "focus" && event.target instanceof Element) return; setProof(""); if (request && !proofInFlight.current) read.reload(); };
    window.addEventListener("focus", clear); document.addEventListener("visibilitychange", clear);
    return () => { window.removeEventListener("focus", clear); document.removeEventListener("visibilitychange", clear); };
  }, [request, read.reload]);
  function create() {
    if (blocked || !eligible.some(link => link.linkId === linkId && isSupportSubjectSelectable(link))) return;
    const body = { subjectLinkId: linkId, requestedLevel: level, purpose: scope === "SUPPORT_ACTION" ? "CASE_RESOLUTION" as const : purpose, actionScope: scope };
    command.submit(JSON.stringify({ caseId, body }), async key => { const result = unwrap(await operationsApi.POST("/support/cases/{caseId}/verification-sessions", { params: { path: { caseId }, header: { "Idempotency-Key": key } }, body })); setLookup(result.sessionId); setRequest({ id: result.sessionId }); }, () => {});
  }
  const pending = current?.state === "PENDING" && !expired;
  const issued = pending ? current.challenges.find(c => c.state === "ISSUED" && Date.parse(c.expiresAt) > Date.now()) : null;
  const unavailableChannels = new Set(current?.challenges.filter(c => !["EXPIRED", "INVALID", "REVOKED"].includes(c.state) && !(c.state === "ISSUED" && Date.parse(c.expiresAt) <= Date.now())).map(c => c.channel));
  function issue() {
    if (blocked || !current || !pending || unavailableChannels.has(channel)) return;
    const body = { channel }; const sessionId = current.sessionId;
    command.submit(JSON.stringify({ sessionId, body }), key => operationsApi.POST("/support/verification-sessions/{sessionId}/challenges", { params: { path: { sessionId }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => {});
  }
  async function verify() {
    if (blocked || proofInFlight.current || !issued || !proof.trim()) return;
    proofInFlight.current = true; setProofBusy(true); setProofError(null);
    const body = { proof: proof.trim() }; setProof("");
    try { unwrap(await operationsApi.POST("/support/verification-challenges/{challengeId}/verifications", { params: { path: { challengeId: issued.challengeId }, header: { "Idempotency-Key": new SubmissionIntent().keyFor(issued.challengeId) } }, body })); }
    catch (error) { if (mounted.current) setProofError(error); }
    finally { body.proof = ""; proofInFlight.current = false; if (mounted.current) { setProofBusy(false); read.reload(); } }
  }
  function revoke() {
    if (blocked || !current) return;
    const sessionId = current.sessionId;
    command.submit(`revoke:${sessionId}`, key => operationsApi.POST("/support/verification-sessions/{sessionId}/revocations", { params: { path: { sessionId }, header: { "Idempotency-Key": key } } }).then(unwrap), () => {});
  }
  if (disabled) return <InlineNotice title="종료된 상담 건에서는 본인확인을 진행할 수 없습니다" description="상담 목록에서 현재 처리 중인 건을 확인해 주세요." />;
  return <section className="management-workspace"><h2>본인확인</h2>
    <InlineNotice title="강화 인증에는 서로 다른 인증 수단 두 가지가 필요합니다" description="이미 등록된 앱·전화·이메일을 사용합니다. 상담 조치는 상담 해결 목적의 별도 인증이 필요합니다." />
    {!eligible.length ? <EmptyState title="본인확인 가능한 대상이 없습니다" description="고객, 매장 또는 배송 대상을 상담 건에 연결해 주세요." /> : <form className="surface-card management-card" onSubmit={e => { e.preventDefault(); create(); }}><fieldset className="catalog-fieldset" disabled={blocked}><legend>새 본인확인</legend>{eligible.some(link => !isSupportSubjectSelectable(link)) ? <p>표시 정보 조회 권한과 등록된 대상 프로필을 확인해 주세요.</p> : null}<SelectField label="본인확인 대상" value={linkId} onValueChange={setLinkId} required><option value="">대상 선택</option>{eligible.map(link => <option key={link.linkId} value={link.linkId} disabled={!isSupportSubjectSelectable(link)}>{supportSubjectLabel(link)}</option>)}</SelectField><SelectField label="인증 사용 업무" value={scope} onValueChange={value => setScope(value as Session["actionScope"])}><option value="PERSONAL_DATA_REVEAL">개인정보 열람</option><option value="SUPPORT_ACTION">상담 조치</option></SelectField><SelectField label="본인확인 목적" value={scope === "SUPPORT_ACTION" ? "CASE_RESOLUTION" : purpose} onValueChange={value => setPurpose(value as Session["purpose"])} disabled={scope === "SUPPORT_ACTION"}>{Object.entries(verificationPurposeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField><SelectField label="요청 인증 수준" value={level} onValueChange={value => setLevel(value as typeof level)}><option value="BASIC">기본 · 인증 수단 1개</option><option value="ENHANCED">강화 · 서로 다른 인증 수단 2개</option></SelectField><Button type="submit" loading={command.busy} disabled={!eligible.some(link => link.linkId === linkId && isSupportSubjectSelectable(link))}>본인확인 시작</Button></fieldset></form>}
    <form className="surface-card management-card" onSubmit={e => { e.preventDefault(); if (!blocked && lookup.trim()) { setProof(""); setProofError(null); setRequest({ id: lookup.trim() }); } }}><TextField label="기존 본인확인 ID" value={lookup} onValueChange={setLookup} disabled={blocked} required /><Button type="submit" variant="secondary" disabled={blocked || !lookup.trim()}>본인확인 현재 상태 조회</Button></form>
    {command.failure ? <ErrorState error={command.failure} /> : null}{command.pending ? <InlineNotice tone="warning" title="본인확인 요청 응답을 확인하지 못했습니다" description="같은 요청으로 결과를 확인해 주세요." action={<Button disabled={busy} onClick={() => void command.retry()}>같은 인증 요청 확인</Button>} /> : null}{proofError ? <ErrorState error={proofError} /> : null}
    {read.state.status === "loading" && request ? <LoadingState label="본인확인 현재 상태를 확인하는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : current ? <article className="surface-card management-card management-workspace"><h3>현재 본인확인</h3><p className="support-case-reference">본인확인 ID {current.sessionId}</p><p>{current.actionScope === "SUPPORT_ACTION" ? "상담 조치" : "개인정보 열람"} · {verificationPurposeLabels[current.purpose]}</p><p className="support-case-reference">대상 {current.subjectId}</p><StatusText state={expired ? "EXPIRED" : current.state} /><p>요청 수준 {current.requestedLevel === "ENHANCED" ? "강화" : "기본"} · 실패 {current.invalidAttempts}/5 · 만료 {fullDateTime.format(new Date(current.expiresAt))}</p>
      {current.state === "LOCKED" ? <InlineNotice tone="warning" title="본인확인이 잠겼습니다" description="실패 횟수 한도에 도달했습니다. 서버가 허용하는 시간 이후 다시 진행해 주세요." /> : current.state === "REVOKED" ? <InlineNotice title="본인확인이 철회되었습니다" description="이 인증으로 새 열람이나 조치를 진행할 수 없습니다." /> : expired || current.state === "EXPIRED" ? <InlineNotice title="본인확인 기한이 지났습니다" description="새 인증을 시작해 주세요." /> : current.state === "VERIFIED" ? <InlineNotice title="본인확인이 완료되었습니다" description="현재 대상과 목적에 한해 요청한 수준의 인증이 완료되었습니다." /> : null}
      {current.challenges.map(c => <div key={c.challengeId}><p>{verificationChannelLabels[c.channel]} · <StatusText state={c.state} label={challengeLabels[c.state]} /></p><p>인증 요청 만료 {fullDateTime.format(new Date(c.expiresAt))}</p>{["ISSUE_UNKNOWN", "VERIFICATION_UNKNOWN", "VERIFYING", "PENDING_ISSUE"].includes(c.state) ? <InlineNotice tone="warning" title="인증 결과를 확인하고 있습니다" description="현재 상태를 다시 조회해 주세요. 결과를 확인하기 전에는 이 인증을 완료로 처리하지 않습니다." /> : null}</div>)}
      {pending ? <><SelectField label="인증 수단" value={channel} onValueChange={value => setChannel(value as Channel)} disabled={blocked}>{Object.entries(verificationChannelLabels).map(([value, label]) => <option key={value} value={value} disabled={unavailableChannels.has(value as Channel)}>{label}</option>)}</SelectField><Button variant="secondary" disabled={blocked || unavailableChannels.has(channel)} onClick={issue}>인증 요청 발급</Button></> : null}
      {issued ? <form onSubmit={e => { e.preventDefault(); void verify(); }}><TextField label="일회성 인증 코드" type="password" autoComplete="one-time-code" value={proof} onValueChange={setProof} maxLength={512} disabled={blocked} required /><Button type="submit" loading={proofBusy} disabled={blocked || !proof.trim()}>인증 코드 확인</Button></form> : null}
      <div className="button-row"><Button variant="ghost" disabled={blocked} onClick={() => { setProof(""); read.reload(); }}>본인확인 새로고침</Button>{!expired && ["PENDING", "VERIFIED"].includes(current.state) ? <Button variant="secondary" disabled={blocked} onClick={revoke}>본인확인 철회</Button> : null}</div>
    </article> : null}
  </section>;
}
