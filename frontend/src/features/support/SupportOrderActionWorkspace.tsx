import { SupportOrderConsentPicker, type OrderConsentSelection } from "./SupportOrderConsentPicker";
import { SupportWorkPicker } from "./SupportWorkPicker";
import { supportSubjectLabel, isSupportSubjectSelectable, type SupportSubjectDisplaySource } from "./supportCaseLabels";
import { OperatorTargetPicker, type OperatorSelection } from "../operations/OperatorTargetPicker";
import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { ApiRequestError, unwrap } from "../../api/client";
import { Button, ButtonLink, EmptyState, InlineNotice, LoadingState, PageHeading, SelectField, TextAreaField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { shortDateTime } from "../../lib/format";
import { cancellationReasonLabels, orderActionLabels, orderChangeDigest, supportDigest, type CancellationReason, type OrderChangeAction } from "../../lib/supportOrderPayload";
import { useResource } from "../shared/useResource";
import { initialResolutionDraft, validResolutionDraft, resolutionPlan, resolutionDigest, type ResolutionDraft } from "../../lib/supportResolutionPayload";
import { SupportResolutionPlanFields } from "./SupportResolutionPlanFields";
import { SupportResolutionWorkspace } from "./SupportResolutionWorkspace";
import { useExpired } from "./useSupportExpiry";
import { useSupportCommand } from "./useSupportCommand";

type Case = { caseId: string; state: string; subjectLinks: readonly (SupportSubjectDisplaySource & { subjectType: string; subjectId: string; relationship: string })[] };
type Verification = { sessionId: string; state: string; actionScope: string; purpose: string; expiresAt: string };
type Request = components["schemas"]["SupportActionRequestResource"];
type Workflow = components["schemas"]["SupportOrderWorkflowResource"];
type Evaluation = components["schemas"]["SupportActionEvaluationResource"];
const requestStates: Record<Request["state"], string> = { AWAITING_SUPPORT_MANAGER: "상담 관리자 승인 대기", AWAITING_OPERATIONS: "운영 검토 대기", READY_FOR_EXECUTION: "실행 준비", REASSIGNMENT_REQUIRED: "실행 담당자 재배정 필요", REVISION_REQUIRED: "승인안 수정 필요", DENIED: "반려", EXPIRED: "만료", STALE: "요청 조건 변경", MANUAL_REVIEW: "수동 확인 필요", EXECUTED: "실행 완료", RESOLUTION_REQUIRED: "수락 후 해결 필요" };
const evaluationReasons: Record<string, string> = { POLICY_ALLOWED: "현재 정책에서 요청할 수 있습니다", POLICY_APPROVAL_REQUIRED: "별도 담당자의 승인이 필요합니다", UNSUPPORTED_TARGET_STATE: "현재 주문 상태에서는 처리할 수 없습니다", CASE_NOT_ELIGIBLE: "진행 중인 상담 건이 필요합니다", TARGET_RELATIONSHIP_MISMATCH: "본인확인 대상과 주문의 관계가 일치하지 않습니다", MISSING_PERMISSION: "필요한 업무 권한이 없습니다", VERIFICATION_SCOPE_MISMATCH: "업무 처리 목적의 본인확인이 필요합니다", VERIFICATION_PURPOSE_MISMATCH: "상담 해결 목적의 본인확인이 필요합니다", INSUFFICIENT_VERIFICATION: "추가 본인확인이 필요합니다", STALE_TARGET_VERSION: "주문 정보가 변경되었습니다" };

/** Creates typed order changes and inspects the current server-owned approval workflow. */
export function SupportOrderActionWorkspace({ supportCase, verification, initialRequestId, onBusyChange }: { supportCase?: Case; verification?: Verification | null; initialRequestId?: string; onBusyChange?: (busy: boolean) => void }) {
  const [activeCommand, setActiveCommand] = useState(false);
  const [requestId, setRequestId] = useState(initialRequestId ?? "");
  useEffect(() => { onBusyChange?.(activeCommand); return () => onBusyChange?.(false); }, [activeCommand, onBusyChange]);
  return <section className="management-workspace" aria-label="상담 주문 변경">
    <h2>주문 변경 요청</h2>
    <SupportWorkPicker kind="ORDER_ACTION" caseId={supportCase?.caseId} disabled={activeCommand} onSelect={item => setRequestId(item.requestId)} />
    {requestId && supportCase ? <Button variant="ghost" disabled={activeCommand} onClick={() => setRequestId("")}>새 요청 작성</Button> : null}
    {requestId ? <RequestInspection key={requestId} requestId={requestId} supportCase={supportCase} verification={verification} onBusyChange={setActiveCommand} /> : supportCase ? <CreateOrderRequest onBusyChange={setActiveCommand} supportCase={supportCase} verification={verification} onCreated={id => { setRequestId(id); }} /> : <EmptyState title="상담 건에서 새 요청을 시작해 주세요" description="기존 요청 찾기에서 현재 승인 단계와 실행 담당자를 확인할 수 있습니다." />}
  </section>;
}

export function SupportOrderActionPage() {
  const { requestId } = useParams();
  return <div className="console-page"><PageHeading title="주문 변경 승인과 실행" /><SupportOrderActionWorkspace initialRequestId={requestId} /></div>;
}


function CreateOrderRequest({ supportCase, verification, onCreated, revision, onBusyChange }: { onBusyChange?: (value: boolean) => void; supportCase: Case; verification?: Verification | null; onCreated: (id: string) => void; revision?: Request }) {
  const orders = supportCase.subjectLinks.filter(link => link.subjectType === "ORDER" && link.relationship === "RELATED_ORDER");
  const [orderId, setOrderId] = useState(revision?.targetId ?? orders.find(isSupportSubjectSelectable)?.subjectId ?? "");
  const [action, setAction] = useState<OrderChangeAction | "POST_ACCEPTANCE_RESOLUTION">(revision?.action === "POST_ACCEPTANCE_RESOLUTION" ? "POST_ACCEPTANCE_RESOLUTION" : revision?.action === "PICKUP_RESCHEDULE" ? "PICKUP_RESCHEDULE" : "ORDER_CANCELLATION");
  const [resolutionDraft, setResolutionDraft] = useState<ResolutionDraft>(initialResolutionDraft);
  const [reasonCode, setReasonCode] = useState<CancellationReason>("CHANGED_MIND");
  const [slotId, setSlotId] = useState("");
  const [reason, setReason] = useState(""); const [evidence, setEvidence] = useState("");
  const [evaluation, setEvaluation] = useState<Evaluation | null>(null);
  const [evaluating, setEvaluating] = useState(false); const [preparing, setPreparing] = useState(false); const [error, setError] = useState<unknown>(null);
  const generation = useRef(0);
  const selectedOrder = orders.find(link => link.subjectId === orderId && isSupportSubjectSelectable(link));
  const orderRead = useResource(useCallback(async () => selectedOrder ? unwrap(await operationsApi.GET("/support/cases/{caseId}/orders/{orderId}", { params: { path: { caseId: supportCase.caseId, orderId } } })) : null, [orderId, selectedOrder, supportCase.caseId]));
  const command = useSupportCommand(`order-request:${revision?.requestId ?? supportCase.caseId}`, () => { setEvaluation(null); orderRead.reload(); });
  const verificationExpired = useExpired(verification?.expiresAt), evaluationExpired = useExpired(evaluation?.expiresAt);
  const active = !["RESOLVED", "CLOSED"].includes(supportCase.state);
  const verified = verification?.state === "VERIFIED" && verification.actionScope === "SUPPORT_ACTION" && verification.purpose === "CASE_RESOLUTION" && !verificationExpired;
  useEffect(() => { generation.current++; setEvaluation(null); }, [action, orderId, verification?.sessionId, verification?.state]);
  const current = selectedOrder && orderRead.state.status === "ready" ? orderRead.state.value : null;
  const disabled = command.busy || command.pending || preparing || evaluating;
  useEffect(() => { onBusyChange?.(disabled); return () => onBusyChange?.(false); }, [disabled, onBusyChange]);
  async function evaluate() {
    if (!current || !verified || !verification || disabled) return;
    const sequence = ++generation.current; setEvaluating(true); setEvaluation(null); setError(null);
    try { const result = unwrap(await operationsApi.POST("/support/cases/{caseId}/action-evaluations", { params: { path: { caseId: supportCase.caseId } }, body: { action, orderId, expectedTargetVersion: current.version, verificationSessionId: verification.sessionId } })); if (sequence === generation.current) setEvaluation(result); }
    catch (failure) { if (sequence === generation.current) setError(failure); }
    finally { setEvaluating(false); }
  }
  async function submit() {
    if (!current || !verified || !verification || !evaluation || evaluationExpired || evaluation.decision === "DENIED" || disabled) return;
    setPreparing(true); setError(null);
    try {
      const plan = action === "POST_ACCEPTANCE_RESOLUTION" ? await resolutionPlan(resolutionDraft) : null;
      if (plan && !validResolutionDraft(resolutionDraft)) return;
      const body = { expectedTargetVersion: current.version, verificationSessionId: verification.sessionId, actionPayloadDigest: plan ? await resolutionDigest(orderId, plan) : await orderChangeDigest(action as OrderChangeAction, orderId, reasonCode, slotId), reason: reason.trim(), evidenceDigest: plan?.evidenceDigest ?? await supportDigest(evidence.trim()), ...(plan ? { amountKrw: plan.cashRefundKrw } : {}) };
      if (revision) {
        const next = { ...body, expectedRevisionNumber: revision.revisionNumber, expectedRequestVersion: revision.requestVersion };
        command.submit(JSON.stringify(next), key => operationsApi.POST("/support/action-requests/{requestId}/revisions", { params: { path: { requestId: revision.requestId }, header: { "Idempotency-Key": key } }, body: next }).then(unwrap), () => onCreated(revision.requestId));
      } else {
        const next = { ...body, action, orderId };
        command.submit(JSON.stringify(next), async key => { const created = unwrap(await operationsApi.POST("/support/cases/{caseId}/action-requests", { params: { path: { caseId: supportCase.caseId }, header: { "Idempotency-Key": key } }, body: next })); onCreated(created.requestId); }, () => undefined);
      }
    } catch (failure) { setError(failure); } finally { setPreparing(false); }
  }
  if (!active) return <InlineNotice tone="info" title="종결된 상담에서는 새 주문 변경을 요청할 수 없습니다" description="필요한 경우 새 상담을 접수해 주세요." />;
  if (!verified) return <><InlineNotice tone="info" title="업무 처리 목적의 본인확인이 필요합니다" description="상담 해결 목적의 본인확인을 완료하거나 기존 세션을 조회해 주세요." /><CommandResult command={command} /></>;
  if (!orders.length) return <EmptyState title="연결된 주문이 없습니다" description="상담 관리에서 관련 주문을 연결한 뒤 요청해 주세요." action={<ButtonLink to={`/support/cases/${supportCase.caseId}`}>상담 대상 연결</ButtonLink>} />;
  return <div className="surface-card management-card management-workspace">
    <h3>{revision ? "새 승인안 작성" : "새 주문 변경 요청"}</h3>
    {orders.some(link => !isSupportSubjectSelectable(link)) ? <p>표시 정보 조회 권한과 등록된 대상 프로필을 확인해 주세요.</p> : null}<SelectField label="연결된 주문" value={selectedOrder?.subjectId ?? ""} disabled={!!revision || disabled} onValueChange={setOrderId}><option value="">표시 정보를 확인한 주문 선택</option>{orders.map(link => <option key={link.subjectId} value={link.subjectId} disabled={!isSupportSubjectSelectable(link)}>{supportSubjectLabel(link)}</option>)}</SelectField>
    <SelectField label="주문 변경 업무" value={action} disabled={!!revision || disabled} onValueChange={value => { setAction(value as typeof action); setSlotId(""); }}>{Object.entries({ ...orderActionLabels, POST_ACCEPTANCE_RESOLUTION: " 수락 후 해결" }).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
    {orderRead.state.status === "loading" ? <LoadingState label="현재 주문 정보를 읽는 중" /> : orderRead.state.status === "failed" ? <ErrorState error={orderRead.state.error} retry={orderRead.reload} /> : current ? <>
      <p>현재 주문 <StatusText state={current.state} /> · 버전 {current.version}</p>
      {action === "POST_ACCEPTANCE_RESOLUTION" ? <SupportResolutionPlanFields value={resolutionDraft} onChange={setResolutionDraft} disabled={disabled} /> : <OrderPayloadFields action={action} pickupScope={{ caseId: supportCase.caseId, orderId }} reason={reasonCode} slotId={slotId} onReason={setReasonCode} onSlot={setSlotId} disabled={disabled} />}
      <Button variant="secondary" disabled={disabled} loading={evaluating} onClick={() => void evaluate()}>현재 주문 변경 가능 여부 확인</Button>
    </> : null}
    {evaluation ? <InlineNotice tone={evaluation.decision === "DENIED" ? "warning" : "info"} title={evaluation.decision === "DENIED" ? "현재 요청할 수 없습니다" : evaluation.decision === "APPROVAL_REQUIRED" ? "승인 후 실행할 수 있습니다" : "현재 요청할 수 있습니다"} description={`${evaluation.reasonCodes.map(code => evaluationReasons[code] ?? code).join(" · ")} · 필요한 본인확인 ${evaluation.requiredVerificationLevel === "ENHANCED" ? "강화" : "기본"}`} /> : null}
    {evaluation && evaluation.decision !== "DENIED" && !evaluationExpired && current ? <form className="operation-form" onSubmit={event => { event.preventDefault(); void submit(); }}>
      <TextAreaField label="요청 사유" value={reason} onValueChange={setReason} required maxLength={500} disabled={disabled} description="개인정보와 인증 원문은 적지 않습니다." />
      {action !== "POST_ACCEPTANCE_RESOLUTION" ? <TextField label="증빙 참조" value={evidence} onValueChange={setEvidence} required maxLength={500} disabled={disabled} description="확인한 상담 기록 등의 참조를 입력합니다. 서버에는 해시만 전송합니다." /> : null}
      <Button type="submit" disabled={disabled || !reason.trim() || (action === "POST_ACCEPTANCE_RESOLUTION" ? !validResolutionDraft(resolutionDraft) : !evidence.trim()) || (action === "PICKUP_RESCHEDULE" && !slotId)}>{revision ? "새 승인안 제출" : "주문 변경 요청 등록"}</Button>
    </form> : null}
    {evaluationExpired ? <InlineNotice tone="warning" title="평가 유효 시간이 지났습니다" description="현재 주문 변경 가능 여부를 다시 확인해 주세요." /> : null}
    {error ? <ErrorState error={error} /> : null}<CommandResult command={command} />
  </div>;
}

function RequestInspection({ requestId, supportCase, verification, onBusyChange }: { onBusyChange: (value: boolean) => void; requestId: string; supportCase?: Case; verification?: Verification | null }) {
  const read = useResource(useCallback(async () => { const result = unwrap(await operationsApi.GET("/support/action-requests/{requestId}/workflow", { params: { path: { requestId } } })); if (supportCase && result.request.caseId !== supportCase.caseId) throw new ApiRequestError(403, "ACCESS_DENIED", "현재 상담에 속한 요청이 아닙니다."); return result; }, [requestId, supportCase?.caseId]));
  const command = useSupportCommand(`order-workflow:${requestId}`, read.reload);
  const [message, setMessage] = useState(""); const [execution, setExecution] = useState<components["schemas"]["SupportOrderChangeExecutionResource"] | null>(null);
  const [reasonCode, setReasonCode] = useState<CancellationReason>("CHANGED_MIND"); const [slotId, setSlotId] = useState(""); const [digest, setDigest] = useState("");
  const [decision, setDecision] = useState<components["schemas"]["SupportApprovalDecision"]>("APPROVE"); const [reason, setReason] = useState(""); const [assignee, setAssignee] = useState<OperatorSelection | null>(null); const [consent, setConsent] = useState<{ value: OrderConsentSelection; binding: string } | null>(null);
  const [resolutionDraft, setResolutionDraft] = useState<ResolutionDraft>(initialResolutionDraft);
  const [resolutionBusy, setResolutionBusy] = useState(false);
  const [preparingResolution, setPreparingResolution] = useState(false);
  const [resolutionError, setResolutionError] = useState<unknown>(null);
  const [createdResolutionId, setCreatedResolutionId] = useState("");
  const [revisionOpen, setRevisionOpen] = useState(false);
  const [revisionBusy, setRevisionBusy] = useState(false);
  const navigatingBlocked = command.busy || command.pending || revisionBusy || resolutionBusy || preparingResolution;
  useEffect(() => { onBusyChange(navigatingBlocked); return () => onBusyChange(false); }, [navigatingBlocked, onBusyChange]);
  const value = read.state.status === "ready" ? read.state.value : null;
  const request = value?.request;
  const direct = request?.action === "ORDER_CANCELLATION" || request?.action === "PICKUP_RESCHEDULE";
  const expired = useExpired(request?.expiresAt);
  const consentBinding = `${requestId}:${request?.revisionNumber}:${request?.requestVersion}:${request?.targetVersion}`;
  const selectedConsent = consent?.binding === consentBinding ? consent.value : null;
  const consentExpired = useExpired(selectedConsent?.expiresAt);
  const authorizationId = selectedConsent && !consentExpired ? selectedConsent.authorizationId : "";
  useEffect(() => { let live = true; setDigest(""); if (request && direct && (request.action === "ORDER_CANCELLATION" || slotId)) void orderChangeDigest(request.action as OrderChangeAction, request.targetId, reasonCode, slotId).then(result => { if (live) setDigest(result); }); return () => { live = false; }; }, [request?.targetId, request?.action, reasonCode, slotId]);
  useEffect(() => { let live = true; if (request?.action !== "POST_ACCEPTANCE_RESOLUTION") return; setDigest(""); if (validResolutionDraft(resolutionDraft)) void resolutionPlan(resolutionDraft).then(plan => resolutionDigest(request.targetId, plan)).then(result => { if (live) setDigest(result); }).catch(setResolutionError); return () => { live = false; }; }, [request?.action, request?.targetId, resolutionDraft]);
  const allowed = (name: Workflow["allowedActions"][number]) => !!value && !expired && value.allowedActions.includes(name);
  const blocked = command.busy || command.pending || read.refreshing || revisionBusy || resolutionBusy || preparingResolution;
  const matches = !!request && digest === request.actionPayloadDigest;
  function decide() {
    if (!request || !allowed("DECIDE_SUPPORT_MANAGER") || !reason.trim() || (decision === "APPROVE" && !matches)) return;
    const body = { revisionNumber: request.revisionNumber, expectedRequestVersion: request.requestVersion, decision, reason: reason.trim() };
    command.submit(JSON.stringify({ operation: "decide", body }), key => operationsApi.POST("/support/action-requests/{requestId}/support-manager-decisions", { params: { path: { requestId }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => { setMessage("승인 결정을 기록했습니다"); setReason(""); });
  }
  function reassign() {
    if (!assignee) return;
    if (!request || !value || !allowed("REASSIGN")) return;
    const body = { revisionNumber: request.revisionNumber, expectedRequestVersion: request.requestVersion, expectedCaseVersion: value.caseVersion, assigneeId: assignee.operatorId, reason: reason.trim() };
    command.submit(JSON.stringify({ operation: "reassign", body }), key => operationsApi.POST("/support/action-requests/{requestId}/reassignments", { params: { path: { requestId }, header: { "Idempotency-Key": key } }, body }).then(unwrap), () => setMessage("상담과 요청의 담당자를 변경했습니다"));
  }
  function execute() {
    if (!request || blocked || !allowed("EXECUTE") || !direct || !matches || (value?.order?.state === "ACCEPTED" && !authorizationId)) return;
    const common = { revisionNumber: request.revisionNumber, expectedRequestVersion: request.requestVersion, expectedTargetVersion: request.targetVersion, ...(authorizationId.trim() ? { authorizationId: authorizationId.trim() } : {}) };
    const body = request.action === "ORDER_CANCELLATION" ? { ...common, action: "ORDER_CANCELLATION" as const, reasonCode } : { ...common, action: "PICKUP_RESCHEDULE" as const, newPickupSlotId: slotId };
    command.submit(JSON.stringify({ operation: "execute", body }), async key => { const result = unwrap(await operationsApi.POST("/support/action-requests/{requestId}/executions", { params: { path: { requestId }, header: { "Idempotency-Key": key } }, body })); setExecution(result); setMessage(result.outcome === "RESOLUTION_REQUIRED" ? "수락 후 해결 업무로 전환해야 합니다" : "주문 변경을 처리했습니다"); }, () => undefined);
  }
  async function createResolution() {
    if (!request || !matches || !allowed("EXECUTE") || blocked || !validResolutionDraft(resolutionDraft)) return;
    setPreparingResolution(true); setResolutionError(null);
    try {
      const plan = await resolutionPlan(resolutionDraft);
      const body = { ...plan, requestId, revisionNumber: request.revisionNumber, expectedRequestVersion: request.requestVersion, expectedOrderVersion: request.targetVersion };
      command.submit(JSON.stringify(body), async key => { const created = unwrap(await operationsApi.POST("/support/orders/{orderId}/post-acceptance-resolutions", { params: { path: { orderId: request.targetId }, header: { "Idempotency-Key": key } }, body })); setCreatedResolutionId(created.resolutionId); }, () => setMessage("해결 실행 계획을 등록했습니다. 단계별 처리를 시작해 주세요."));
    } catch (error) { setResolutionError(error); } finally { setPreparingResolution(false); }
  }
  return <section className="surface-card management-card management-workspace" aria-label="현재 주문 변경 요청">
    {message ? <p role="status">{message}</p> : null}
    {createdResolutionId ? <ButtonLink variant="secondary" to={`/support/resolutions/${createdResolutionId}`}>생성된 해결 건 열기</ButtonLink> : null}
    {execution ? <div><p>{execution.outcome === "RESOLUTION_REQUIRED" ? "주문이 제조 단계로 진행되어 별도 해결이 필요합니다" : "주문 변경 실행 결과"} · <StatusText state={execution.currentTargetState} /></p><p>{execution.paymentRecoveryState === "REQUESTED" ? "환불 요청 접수" : execution.paymentRecoveryState === "NOT_REQUIRED" ? "추가 환불 처리 없음" : `환불 후속 처리 ${execution.paymentRecoveryState ?? "해당 없음"}`}</p></div> : null}
    <CommandResult command={command} />{resolutionError ? <ErrorState error={resolutionError} /> : null}
    {read.state.status === "loading" ? <LoadingState label="현재 승인안을 읽는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : request && value ? <>
      <h3>{direct ? orderActionLabels[request.action as OrderChangeAction] : "상담 후속 요청"}</h3>
      <p className="support-case-reference">요청 ID {requestId}</p>
      <StatusText state={request.state} label={requestStates[request.state]} />
      <dl className="detail-list"><div><dt>승인안 / 요청 버전</dt><dd>{request.revisionNumber} / {request.requestVersion}</dd></div><div><dt>대상 주문 버전</dt><dd>{request.targetVersion}</dd></div><div><dt>유효 시간</dt><dd>{shortDateTime.format(new Date(request.expiresAt))}까지</dd></div><div><dt>실행 담당자</dt><dd className="support-case-reference">{request.executorActorId}</dd></div></dl>
      {request.approvalSteps.map(step => <p key={step.stepType}>{step.stepType === "SUPPORT_MANAGER" ? "상담 관리자" : "운영 검토"} · <StatusText state={step.state} /></p>)}
      <div className="button-row"><Button variant="secondary" onClick={read.reload} disabled={command.busy || resolutionBusy || preparingResolution}>요청 상태 새로고침</Button><ButtonLink variant="secondary" to={`/support/action-requests/${requestId}`}>요청 검토 주소</ButtonLink><ButtonLink variant="secondary" to={`/support/follow-up?caseId=${request.caseId}&requestId=${requestId}`}>상담에서 요청 이어가기</ButtonLink></div>
      {expired && request.state !== "EXECUTED" ? <InlineNotice tone="warning" title="승인안의 유효 시간이 지났습니다" description="현재 상태를 다시 확인하고 필요한 경우 새 요청을 시작해 주세요." /> : null}
      {(direct || request.action === "POST_ACCEPTANCE_RESOLUTION") && !value.resolutionId && !createdResolutionId && (allowed("EXECUTE") || allowed("DECIDE_SUPPORT_MANAGER")) ? <>
        {request.action === "POST_ACCEPTANCE_RESOLUTION" ? <SupportResolutionPlanFields value={resolutionDraft} onChange={setResolutionDraft} disabled={blocked} /> : <OrderPayloadFields action={request.action as OrderChangeAction} pickupScope={value.order ? { requestId } : undefined} reason={reasonCode} slotId={slotId} onReason={setReasonCode} onSlot={setSlotId} disabled={blocked} />}
        <InlineNotice tone={matches ? "info" : "warning"} title={matches ? "선택한 내용이 현재 승인안과 일치합니다" : "선택한 내용이 현재 승인안과 다릅니다"} description="승인 요청 때 선택한 변경 내용과 증빙을 확인합니다. 내용이 다르면 새 승인안이 필요합니다." />
      </> : null}
      {allowed("DECIDE_SUPPORT_MANAGER") ? <form className="operation-form" onSubmit={event => { event.preventDefault(); decide(); }}><SelectField label="승인 결정" value={decision} onValueChange={value => setDecision(value as typeof decision)} disabled={blocked}><option value="APPROVE">승인</option><option value="DENY">반려</option><option value="RETURN_FOR_REVISION">수정 요청</option></SelectField><TextAreaField label="결정 사유" value={reason} onValueChange={setReason} required maxLength={500} disabled={blocked} /><Button type="submit" disabled={blocked || (decision === "APPROVE" && !matches) || !reason.trim()}>승인 결정 기록</Button></form> : null}
      {direct && allowed("EXECUTE") ? <form className="operation-form" onSubmit={event => { event.preventDefault(); execute(); }}>
        {value.order?.state === "ACCEPTED" ? <SupportOrderConsentPicker key={consentBinding} requestId={requestId} value={selectedConsent} onValueChange={selected => setConsent(selected ? { value: selected, binding: consentBinding } : null)} disabled={blocked} /> : null}
        <Button type="submit" disabled={blocked || !matches || (value.order?.state === "ACCEPTED" && !authorizationId.trim())}>확인한 주문 변경 실행</Button>
      </form> : null}
      {request.action === "POST_ACCEPTANCE_RESOLUTION" && allowed("EXECUTE") && !value.resolutionId && !createdResolutionId ? <Button disabled={blocked || !matches} onClick={() => void createResolution()}>확인한 해결 실행 계획 등록</Button> : null}
      {value.resolutionId ? <SupportResolutionWorkspace key={value.resolutionId} resolutionId={value.resolutionId} onBusyChange={setResolutionBusy} /> : null}
      {allowed("REASSIGN") ? <form className="operation-form" onSubmit={event => { event.preventDefault(); reassign(); }}><OperatorTargetPicker label="새 실행 담당자" purpose={request.action === "GOODWILL_COMPENSATION" ? "COMPENSATION" : request.action} value={assignee} onSelect={setAssignee} disabled={blocked} /><TextAreaField label="배정 사유" value={reason} onValueChange={setReason} required maxLength={500} disabled={blocked} /><Button variant="secondary" type="submit" disabled={blocked || !assignee || !reason.trim()}>상담과 요청 함께 재배정</Button></form> : null}
      {allowed("REVISE") && supportCase ? <><Button variant="secondary" disabled={blocked} onClick={() => setRevisionOpen(value => !value)}>승인안 수정</Button>{revisionOpen ? <CreateOrderRequest onBusyChange={setRevisionBusy} key={request.revisionNumber} supportCase={supportCase} verification={verification} revision={request} onCreated={() => { setRevisionOpen(false); setMessage("새 승인안을 제출했습니다"); read.reload(); }} /> : null}</> : null}
      {!expired && value.allowedActions.length === 0 ? <InlineNotice tone="info" title="현재 담당자가 실행할 명령이 없습니다" description="다른 담당자의 승인 대기, 권한 또는 요청 상태를 확인해 주세요." /> : null}
    </> : null}
  </section>;
}

function CommandResult({ command }: { command: ReturnType<typeof useSupportCommand> }) {
  return <>{command.failure ? <ErrorState error={command.failure} /> : null}{command.pending ? <InlineNotice tone="warning" title="요청 결과를 확인하지 못했습니다" description="새 명령을 만들기 전에 같은 요청으로 결과를 확인해 주세요." /> : null}{command.pending ? <Button variant="secondary" loading={command.busy} onClick={() => void command.retry()}>같은 요청으로 결과 확인</Button> : null}</>;
}

type PickupScope = { caseId: string; orderId: string; requestId?: never } | { requestId: string; caseId?: never; orderId?: never };
function OrderPayloadFields({ action, pickupScope, reason, slotId, onReason, onSlot, disabled }: { action: OrderChangeAction; pickupScope?: PickupScope; reason: CancellationReason; slotId: string; onReason: (value: CancellationReason) => void; onSlot: (value: string) => void; disabled: boolean }) {
  return action === "ORDER_CANCELLATION" ? <SelectField label="취소 사유 확인" value={reason} onValueChange={value => onReason(value as CancellationReason)} disabled={disabled}>{Object.entries(cancellationReasonLabels).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</SelectField> : pickupScope ? <PickupChoice scope={pickupScope} value={slotId} onChange={onSlot} disabled={disabled} /> : <InlineNotice tone="warning" title="매장 정보를 확인할 수 없습니다" description="주문 조회 권한을 확인한 뒤 다시 조회해 주세요." />;
}
function PickupChoice({ scope, value, onChange, disabled }: { scope: PickupScope; value: string; onChange: (value: string) => void; disabled: boolean }) {
  const { caseId, orderId, requestId } = scope;
  const read = useResource(useCallback(async () => requestId
    ? unwrap(await operationsApi.GET("/support/action-requests/{requestId}/pickup-slots", { params: { path: { requestId } } }))
    : unwrap(await operationsApi.GET("/support/cases/{caseId}/orders/{orderId}/pickup-slots", { params: { path: { caseId: caseId!, orderId: orderId! } } })), [caseId, orderId, requestId]));
  if (read.state.status === "loading") return <LoadingState label="현재 가능한 픽업 시간을 읽는 중" />;
  if (read.state.status === "failed") return <ErrorState error={read.state.error} retry={read.reload} />;
  return <SelectField label="변경할 픽업 시간" value={value} onValueChange={onChange} disabled={disabled} required description="현재 남은 정원은 실행할 때 다시 검증합니다."><option value="">픽업 시간을 선택해 주세요</option>{read.state.value.items.map(slot => <option key={slot.pickupSlotId} value={slot.pickupSlotId} disabled={slot.remainingCapacity < 1}>{shortDateTime.format(new Date(slot.startsAt))} · 남은 정원 {slot.remainingCapacity}</option>)}</SelectField>;
}
