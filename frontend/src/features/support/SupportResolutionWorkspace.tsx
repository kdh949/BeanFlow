import { useCallback, useEffect, useState } from "react";
import { useParams } from "react-router";
import { operationsApi } from "../../api/consoleClient";
import { ApiRequestError, unwrap } from "../../api/client";
import type { components } from "../../api/schema";
import { Button, ButtonLink, InlineNotice, LoadingState, PageHeading } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { shortDateTime, won } from "../../lib/format";
import { resolutionOutcomeLabels, resolutionResponsibilityLabels } from "../../lib/supportResolutionPayload";
import { useResource } from "../shared/useResource";
import { useExpired } from "./useSupportExpiry";
import { useSupportCommand } from "./useSupportCommand";
type Resolution = components["schemas"]["PostAcceptanceResolutionResource"];
const stepLabels: Record<Resolution["steps"][number]["type"], string> = { PAYMENT_REFUND: "현금 환불", POINT_RESTORATION: "포인트 복원", COUPON_RESTORATION: "쿠폰 복원", SETTLEMENT_ADJUSTMENT: "매장 정산 조정", CUSTOMER_NOTIFICATION: "고객 알림" };
const stepStateLabels: Record<Resolution["steps"][number]["state"], string> = { PENDING: "대기 중", PROCESSING: "처리 중", RETRY_SCHEDULED: "재시도 예약", SUCCEEDED: "처리 완료", NOT_REQUIRED: "해당 없음", UNKNOWN: "결과 불명", RECONCILING: "결과 확인 중", MANUAL_REVIEW: "수동 확인 필요", BLOCKED: "선행 조건 확인 필요" };
const stateLabels: Record<Resolution["state"], string> = { PLANNED: "실행 계획 등록", EXECUTING: "처리 중", PARTIALLY_RESOLVED: "일부 처리 완료", RECONCILING: "결과 확인 중", RESOLVED: "금융 처리 완료", MANUAL_REVIEW: "수동 확인 필요" };
/** Reads the persisted resolution and current workflow before advancing or reconciling existing steps. */
export function SupportResolutionWorkspace({ resolutionId, onBusyChange }: { resolutionId: string; onBusyChange?: (busy: boolean) => void }) {
  const read = useResource(useCallback(async () => {
    const resolution = unwrap(await operationsApi.GET("/support/post-acceptance-resolutions/{resolutionId}", { params: { path: { resolutionId } } }));
    const workflow = unwrap(await operationsApi.GET("/support/action-requests/{requestId}/workflow", { params: { path: { requestId: resolution.requestId } } }));
    if (workflow.request.action !== "POST_ACCEPTANCE_RESOLUTION" || workflow.resolutionId !== resolutionId || (workflow.order && workflow.order.orderId !== resolution.orderId)) throw new ApiRequestError(409, "RESOURCE_STATE_CONFLICT", "해결 건과 현재 승인안의 연결을 확인할 수 없습니다.");
    return { resolution, workflow };
  }, [resolutionId]));
  const command = useSupportCommand(read.reload);
  const [message, setMessage] = useState("");
  const busy = command.busy || command.pending;
  useEffect(() => { onBusyChange?.(busy); return () => onBusyChange?.(false); }, [busy, onBusyChange]);
  function submit(reconcile: boolean) {
    if (read.state.status !== "ready" || busy) return;
    const { resolution, workflow } = read.state.value;
    const allowed = workflow.allowedActions.includes("ADVANCE_RESOLUTION") || (workflow.allowedActions.includes("EXECUTE") && new Date(workflow.request.expiresAt).getTime() > Date.now());
    if (!allowed || !workflow.order) return;
    const body = { expectedResolutionVersion: resolution.version, expectedOrderVersion: workflow.order.version };
    if (reconcile) {
      const payment = resolution.steps.find(step => step.type === "PAYMENT_REFUND");
      if (!payment || !["UNKNOWN", "MANUAL_REVIEW"].includes(payment.state)) return;
      const payload = { ...body, stepType: "PAYMENT_REFUND" as const };
      command.submit(JSON.stringify(payload), key => operationsApi.POST("/support/post-acceptance-resolutions/{resolutionId}/reconciliations", { params: { path: { resolutionId }, header: { "Idempotency-Key": key } }, body: payload }).then(unwrap), () => setMessage("환불 재조회 요청을 처리했습니다. 현재 단계별 결과를 확인해 주세요."));
    } else {
      const payload = { ...body, expectedRequestVersion: workflow.request.requestVersion };
      command.submit(JSON.stringify(payload), key => operationsApi.POST("/support/post-acceptance-resolutions/{resolutionId}/executions", { params: { path: { resolutionId }, header: { "Idempotency-Key": key } }, body: payload }).then(unwrap), () => setMessage("후속 처리 요청을 처리했습니다. 현재 단계별 결과를 확인해 주세요."));
    }
  }
  const value = read.state.status === "ready" ? read.state.value : null;
  const expired = useExpired(value?.workflow.request.expiresAt);
  const allowed = value && (value.workflow.allowedActions.includes("ADVANCE_RESOLUTION") || (value.workflow.allowedActions.includes("EXECUTE") && !expired));
  const actionable = value?.resolution.steps.some(step => !["SUCCEEDED", "NOT_REQUIRED", "BLOCKED", "MANUAL_REVIEW"].includes(step.state));
  return <section className="surface-card management-card management-workspace" aria-label="수락 후 해결 진행">
    <h2>수락 후 해결 진행</h2>
    {message ? <p role="status">{message}</p> : null}
    {command.failure ? <ErrorState error={command.failure} /> : null}
    {command.pending ? <><InlineNotice tone="warning" title="후속 처리 결과를 확인하지 못했습니다" description="같은 요청으로 확인할 때까지 다른 명령을 잠급니다." /><Button variant="secondary" loading={command.busy} onClick={() => void command.retry()}>같은 요청으로 결과 확인</Button></> : null}
    {read.state.status === "loading" ? <LoadingState label="해결 건과 현재 처리 권한을 읽는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : value ? <>
      <p className="support-case-reference">해결 ID {resolutionId}</p><StatusText state={value.resolution.state} label={stateLabels[value.resolution.state]} />
      <p>{resolutionOutcomeLabels[value.resolution.outcome]} · {resolutionResponsibilityLabels[value.resolution.responsibility]} · 현금 {won.format(value.resolution.cashRefundKrw)}</p>
      <div className="management-workspace">{value.resolution.steps.map(step => <article key={step.type} className="surface-card management-card"><h3>{stepLabels[step.type]}</h3><StatusText state={step.state} label={stepStateLabels[step.state]} /><p>시도 {step.attemptCount}회</p>{step.failureCode ? <p>확인 코드 {step.failureCode}</p> : null}{step.nextAttemptAt ? <p>다음 처리 {shortDateTime.format(new Date(step.nextAttemptAt))}</p> : null}{step.resultReference ? <p className="support-case-reference">결과 참조 {step.resultReference}</p> : null}{step.type === "CUSTOMER_NOTIFICATION" ? <p>알림 전달 상태 · {step.ownerState ? <StatusText state={step.ownerState} /> : "전달 결과 없음"}</p> : null}</article>)}</div>
      <div className="button-row"><Button variant="secondary" onClick={read.reload} disabled={command.busy}>해결 상태 새로고침</Button><ButtonLink variant="secondary" to={`/support/resolutions/${resolutionId}`}>해결 건 주소</ButtonLink></div>
      {allowed && actionable ? <Button onClick={() => submit(false)} disabled={busy}>미완료 단계 진행 요청</Button> : null}
      {allowed && value.resolution.steps.some(step => step.type === "PAYMENT_REFUND" && ["UNKNOWN", "MANUAL_REVIEW"].includes(step.state)) ? <Button variant="secondary" onClick={() => submit(true)} disabled={busy}>환불 결과 재조회 요청</Button> : null}
      {!allowed ? <InlineNotice tone="info" title="현재 후속 처리 권한이 없습니다" description="현재 상담 담당자·실행 권한·승인 상태를 확인해 주세요." /> : null}
    </> : null}
  </section>;
}
export function SupportResolutionPage() {
  const { resolutionId } = useParams();
  return <div className="console-page"><PageHeading title="수락 후 해결" />{resolutionId ? <SupportResolutionWorkspace resolutionId={resolutionId} /> : null}</div>;
}
