import { supportSubjectLabel, isSupportSubjectSelectable, type SupportSubjectDisplaySource } from "./supportCaseLabels";
import { OperatorTargetPicker, type OperatorSelection } from "../operations/OperatorTargetPicker";
import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { ApiRequestError, unwrap } from "../../api/client";
import { Button, ButtonLink, Checkbox, EmptyState, InlineNotice, LoadingState, PageHeading, SelectField, TextAreaField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { profileDigest, profilePurposes, validProfileValues, type ProfilePurpose, type ProfileValues } from "../../lib/supportProfilePayload";
import { supportDigest } from "../../lib/supportOrderPayload";
import { useResource } from "../shared/useResource";
import { useExpired } from "./useSupportExpiry";
import { useSupportCommand } from "./useSupportCommand";
import { useSensitiveSupportCommand } from "./useSensitiveSupportCommand";
import { executeProfile, reviseProfile, submitProfile } from "./supportProfileCommands";
type Case = { caseId: string; state: string; subjectLinks: readonly (SupportSubjectDisplaySource & { linkId: string; subjectId: string; subjectType: string })[] };
type Verification = { sessionId: string; subjectLinkId: string; state: string; subjectType: string; subjectId: string; purpose: string; actionScope: string; achievedLevel: string; expiresAt: string };
type Workflow = components["schemas"]["SupportProfileWorkflowResource"];
const approvalLabels: Record<string, string> = { AWAITING_SUPPORT_MANAGER: "상담 관리자 승인 대기", AWAITING_OPERATIONS: "운영 검토 대기", READY_FOR_EXECUTION: "실행 준비", REASSIGNMENT_REQUIRED: "담당자 재배정 필요", REVISION_REQUIRED: "정정안 수정 필요", DENIED: "반려", EXPIRED: "만료", STALE: "조건 변경", MANUAL_REVIEW: "수동 확인 필요", EXECUTED: "실행 완료" };
const notificationLabels: Record<string, string> = { NOT_REQUESTED: "알림 대상 없음", PENDING: "알림 대기", PROCESSING: "알림 접수 처리 중", ACCEPTED: "알림 접수 완료", RETRY_SCHEDULED: "알림 재시도 예정", MANUAL_REVIEW: "알림 수동 확인 필요" };
const uuid = (value: string) => /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value.trim());

/** Purpose-specific fields use only transient component state; leaving the window or waiting clears raw input. */
export function useProfileValues(scope: string) {
  const [values, setValues] = useState<ProfileValues>({});
  const clear = useCallback(() => setValues({}), []);
  useEffect(clear, [scope, clear]);
  useEffect(() => {
    const leave = (event: Event) => { if (event.type === "blur" && event.target instanceof Element) return; if (event.type !== "visibilitychange" || document.hidden) clear(); };
    window.addEventListener("blur", leave); document.addEventListener("visibilitychange", leave);
    return () => { window.removeEventListener("blur", leave); document.removeEventListener("visibilitychange", leave); };
  }, [clear]);
  useEffect(() => { if (!Object.values(values).some(Boolean)) return; const timer = window.setTimeout(clear, 60_000); return () => window.clearTimeout(timer); }, [values, clear]);
  return { values, setValues, clear };
}

export function ProfileFields({ purpose, values, onChange, disabled }: { purpose: ProfilePurpose; values: ProfileValues; onChange: (values: ProfileValues) => void; disabled: boolean }) {
  const descriptor = profilePurposes[purpose];
  return <div className="operation-form">
    {descriptor.risk === "R4" ? <InlineNotice title="인증정보 재등록 요청" description="비밀번호나 인증 토큰을 입력하거나 조회하지 않습니다. 승인 후 기존 소유 서비스에 재등록 의도를 전달합니다." /> : <p>입력값은 제출 후 또는 화면을 벗어나면 지워집니다. 승인 후 실행할 때 같은 값을 다시 입력합니다.</p>}
    {descriptor.fields.some(field => field.optional) ? <p>값을 입력한 항목만 변경합니다. 빈 항목은 기존 값을 유지합니다.</p> : null}
    {descriptor.fields.map(field => <TextField key={`${purpose}:${field.key}`} label={field.label} value={values[field.key] ?? ""} onValueChange={value => onChange({ ...values, [field.key]: value })} maxLength={field.maxLength} required={!field.optional} disabled={disabled} autoComplete="off" type={field.key === "email" ? "email" : field.key.toLowerCase().includes("phone") ? "tel" : "text"} description={field.key.endsWith("Reference") ? "등록된 불투명 참조값만 입력합니다. 실제 계좌번호·카드정보·서비스 비밀키는 입력하지 않습니다." : undefined} />)}
  </div>;
}

function SensitiveResult({ command }: { command: ReturnType<typeof useSensitiveSupportCommand> }) {
  return <>{command.mismatch ? <InlineNotice tone="warning" title="처음 제출한 내용과 일치하지 않습니다" description="같은 요청을 확인하려면 처음 입력한 값을 다시 입력해 주세요." /> : command.failure ? <ErrorState error={command.failure} /> : null}{command.pending ? <InlineNotice tone="warning" title="정정 요청 결과를 확인하지 못했습니다" description="다른 명령을 잠갔습니다. 원문은 보관하지 않으므로 같은 값을 다시 입력해 같은 요청의 결과를 확인해 주세요." /> : null}</>;
}

/** Composes current profile metadata, typed changes, independent approval and notification follow-up. */
export function SupportProfileChangeWorkspace({ supportCase, verification, initialProfileChangeId, onBusyChange }: { supportCase?: Case; verification?: Verification | null; initialProfileChangeId?: string; onBusyChange?: (busy: boolean) => void }) {
  const [id, setId] = useState(initialProfileChangeId ?? ""), [lookup, setLookup] = useState(initialProfileChangeId ?? ""), [active, setActive] = useState(false);
  useEffect(() => { onBusyChange?.(active); return () => onBusyChange?.(false); }, [active, onBusyChange]);
  return <section className="management-workspace" aria-label="정보 정정 업무"><h2>정보 정정</h2>
    <form className="surface-card management-card operation-form" onSubmit={event => { event.preventDefault(); if (!active && uuid(lookup)) setId(lookup.trim()); }}><TextField label="기존 정보 정정 ID" value={lookup} onValueChange={setLookup} disabled={active} required /><Button type="submit" variant="secondary" disabled={active || !uuid(lookup)}>정보 정정 건 열기</Button>{id && supportCase ? <Button variant="ghost" disabled={active} onClick={() => { setId(""); setLookup(""); }}>새 정보 정정</Button> : null}</form>
    {id ? <ProfileInspection key={id} id={id} supportCase={supportCase} verification={verification} onBusyChange={setActive} /> : supportCase ? <CreateProfileChange supportCase={supportCase} verification={verification} onBusyChange={setActive} onCreated={created => { setId(created); setLookup(created); }} /> : <EmptyState title="상담 건에서 정정을 시작해 주세요" description="기존 정정 건은 ID로 열어 승인과 변경 상태를 확인할 수 있습니다." />}
  </section>;
}

function CreateProfileChange({ supportCase, verification, onCreated, onBusyChange, revision }: { supportCase: Case; verification?: Verification | null; onCreated: (id: string) => void; onBusyChange: (busy: boolean) => void; revision?: Workflow }) {
  const targets = supportCase.subjectLinks.filter(link => ["CUSTOMER", "STORE", "DELIVERY"].includes(link.subjectType));
  const [linkId, setLinkId] = useState((revision ? targets.find(link => link.subjectId === revision.profileChange.subjectId && isSupportSubjectSelectable(link))?.linkId : targets.find(isSupportSubjectSelectable)?.linkId) ?? "");
  const link = targets.find(item => item.linkId === linkId && isSupportSubjectSelectable(item));
  const purposes = (Object.keys(profilePurposes) as ProfilePurpose[]).filter(purpose => profilePurposes[purpose].subject === link?.subjectType);
  const [selectedPurpose, setPurpose] = useState<ProfilePurpose>(revision?.profileChange.purpose ?? purposes[0] ?? "CUSTOMER_DISPLAY_NAME");
  const purpose = purposes.includes(selectedPurpose) ? selectedPurpose : purposes[0] ?? "CUSTOMER_DISPLAY_NAME";
  const descriptor = profilePurposes[purpose];
  const raw = useProfileValues(`${linkId}:${purpose}:${verification?.sessionId}`);
  const [reason, setReason] = useState(""), [evidence, setEvidence] = useState(""), [resetAcknowledged, setResetAcknowledged] = useState(false), [preparing, setPreparing] = useState(false), [error, setError] = useState<unknown>(null);
  const preparingRef = useRef(false);
  const context = useResource(useCallback(async () => link ? unwrap(await operationsApi.GET("/support/cases/{caseId}/profile-contexts/{linkId}", { params: { path: { caseId: supportCase.caseId, linkId }, query: { purpose } } })) : null, [supportCase.caseId, linkId, link, purpose]));
  const command = useSensitiveSupportCommand();
  const expired = useExpired(verification?.expiresAt), busy = preparing || command.busy, frozen = busy || !!command.pending;
  useEffect(() => { onBusyChange(frozen); return () => onBusyChange(false); }, [frozen, onBusyChange]);
  const current = context.state.status === "ready" ? context.state.value : null;
  const verified = verification?.state === "VERIFIED" && verification.subjectLinkId === linkId && verification.subjectId === link?.subjectId && verification.subjectType === link?.subjectType && verification.purpose === "CASE_RESOLUTION" && verification.actionScope === "SUPPORT_ACTION" && !expired;
  const enough = current?.requiredVerificationLevel === "BASIC" ? ["BASIC", "ENHANCED"].includes(verification?.achievedLevel ?? "") : verification?.achievedLevel === "ENHANCED";
  const valid = validProfileValues(purpose, raw.values) && !!reason.trim() && !!evidence.trim() && (descriptor.risk !== "R4" || resetAcknowledged);
  const eligible = link && current && verified && enough && !["RESOLVED", "CLOSED"].includes(supportCase.state);
  async function submit() {
    if (busy || preparingRef.current || !current || !verification || !valid || (!command.pending && !eligible)) return;
    preparingRef.current = true; setPreparing(true); setError(null);
    const values = { ...raw.values };
    try {
      const digest = await profileDigest(current.subjectId, current.currentProfileVersion, purpose, values), evidenceDigest = await supportDigest(evidence.trim());
      const common = { expectedProfileVersion: current.currentProfileVersion, verificationSessionId: verification.sessionId, reason: reason.trim(), evidenceDigest };
      if (revision?.approval) {
        const binding = { ...common, expectedProfileChangeVersion: revision.profileChange.version, expectedActionRequestVersion: revision.approval.requestVersion };
        const fingerprint = await supportDigest(JSON.stringify({ operation: "revise", id: revision.profileChange.profileChangeId, purpose, digest, binding }));
        await command.submit(fingerprint, key => reviseProfile(purpose, revision.profileChange.profileChangeId, binding, values, key), result => onCreated(result.profileChangeId));
      } else {
        const binding = { ...common, subjectId: current.subjectId };
        const fingerprint = await supportDigest(JSON.stringify({ operation: "submit", caseId: supportCase.caseId, purpose, digest, binding }));
        await command.submit(fingerprint, key => submitProfile(purpose, supportCase.caseId, binding, values, key), result => onCreated(result.profileChangeId));
      }
    } catch (failure) { setError(failure); } finally { raw.clear(); preparingRef.current = false; setPreparing(false); }
  }
  return <div className="surface-card management-card management-workspace"><h3>{revision ? "정정안 수정" : "새 정보 정정"}</h3>
    {targets.some(link => !isSupportSubjectSelectable(link)) ? <p>표시 정보 조회 권한과 등록된 대상 프로필을 확인해 주세요.</p> : null}<SelectField label="정보 정정 대상" value={link?.linkId ?? ""} disabled={frozen || !!revision} onValueChange={setLinkId}><option value="">표시 정보를 확인한 대상 선택</option>{targets.map(target => <option key={target.linkId} value={target.linkId} disabled={!isSupportSubjectSelectable(target)}>{supportSubjectLabel(target)}</option>)}</SelectField>
    <SelectField label="정보 정정 목적" value={purpose} disabled={frozen || !!revision} onValueChange={value => setPurpose(value as ProfilePurpose)}>{purposes.map(item => <option key={item} value={item}>{profilePurposes[item].label}</option>)}</SelectField>
    {!targets.length ? <EmptyState title="정정할 대상이 없습니다" description="상담에 고객·매장·외부 배달원을 연결해 주세요." /> : context.state.status === "loading" ? <LoadingState label="현재 프로필 조건을 읽는 중" /> : context.state.status === "failed" ? <ErrorState error={context.state.error} retry={context.reload} /> : current ? <p>현재 버전 {current.currentProfileVersion} · {current.requiredVerificationLevel === "ENHANCED" ? "강화" : "기본"} 본인확인 필요 · {descriptor.risk === "R3" || descriptor.risk === "R4" ? "상담 관리자와 운영 순차 승인" : "권한 확인 후 직접 정정"}</p> : null}
    {current && !enough && current.requiredVerificationLevel === "ENHANCED" ? <InlineNotice tone="info" title="이 정정에는 강화 본인확인이 필요합니다" description="같은 대상의 상담 해결 목적 본인확인을 완료해 주세요." /> : !verified ? <InlineNotice title="정정 대상의 업무 처리 본인확인이 필요합니다" description="상담 해결 목적·업무 처리 범위의 인증 세션을 선택해 주세요." /> : null}
    <Button variant="secondary" disabled={frozen || !link} onClick={() => { raw.clear(); context.reload(); }}>현재 프로필 조건 다시 확인</Button>
    <ProfileFields purpose={purpose} values={raw.values} onChange={raw.setValues} disabled={busy || (!current && !command.pending)} />
    {descriptor.risk === "R4" ? <Checkbox label="인증정보를 직접 변경하지 않는 재등록 요청임을 확인했습니다" checked={resetAcknowledged} onCheckedChange={setResetAcknowledged} disabled={frozen} /> : null}
    <TextAreaField label="정정 사유" value={reason} onValueChange={setReason} disabled={frozen} maxLength={500} required description="개인정보나 인증 원문을 적지 않습니다." /><TextField label="정정 증빙 참조" value={evidence} onValueChange={setEvidence} disabled={frozen} maxLength={500} required description="확인한 상담 기록의 참조입니다. 해시로 전송합니다." />
    <SensitiveResult command={command} />{error ? <ErrorState error={error} /> : null}
    <Button disabled={busy || !valid || (!command.pending && !eligible)} onClick={() => void submit()}>{command.pending ? "같은 내용으로 정정 결과 확인" : revision ? "수정한 정정안 제출" : descriptor.risk === "R3" || descriptor.risk === "R4" ? "승인용 정정 요청 등록" : "확인한 정보 정정"}</Button>
  </div>;
}

function ProfileInspection({ id, supportCase, verification, onBusyChange }: { id: string; supportCase?: Case; verification?: Verification | null; onBusyChange: (busy: boolean) => void }) {
  const read = useResource(useCallback(async () => { const workflow = unwrap(await operationsApi.GET("/support/profile-changes/{profileChangeId}/workflow", { params: { path: { profileChangeId: id } } })); if (supportCase && workflow.profileChange.caseId !== supportCase.caseId) throw new ApiRequestError(409, "RESOURCE_STATE_CONFLICT", "상담에 연결된 정정 건이 아닙니다"); return workflow; }, [id, supportCase?.caseId]));
  const value = read.state.status === "ready" ? read.state.value : null, profile = value?.profileChange, approval = value?.approval;
  const raw = useProfileValues(`${profile?.purpose}:${profile?.version}:${read.state.status}`);
  const [digest, setDigest] = useState(""), [decision, setDecision] = useState<"APPROVE" | "DENY" | "RETURN_FOR_REVISION">("APPROVE"), [reason, setReason] = useState(""), [message, setMessage] = useState(""), [assignee, setAssignee] = useState<OperatorSelection | null>(null), [assignmentReason, setAssignmentReason] = useState(""), [revising, setRevising] = useState(false), [revisionBusy, setRevisionBusy] = useState(false);
  const sensitive = useSensitiveSupportCommand(), command = useSupportCommand(`profile-change:${id}`, () => { raw.clear(); read.reload(); });
  const busy = sensitive.busy || command.busy, frozen = busy || !!sensitive.pending || command.pending || revisionBusy;
  useEffect(() => { onBusyChange(frozen); return () => onBusyChange(false); }, [frozen, onBusyChange]);
  const expired = useExpired(value?.verificationExpiresAt);
  const allowed = (action: Workflow["allowedActions"][number]) => !!value?.allowedActions.includes(action) && ((action === "RETRY_NOTIFICATION" || action === "REVISE") || !expired);
  useEffect(() => { let live = true; setDigest(""); if (profile && validProfileValues(profile.purpose, raw.values)) void profileDigest(profile.subjectId, profile.expectedProfileVersion, profile.purpose, raw.values).then(hash => { if (live) setDigest(hash); }); return () => { live = false; }; }, [profile?.subjectId, profile?.purpose, profile?.expectedProfileVersion, raw.values]);
  const matches = !!digest && digest === profile?.payloadDigest;
  async function execute() {
    if (!profile || !approval || !value || busy || (!sensitive.pending && (!allowed("EXECUTE") || !matches)) || !validProfileValues(profile.purpose, raw.values)) return;
    const binding = { revisionNumber: approval.revisionNumber, expectedActionRequestVersion: approval.requestVersion, expectedProfileChangeVersion: profile.version, expectedProfileVersion: profile.expectedProfileVersion }, values = { ...raw.values };
    try {
      const fingerprint = await supportDigest(JSON.stringify({ operation: "execute", id, binding, digest: await profileDigest(profile.subjectId, profile.expectedProfileVersion, profile.purpose, values) }));
      await sensitive.submit(fingerprint, key => executeProfile(profile.purpose, id, binding, values, key), () => { setMessage("정보 변경 요청을 처리했습니다"); read.reload(); });
    } finally { raw.clear(); }
  }
  function decide() {
    if (!approval || !allowed("DECIDE_SUPPORT_MANAGER") || frozen || !reason.trim() || (decision === "APPROVE" && !matches)) return;
    const body = { revisionNumber: approval.revisionNumber, expectedRequestVersion: approval.requestVersion, decision, reason: reason.trim() }, requestId = approval.requestId;
    command.submit(JSON.stringify(body), key => operationsApi.POST("/support/action-requests/{requestId}/support-manager-decisions", { params: { path: { requestId }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => setMessage("정정 승인 결정을 기록했습니다")); raw.clear();
  }
  function reassign() {
    if (!assignee) return;
    if (!approval || !value || !allowed("REASSIGN") || frozen || !assignee || !assignmentReason.trim()) return;
    const body = { revisionNumber: approval.revisionNumber, expectedRequestVersion: approval.requestVersion, expectedCaseVersion: value.caseVersion, assigneeId: assignee.operatorId, reason: assignmentReason.trim() }, requestId = approval.requestId;
    command.submit(JSON.stringify(body), key => operationsApi.POST("/support/action-requests/{requestId}/reassignments", { params: { path: { requestId }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => setMessage("정정과 상담의 담당자를 변경했습니다"));
  }
  return <div className="surface-card management-card management-workspace"><h3>정정 검토와 실행</h3>
    {message ? <p role="status">{message}</p> : null}<SensitiveResult command={sensitive} />{command.failure ? <ErrorState error={command.failure} /> : null}{command.pending ? <InlineNotice tone="warning" title="승인·후속 요청 결과를 확인하지 못했습니다" description="같은 요청으로 확인할 때까지 다른 명령을 잠급니다." action={<Button loading={command.busy} onClick={() => void command.retry()}>같은 후속 요청 확인</Button>} /> : null}
    {read.state.status === "loading" ? <LoadingState label="현재 정정과 승인 조건을 읽는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : value && profile ? <>
      <p className="support-case-reference">정정 ID {id}</p><p>{profilePurposes[profile.purpose].label} · <StatusText state={profile.state} label={profile.state === "EXECUTED" ? "정보 변경 완료" : "정보 변경 대기"} /></p>
      <dl className="detail-list"><dt>대상 ID</dt><dd className="support-case-reference">{profile.subjectId}</dd><dt>요청 당시 / 현재 버전</dt><dd>{profile.expectedProfileVersion} / {value.currentProfileVersion}</dd><dt>승인 내용 해시</dt><dd className="support-case-reference">{profile.payloadDigest}</dd>{profile.maskedBefore !== null ? <><dt>변경 전</dt><dd>{profile.maskedBefore}</dd><dt>변경 후</dt><dd>{profile.maskedAfter}</dd></> : null}</dl>
      {approval ? <p>승인 단계 · <StatusText state={approval.state} label={approvalLabels[approval.state]} /></p> : null}
      <p>알림 처리 · <StatusText state={profile.notificationState} label={notificationLabels[profile.notificationState]} /></p><p>알림 접수 완료는 고객 전달 완료와 구분합니다.</p>
      {profile.notifications.map((line, index) => <p key={`${line.targetKind}:${line.channel}:${index}`}>{line.targetKind === "OLD" ? "이전" : line.targetKind === "NEW" ? "새" : "현재"} {line.channel === "PHONE" ? "전화" : "이메일"} 채널 · {notificationLabels[line.state]} · 시도 {line.attempts}회 {line.failureCode ? `· 확인 코드 ${line.failureCode}` : ""}</p>)}
      <div className="button-row"><Button variant="secondary" disabled={frozen} onClick={() => { raw.clear(); read.reload(); }}>정정 상태 새로고침</Button><ButtonLink variant="secondary" to={`/support/profile-changes/${id}`}>정정 건 주소</ButtonLink>{approval?.state === "AWAITING_OPERATIONS" ? <ButtonLink variant="secondary" to={`/ops/support-investigations?requestId=${approval.requestId}`}>운영 조사 검토</ButtonLink> : null}</div>
      {(allowed("EXECUTE") || allowed("DECIDE_SUPPORT_MANAGER") || sensitive.pending) && !revising ? <><ProfileFields purpose={profile.purpose} values={raw.values} onChange={raw.setValues} disabled={busy || command.pending} /><p role="status">{matches ? "입력한 내용이 승인안과 일치합니다" : "검토할 값을 다시 입력하면 승인안과 대조합니다"}</p></> : null}
      {(allowed("EXECUTE") || sensitive.pending) && !revising ? <Button disabled={busy || command.pending || (!sensitive.pending && !matches) || !validProfileValues(profile.purpose, raw.values)} onClick={() => void execute()}>{sensitive.pending ? "같은 내용으로 정정 결과 확인" : "승인된 정보 변경 실행"}</Button> : null}
      {allowed("DECIDE_SUPPORT_MANAGER") && !revising ? <form className="operation-form" onSubmit={event => { event.preventDefault(); decide(); }}><SelectField label="정정 승인 결정" value={decision} onValueChange={next => setDecision(next as typeof decision)} disabled={frozen}><option value="APPROVE">승인</option><option value="DENY">반려</option><option value="RETURN_FOR_REVISION">수정 요청</option></SelectField><TextAreaField label="정정 승인 사유" value={reason} onValueChange={setReason} disabled={frozen} maxLength={500} required description="개인정보나 인증 원문은 입력하지 않습니다." /><Button type="submit" disabled={frozen || !reason.trim() || (decision === "APPROVE" && !matches)}>정정 승인 결정 기록</Button></form> : null}
      {allowed("REVISE") && !revising ? supportCase ? <Button variant="secondary" disabled={frozen} onClick={() => { raw.clear(); setRevising(true); }}>정정안 수정</Button> : <ButtonLink variant="secondary" to={`/support/follow-up?caseId=${profile.caseId}&profileChangeId=${id}`}>상담에서 정정안 수정</ButtonLink> : null}
      {revising && supportCase ? <><CreateProfileChange supportCase={supportCase} verification={verification} revision={value} onBusyChange={setRevisionBusy} onCreated={() => { setRevising(false); read.reload(); }} /><Button variant="ghost" disabled={revisionBusy} onClick={() => setRevising(false)}>수정 닫기</Button></> : null}
      {allowed("REASSIGN") && !revising ? <form className="operation-form" onSubmit={event => { event.preventDefault(); reassign(); }}><OperatorTargetPicker label="새 실행 담당자" purpose="PROFILE_CHANGE" value={assignee} onSelect={setAssignee} disabled={frozen} /><TextAreaField label="정정 배정 사유" value={assignmentReason} onValueChange={setAssignmentReason} disabled={frozen} maxLength={500} required /><Button type="submit" disabled={frozen || !assignee || !assignmentReason.trim()}>정정과 상담 함께 재배정</Button></form> : null}
      {allowed("RETRY_NOTIFICATION") ? <Button variant="secondary" disabled={frozen} onClick={() => { const body = { expectedProfileChangeVersion: profile.version }; command.submit(JSON.stringify({ id, body }), key => operationsApi.POST("/support/profile-changes/{profileChangeId}/notification-retries", { params: { path: { profileChangeId: id }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => setMessage("정정 알림을 다시 요청했습니다")); }}>정정 알림 다시 요청</Button> : null}
    </> : null}
  </div>;
}

export function SupportProfileChangePage() { const { profileChangeId } = useParams(); return <div className="console-page"><PageHeading title="정보 정정 검토" /><SupportProfileChangeWorkspace initialProfileChangeId={profileChangeId} /></div>; }
