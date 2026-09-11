import { SupportWorkPicker } from "./SupportWorkPicker";
import { supportSubjectLabel } from "./supportCaseLabels";
import { OperatorTargetPicker, type OperatorSelection } from "../operations/OperatorTargetPicker";
import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { ApiRequestError, unwrap } from "../../api/client";
import { Button, ButtonLink, Checkbox, EmptyState, InlineNotice, LoadingState, PageHeading, SelectField, TextAreaField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { shortDateTime, won } from "../../lib/format";
import { supportDigest } from "../../lib/supportOrderPayload";
import { useResource } from "../shared/useResource";
import { useExpired } from "./useSupportExpiry";
import { useSupportCommand } from "./useSupportCommand";

type Case = { caseId: string; state: string; subjectLinks: readonly { subjectType: string; subjectId: string; relationship: string }[] };
type Verification = { sessionId: string; state: string; subjectType: string; actionScope: string; purpose: string; expiresAt: string };
type Workflow = components["schemas"]["SupportCompensationWorkflowResource"];
type Evaluation = components["schemas"]["SupportCompensationEvaluationResource"];
type Template = components["schemas"]["GoodwillCouponTemplateView"];
type Payload = components["schemas"]["EvaluateSupportCompensationRequest"];
const responsibilityLabels: Record<Payload["responsibility"], string> = { PLATFORM: "플랫폼 부담", STORE: "매장 부담", SHARED: "플랫폼·매장 공동 부담", UNDETERMINED: "비용 책임 미확정" };
const basisLabels: Record<NonNullable<Payload["evidenceBasis"]>, string> = { STORE_CONSENT: "매장 동의", OPERATIONS_FINDING: "운영 조사 결과", CONTRACTUAL_RULE: "계약 규칙" };
const routeLabels: Record<string, string> = { NONE: "추가 승인 없음", SUPPORT_MANAGER: "상담 관리자 승인", OPERATIONS: "운영 조사·승인" };
const bandLabels: Record<string, string> = { LOW: "소액", MEDIUM: "중간 금액", HIGH: "고액", EXCEPTIONAL: "예외 검토" };
const reasonLabels: Record<Evaluation["reasonCodes"][number], string> = { RELATED_ORDER_MISSING: "관련 주문이 필요합니다", AMOUNT_ABOVE_LOW_LIMIT: "소액 보상 한도를 초과합니다", AMOUNT_ABOVE_HIGH_LIMIT: "고액 보상 검토가 필요합니다", AMOUNT_ABOVE_SUPPORTED_LIMIT: "지원되는 보상 한도를 초과합니다", ORDER_RATIO_ABOVE_LOW_LIMIT: "주문 금액 대비 보상 비율 검토가 필요합니다", REPEATED_CUSTOMER_COMPENSATION: "반복 보상 검토가 필요합니다", STORE_COST_RESPONSIBILITY: "매장 비용 부담의 운영 검토가 필요합니다", COST_RESPONSIBILITY_UNDETERMINED: "비용 책임을 먼저 확정해 주세요", DUPLICATE_TERMINAL_INCIDENT: "이미 보상한 사고입니다", INSUFFICIENT_VERIFICATION: "추가 본인확인이 필요합니다", STALE_TARGET_VERSION: "주문 정보가 변경되었습니다" };
const compensationStates: Record<Workflow["request"]["state"], string> = { AWAITING_APPROVAL: "승인 대기", READY_FOR_EXECUTION: "지급 준비", BENEFIT_ISSUED: "혜택 지급", NOTIFICATION_RETRY: "알림 접수 재시도 필요", NOTIFICATION_ACCEPTED: "알림 접수 완료", NOTIFICATION_SKIPPED: "수신 설정에 따라 알림 생략" };
const approvalStates: Record<string, string> = { AWAITING_SUPPORT_MANAGER: "상담 관리자 승인 대기", AWAITING_OPERATIONS: "운영 검토 대기", READY_FOR_EXECUTION: "실행 준비", REASSIGNMENT_REQUIRED: "실행 담당자 재배정 필요", REVISION_REQUIRED: "보상 조건 수정 필요", DENIED: "반려", EXPIRED: "만료", STALE: "요청 조건 변경", MANUAL_REVIEW: "수동 확인 필요", EXECUTED: "실행 완료" };
const uuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.trim());
function percentBps(value: string): number | null {
  if (!/^\d{1,2}(\.\d{1,2})?$/.test(value)) return null;
  const [whole, fraction = ""] = value.split(".");
  const bps = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  return bps > 0 && bps < 10000 ? bps : null;
}

/** Composes policy evaluation, immutable benefit review, separate approval and issuance follow-up. */
export function SupportCompensationWorkspace({ supportCase, verification, initialCompensationId, initialIncidentId, onBusyChange }: { supportCase?: Case; verification?: Verification | null; initialCompensationId?: string; initialIncidentId?: string; onBusyChange?: (busy: boolean) => void }) {
  const [active, setActive] = useState(false), [id, setId] = useState(initialCompensationId ?? "");
  useEffect(() => { onBusyChange?.(active); return () => onBusyChange?.(false); }, [active, onBusyChange]);
  return <section className="management-workspace" aria-label="고객 불편 보상">
    <h2>고객 불편 보상</h2>
    <SupportWorkPicker kind="COMPENSATION" caseId={supportCase?.caseId} disabled={active} onSelect={item => setId(item.requestId)} />
    {id && supportCase ? <Button variant="ghost" disabled={active} onClick={() => setId("")}>새 보상 요청 작성</Button> : null}
    {id ? <CompensationInspection key={id} id={id} caseId={supportCase?.caseId} onBusyChange={setActive} /> : supportCase ? <CreateCompensation key={initialIncidentId ?? "new"} supportCase={supportCase} verification={verification} initialIncidentId={initialIncidentId} onBusyChange={setActive} onCreated={created => { setId(created); }} /> : <EmptyState title="상담 건에서 보상을 시작해 주세요" description="기존 요청 찾기에서 승인과 지급 상태를 확인할 수 있습니다." />}
  </section>;
}

function CommandResult({ command }: { command: ReturnType<typeof useSupportCommand> }) {
  return <>{command.failure ? <ErrorState error={command.failure} /> : null}{command.pending ? <><InlineNotice tone="warning" title="보상 요청 결과를 확인하지 못했습니다" description="같은 요청으로 결과를 확인할 때까지 다른 명령을 잠급니다." /><Button variant="secondary" loading={command.busy} onClick={() => void command.retry()}>같은 보상 요청으로 결과 확인</Button></> : null}</>;
}

function CreateCompensation({ supportCase, verification, initialIncidentId, onCreated, onBusyChange }: { supportCase: Case; verification?: Verification | null; initialIncidentId?: string; onCreated: (id: string) => void; onBusyChange: (busy: boolean) => void }) {
  const orders = supportCase.subjectLinks.filter(link => link.subjectType === "ORDER" && link.relationship === "RELATED_ORDER");
  const [orderId, setOrderId] = useState(orders[0]?.subjectId ?? ""), [incidentId, setIncidentId] = useState(initialIncidentId ?? "");
  const [benefit, setBenefit] = useState<Payload["benefitType"]>("POINT"), [amount, setAmount] = useState("");
  const [responsibility, setResponsibility] = useState<Payload["responsibility"]>("UNDETERMINED"), [share, setShare] = useState("");
  const [basis, setBasis] = useState<NonNullable<Payload["evidenceBasis"]>>("STORE_CONSENT"), [costEvidence, setCostEvidence] = useState(""), [evidence, setEvidence] = useState("");
  const [template, setTemplate] = useState<Template | null>(null);
  const [evaluation, setEvaluation] = useState<{ result: Evaluation; body: Payload } | null>(null), [preparing, setPreparing] = useState(false), [error, setError] = useState<unknown>(null);
  const sequence = useRef(0);
  const order = useResource(useCallback(async () => orderId ? unwrap(await operationsApi.GET("/support/cases/{caseId}/orders/{orderId}", { params: { path: { caseId: supportCase.caseId, orderId } } })) : null, [supportCase.caseId, orderId]));
  const command = useSupportCommand(() => { setEvaluation(null); order.reload(); });
  const busy = preparing || command.busy || command.pending;
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false); }, [busy, onBusyChange]);
  useEffect(() => { sequence.current++; setEvaluation(null); }, [orderId, incidentId, benefit, amount, responsibility, share, basis, costEvidence, evidence, template, verification?.sessionId, verification?.state]);
  const expired = useExpired(verification?.expiresAt), evaluationExpired = useExpired(evaluation?.result.expiresAt);
  const verified = verification?.state === "VERIFIED" && verification.subjectType === "CUSTOMER" && verification.actionScope === "SUPPORT_ACTION" && verification.purpose === "CASE_RESOLUTION" && !expired;
  const active = !["RESOLVED", "CLOSED"].includes(supportCase.state);
  const currentVersion = order.state.status === "ready" ? (orderId ? order.state.value?.version : 0) : undefined;
  const storeCost = responsibility === "STORE" || responsibility === "SHARED";
  const platformShare = responsibility === "SHARED" ? percentBps(share) : responsibility === "STORE" ? 0 : 10000;
  const amountKrw = benefit === "COUPON" ? template?.amountKrw : /^\d+$/.test(amount) ? Number(amount) : undefined;
  const valid = uuid(incidentId) && currentVersion !== undefined && amountKrw !== undefined && Number.isSafeInteger(amountKrw) && amountKrw > 0 && platformShare !== null && (!storeCost || !!costEvidence.trim()) && (benefit !== "COUPON" || (!!template && !!orderId));
  async function evaluate() {
    if (!verified || !verification || !active || !valid || busy || amountKrw === undefined || currentVersion === undefined || platformShare === null) return;
    const generation = ++sequence.current; setPreparing(true); setError(null); setEvaluation(null);
    try {
      const body: Payload = { incidentId: incidentId.trim(), orderId: orderId || null, expectedTargetVersion: currentVersion, benefitType: benefit, amountKrw, couponTemplateId: benefit === "COUPON" ? template!.templateId : null, responsibility, evidenceBasis: storeCost ? basis : null, costEvidenceDigest: storeCost ? await supportDigest(costEvidence.trim()) : null, platformShareBps: platformShare, storeShareBps: 10000 - platformShare, verificationSessionId: verification.sessionId };
      const result = unwrap(await operationsApi.POST("/support/cases/{caseId}/compensation-evaluations", { params: { path: { caseId: supportCase.caseId } }, body }));
      if (generation === sequence.current) setEvaluation({ result, body });
    } catch (failure) { if (generation === sequence.current) setError(failure); } finally { setPreparing(false); }
  }
  async function create() {
    if (!evaluation || evaluationExpired || !evaluation.result.executable || evaluation.result.decision === "DENIED" || !verified || !active || !evidence.trim() || busy) return;
    setPreparing(true); setError(null);
    try {
      const body = { ...evaluation.body, evidenceDigest: await supportDigest(evidence.trim()) };
      command.submit(JSON.stringify(body), async key => { const created = unwrap(await operationsApi.POST("/support/cases/{caseId}/compensations", { params: { path: { caseId: supportCase.caseId }, header: { "Idempotency-Key": key } }, body })); onCreated(created.compensationRequestId); }, () => undefined);
    } catch (failure) { setError(failure); } finally { setPreparing(false); }
  }
  return <div className="surface-card management-card management-workspace">
    <h3>새 보상 요청</h3>
    {!active ? <InlineNotice tone="info" title="종결된 상담에서는 새 보상을 요청할 수 없습니다" description="필요하면 새 상담을 접수해 주세요." /> : !verified ? <InlineNotice tone="info" title="고객 본인확인이 필요합니다" description="상담 해결·업무 처리 목적의 고객 본인확인을 완료하거나 기존 세션을 조회해 주세요." /> : null}
    <TextField label="사고 ID" value={incidentId} onValueChange={setIncidentId} disabled={busy} required description="같은 사고를 다시 검토할 때도 기존 사고 ID를 사용합니다." />
    <SelectField label="보상 관련 주문" value={orderId} onValueChange={setOrderId} disabled={busy}><option value="">관련 주문 없음</option>{orders.map(link => <option key={link.subjectId} value={link.subjectId}>{supportSubjectLabel(link)}</option>)}</SelectField>
    {order.state.status === "loading" ? <LoadingState label="현재 주문 조건을 읽는 중" /> : order.state.status === "failed" ? <ErrorState error={order.state.error} retry={order.reload} /> : order.state.value ? <p>현재 주문 <StatusText state={order.state.value.state} /></p> : null}
    <SelectField label="보상 혜택" value={benefit} onValueChange={value => setBenefit(value as typeof benefit)} disabled={busy}><option value="POINT">포인트</option><option value="COUPON">쿠폰</option></SelectField>
    {benefit === "POINT" ? <TextField label="보상 금액" type="number" min="1" step="1" value={amount} onValueChange={setAmount} disabled={busy} description="정수 원 단위로 입력합니다." /> : <CouponPicker disabled={busy} selected={template} onSelect={setTemplate} />}
    <SelectField label="비용 책임" value={responsibility} onValueChange={value => setResponsibility(value as typeof responsibility)} disabled={busy}>{Object.entries(responsibilityLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
    {responsibility === "SHARED" ? <><TextField label="플랫폼 부담 비율 (%)" type="number" min="0.01" max="99.99" step="0.01" value={share} onValueChange={setShare} disabled={busy} />{platformShare !== null ? <p>매장 부담 {(10000 - platformShare) / 100}%</p> : null}</> : null}
    {storeCost ? <><SelectField label="비용 근거" value={basis} onValueChange={value => setBasis(value as typeof basis)} disabled={busy}>{Object.entries(basisLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField><TextField label="비용 증빙 참조" value={costEvidence} onValueChange={setCostEvidence} maxLength={500} disabled={busy} description="확인한 동의·조사·계약 기록의 참조를 입력합니다. 개인정보와 인증 원문은 입력하지 않습니다." /></> : null}
    <TextField label="보상 증빙 참조" value={evidence} onValueChange={setEvidence} maxLength={500} disabled={busy} description="확인한 상담 기록의 참조를 입력합니다. 서버에는 해시만 전송합니다." />
    <Button variant="secondary" disabled={busy || !verified || !active || !valid} onClick={() => void evaluate()}>현재 보상 가능 여부 평가</Button>
    {evaluation ? <><InlineNotice tone={evaluation.result.decision === "DENIED" ? "warning" : "info"} title={evaluation.result.decision === "DENIED" ? "현재 보상을 요청할 수 없습니다" : `${bandLabels[evaluation.result.band]} · ${routeLabels[evaluation.result.approvalRoute]}`} description={evaluation.result.reasonCodes.map(code => reasonLabels[code]).join(" · ") || "현재 정책 평가 결과입니다. 요청 등록과 실제 혜택 지급은 별도로 처리합니다."} />{evaluation.result.executable && evaluation.result.decision !== "DENIED" && !evaluationExpired ? <Button onClick={() => void create()} disabled={busy || !verified || !active || !evidence.trim()}>평가한 보상 요청 등록</Button> : null}</> : null}
    {evaluationExpired ? <InlineNotice tone="warning" title="보상 평가 시간이 만료되었습니다" description="현재 보상 가능 여부를 다시 평가해 주세요." /> : null}
    {error ? <ErrorState error={error} /> : null}<CommandResult command={command} />
  </div>;
}

function CouponPicker({ disabled, selected, onSelect }: { disabled: boolean; selected: Template | null; onSelect: (value: Template | null) => void }) {
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursors[cursors.length - 1];
  const read = useResource(useCallback(async () => unwrap(await operationsApi.GET("/support/compensation-coupon-templates", { params: { query: { limit: 20, cursor } } })), [cursor]));
  return <div className="management-workspace">
    {read.state.status === "loading" ? <LoadingState label="보상 쿠폰 조건을 읽는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : <>{read.state.value.items.length ? read.state.value.items.map(item => <div className="surface-card management-card" key={item.templateId}><Button variant="secondary" disabled={disabled} onClick={() => onSelect(item)}>{won.format(item.amountKrw)} 쿠폰 선택</Button><p>사용 기한 {item.validityDays}일 · 최소 사용 금액 {won.format(item.minimumEligibleSubtotalKrw)}</p></div>) : <EmptyState title="보상 쿠폰이 없습니다" description="사용할 수 있는 쿠폰 조건을 확인해 주세요." />}<div className="button-row"><Button variant="secondary" disabled={disabled || cursors.length === 1} onClick={() => setCursors(values => values.slice(0, -1))}>이전 쿠폰</Button><Button variant="secondary" disabled={disabled || !read.state.value.nextCursor} onClick={() => { if (read.state.status === "ready" && read.state.value.nextCursor) { const next = read.state.value.nextCursor; setCursors(values => [...values, next]); } }}>다음 쿠폰</Button></div></>}
    {selected ? <p>선택한 쿠폰 · {won.format(selected.amountKrw)} · 사용 기한 {selected.validityDays}일</p> : <p>등록된 쿠폰을 선택해 주세요. 쿠폰 보상에는 관련 주문이 필요합니다.</p>}
  </div>;
}

function CompensationInspection({ id, caseId, onBusyChange }: { id: string; caseId?: string; onBusyChange: (busy: boolean) => void }) {
  const read = useResource(useCallback(async () => { const value = unwrap(await operationsApi.GET("/support/compensations/{compensationRequestId}/workflow", { params: { path: { compensationRequestId: id } } })); if (caseId && value.request.supportCaseId !== caseId) throw new ApiRequestError(409, "RESOURCE_STATE_CONFLICT", "현재 상담에 연결된 보상 요청이 아닙니다."); return value; }, [id, caseId]));
  const [reviewed, setReviewed] = useState(false), [reason, setReason] = useState(""), [decision, setDecision] = useState<"APPROVE" | "DENY" | "RETURN_FOR_REVISION">("APPROVE"), [assignee, setAssignee] = useState<OperatorSelection | null>(null), [assignmentReason, setAssignmentReason] = useState(""), [message, setMessage] = useState("");
  const command = useSupportCommand(() => { setReviewed(false); read.reload(); });
  const busy = command.busy || command.pending;
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false); }, [busy, onBusyChange]);
  const value: Workflow | null = read.state.status === "ready" ? read.state.value : null;
  const expired = useExpired(value?.verificationExpiresAt);
  const allowed = (action: Workflow["allowedActions"][number]) => !!value?.allowedActions.includes(action) && (action === "RETRY_NOTIFICATION" || !expired);
  function execute() {
    if (!value || !allowed("EXECUTE") || !reviewed || busy || value.currentTargetVersion === null) return;
    const body = { expectedRequestVersion: value.request.version, expectedTargetVersion: value.currentTargetVersion, expectedPayloadDigest: value.request.payloadDigest };
    command.submit(JSON.stringify(body), key => operationsApi.POST("/support/compensations/{compensationRequestId}/executions", { params: { path: { compensationRequestId: id }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => setMessage("보상 지급 요청을 처리했습니다. 실제 지급과 알림 상태를 확인해 주세요."));
  }
  function decide() {
    if (!value?.approval || !allowed("DECIDE_SUPPORT_MANAGER") || !reason.trim() || (decision === "APPROVE" && !reviewed) || busy) return;
    const body = { revisionNumber: value.approval.revisionNumber, expectedRequestVersion: value.approval.requestVersion, decision, reason: reason.trim() };
    const requestId = value.approval.requestId;
    command.submit(JSON.stringify(body), key => operationsApi.POST("/support/action-requests/{requestId}/support-manager-decisions", { params: { path: { requestId }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => setMessage("보상 승인 결정을 기록했습니다"));
  }
  function reassign() {
    if (!assignee) return;
    if (!value?.approval || !allowed("REASSIGN") || !assignee || !assignmentReason.trim() || busy) return;
    const body = { revisionNumber: value.approval.revisionNumber, expectedRequestVersion: value.approval.requestVersion, expectedCaseVersion: value.approval.caseVersion, assigneeId: assignee.operatorId, reason: assignmentReason.trim() }, requestId = value.approval.requestId;
    command.submit(JSON.stringify(body), key => operationsApi.POST("/support/action-requests/{requestId}/reassignments", { params: { path: { requestId }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => setMessage("보상과 상담의 담당자를 변경했습니다"));
  }
  return <div className="surface-card management-card management-workspace">
    <h3>보상 검토와 지급</h3>
    {message ? <p role="status">{message}</p> : null}<CommandResult command={command} />
    {read.state.status === "loading" ? <LoadingState label="현재 보상 조건과 권한을 읽는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : value ? <>
      <p className="support-case-reference">보상 ID {id}</p><StatusText state={value.request.state} label={compensationStates[value.request.state]} />
      <p>{value.request.benefitType === "POINT" ? "포인트" : "쿠폰"} {won.format(value.request.amountKrw)} · {bandLabels[value.request.band]} · {routeLabels[value.request.approvalRoute]}</p>
      <p>{responsibilityLabels[value.terms.responsibility]} · 플랫폼 {value.terms.platformShareBps / 100}% · 매장 {value.terms.storeShareBps / 100}%</p>
      {value.couponTemplate ? <p>쿠폰 사용 기한 {value.couponTemplate.validityDays}일 · 최소 사용 금액 {won.format(value.couponTemplate.minimumEligibleSubtotalKrw)}</p> : null}
      {value.terms.evidenceBasis ? <p>비용 근거 · {basisLabels[value.terms.evidenceBasis]}</p> : null}
      <dl className="detail-list"><dt>사고 ID</dt><dd className="support-case-reference">{value.request.incidentId}</dd><dt>관련 주문</dt><dd className="support-case-reference">{value.request.orderId ?? "관련 주문 없음"}</dd>{value.request.couponTemplateId ? <><dt>쿠폰 조건 ID</dt><dd className="support-case-reference">{value.request.couponTemplateId}</dd></> : null}<dt>보상 증빙 해시</dt><dd className="support-case-reference">{value.terms.evidenceDigest}</dd>{value.terms.costEvidenceDigest ? <><dt>비용 증빙 해시</dt><dd className="support-case-reference">{value.terms.costEvidenceDigest}</dd></> : null}</dl>
      {value.approval ? <p>승인 단계 · <StatusText state={value.approval.state} label={approvalStates[value.approval.state]} /></p> : null}
      {value.request.benefitIssuedAt && value.request.terminalBenefitId ? <><strong>혜택 지급 완료</strong><p>{shortDateTime.format(new Date(value.request.benefitIssuedAt))}</p><p className="support-case-reference">지급 참조 {value.request.terminalBenefitId}</p></> : <p>혜택 지급이 아직 확인되지 않았습니다.</p>}
      <p>알림 전달 상태 · {value.request.notificationState ? <StatusText state={value.request.notificationState} label={value.request.notificationState === "NOTIFICATION_SKIPPED" ? "수신 설정에 따라 알림 생략" : undefined} /> : "전달 결과 없음"}</p>{value.request.notificationFailureCode ? <p>알림 확인 코드 {value.request.notificationFailureCode}</p> : null}
      {value.currentTargetVersion !== value.terms.targetVersion && !value.request.benefitIssuedAt ? <InlineNotice tone="warning" title="보상 요청 이후 주문 정보가 변경되었습니다" description="현재 주문 조건으로 다시 평가하고 기존 사고 ID로 새 보상 요청을 작성해 주세요." /> : null}
      <div className="button-row"><Button variant="secondary" onClick={() => { setReviewed(false); read.reload(); }} disabled={busy}>보상 상태 새로고침</Button><ButtonLink variant="secondary" to={`/support/compensations/${id}`}>보상 건 주소</ButtonLink>{value.approval?.state === "AWAITING_OPERATIONS" ? <ButtonLink variant="secondary" to={`/ops/support-investigations?requestId=${value.approval.requestId}`}>운영 조사 검토</ButtonLink> : null}</div>
      {allowed("EXECUTE") || allowed("DECIDE_SUPPORT_MANAGER") ? <Checkbox label="혜택과 비용 조건을 확인했습니다" checked={reviewed} onCheckedChange={setReviewed} disabled={busy} /> : null}
      {allowed("EXECUTE") ? <Button disabled={busy || !reviewed} onClick={execute}>확인한 보상 지급</Button> : null}
      {allowed("DECIDE_SUPPORT_MANAGER") ? <form className="operation-form" onSubmit={event => { event.preventDefault(); decide(); }}><SelectField label="보상 승인 결정" value={decision} onValueChange={next => setDecision(next as typeof decision)} disabled={busy}><option value="APPROVE">승인</option><option value="DENY">반려</option><option value="RETURN_FOR_REVISION">수정 요청</option></SelectField><TextAreaField label="승인 결정 사유" value={reason} onValueChange={setReason} maxLength={500} required disabled={busy} description="개인정보와 인증 원문은 입력하지 않습니다." /><Button type="submit" disabled={busy || !reason.trim() || (decision === "APPROVE" && !reviewed)}>보상 승인 결정 기록</Button></form> : null}
      {allowed("RETRY_NOTIFICATION") ? <Button variant="secondary" disabled={busy} onClick={() => command.submit(`notification:${id}:${value.request.version}`, key => operationsApi.POST("/support/compensations/{compensationRequestId}/notification-retries", { params: { path: { compensationRequestId: id }, header: { "Idempotency-Key": key } } }).then(unwrap), () => setMessage("보상 알림을 다시 요청했습니다"))}>보상 알림 다시 요청</Button> : null}
      {allowed("REASSIGN") ? <form className="operation-form" onSubmit={event => { event.preventDefault(); reassign(); }}><OperatorTargetPicker label="새 실행 담당자" purpose="COMPENSATION" value={assignee} onSelect={setAssignee} disabled={busy} /><TextAreaField label="배정 사유" value={assignmentReason} onValueChange={setAssignmentReason} maxLength={500} required disabled={busy} /><Button type="submit" disabled={busy || !assignee || !assignmentReason.trim()}>보상과 상담 함께 재배정</Button></form> : null}
      {value.approval?.state === "REVISION_REQUIRED" || value.approval?.state === "STALE" ? <ButtonLink variant="secondary" to={`/support/follow-up?caseId=${value.request.supportCaseId}&incidentId=${value.request.incidentId}`}>같은 사고로 보상 조건 다시 작성</ButtonLink> : null}
      {expired ? <InlineNotice tone="warning" title="본인확인 유효 시간이 지났습니다" description="새 지급이나 승인 전에 현재 본인확인 조건을 확인해 주세요. 이미 지급한 혜택의 알림은 별도로 처리합니다." /> : null}
    </> : null}
  </div>;
}

export function SupportCompensationPage() {
  const { compensationRequestId } = useParams();
  return <div className="console-page"><PageHeading title="고객 불편 보상" /><SupportCompensationWorkspace initialCompensationId={compensationRequestId} /></div>;
}
