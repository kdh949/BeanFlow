import { useCallback, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, InlineNotice, LoadingState, SelectField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { PaymentRepairWorkspace } from "./PaymentRepairWorkspace";
const stepLabels = { PAYMENT: "환불", PICKUP: "픽업 예약 해제", COUPON: "쿠폰 복원", POINTS: "포인트 복원", CUSTOMER_NOTIFICATION: "고객 알림" };
/** Reads all compensation steps without treating the terminal order as a completed refund. */
export function OrderCompensationWorkspace() {
  const [orderId, setOrderId] = useState("");
  const [accessReason, setAccessReason] = useState("");
  const [query, setQuery] = useState<{ orderId: string; reason: string } | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [scheduled, setScheduled] = useState<components["schemas"]["CustomerCancellationRefundReconciliation"] | null>(null);
  const intent = useRef(new SubmissionIntent());
  const detail = useResource(useCallback(async () => query ? unwrap(await operationsApi.GET("/operations/orders/{orderId}/compensation", { params: { path: { orderId: query.orderId }, header: { "X-Access-Reason": query.reason } } })) : null, [query]));
  const current = detail.state.status === "ready" ? detail.state.value : null;
  const canReconcile = current?.compensation.trigger === "CUSTOMER_CANCELLATION" && !current.paymentSetupIssue && current.compensation.steps.some(step => step.type === "PAYMENT" && step.state === "MANUAL_REVIEW");
  async function reconcile() {
    if (busy || !query || !canReconcile || !reason.trim()) return;
    setBusy(true); setFailure(null);
    const body = { reason: reason.trim() };
    try {
      setScheduled(unwrap(await operationsApi.POST("/operations/orders/{orderId}/customer-cancellation-refund-reconciliations", { params: { path: { orderId: query.orderId }, header: { "Idempotency-Key": intent.current.keyFor(JSON.stringify({ orderId: query.orderId, ...body })) } }, body })));
      intent.current.complete(); setReason("");
    } catch (error) { setFailure(error); } finally { detail.reload(); setBusy(false); }
  }
  return <section className="management-workspace"><h2>주문 취소·거절 후속 처리</h2>
    <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); setQuery({ orderId: orderId.trim(), reason: accessReason }); setFailure(null); setScheduled(null); setReason(""); intent.current.complete(); }}><fieldset className="catalog-fieldset" disabled={busy}><legend>주문 조회</legend><TextField label="후속 처리 주문 ID" value={orderId} onValueChange={setOrderId} required /><SelectField label="주문 후속 처리 조회 사유" value={accessReason} onValueChange={setAccessReason}><option value="">조회 목적 선택</option><option value="ORDER_RECOVERY_REVIEW">주문 후속 처리 조사</option></SelectField><Button type="submit" disabled={!orderId.trim() || !accessReason}>주문 후속 처리 조회</Button></fieldset></form>
    {scheduled ? <InlineNotice title="환불 결과 조회를 예약했습니다" description={`아직 환불 완료가 아닙니다. ${fullDateTime.format(new Date(scheduled.scheduledAt))} 예약 · 요청 ${scheduled.operationId}`} /> : null}
    {failure ? <ErrorState error={failure} /> : null}
    {detail.state.status === "loading" ? <LoadingState label="주문 후속 처리를 불러오는 중" /> : detail.state.status === "failed" ? <ErrorState error={detail.state.error} retry={detail.reload} /> : current ? <>
      <article className="surface-card management-card"><h3>{current.compensation.trigger === "CUSTOMER_CANCELLATION" ? "고객 취소 후속 처리" : "매장 거절 후속 처리"}</h3><StatusText state={current.compensation.state} /><p className="support-case-reference">{current.compensation.caseId}</p><p>갱신 {fullDateTime.format(new Date(current.compensation.updatedAt))}</p><p>{current.compensation.benefitPolicies.map(policy => `${policy.benefitType === "COUPON" ? "쿠폰" : "포인트"} 복원 정책 ${policy.policyVersionId}`).join(" · ")}</p></article>
      <div className="management-card-grid">{current.compensation.steps.map(step => <article className="surface-card management-card" key={step.type}><h3>{stepLabels[step.type]}</h3><StatusText state={step.state} /><p>시도 {step.attemptCount}회</p>{step.lastErrorCode ? <p className="support-case-reference">오류 코드 {step.lastErrorCode}</p> : null}</article>)}</div>
      {current.paymentSetupIssue ? <><InlineNotice tone="warning" title="환불 처리 정보에 확인할 문제가 있습니다" description="누락되거나 서로 맞지 않는 정보가 있어 별도 복구 검토가 필요합니다." /><p className="support-case-reference">{current.paymentSetupIssue.lastErrorCode}</p><ul>{[...(current.paymentSetupIssue.missingArtifacts ?? []), ...(current.paymentSetupIssue.invariantViolations ?? [])].map(issue => <li key={issue}>{issue}</li>)}</ul>{current.setupReprocessingCaseId ? <PaymentRepairWorkspace key={current.setupReprocessingCaseId} caseId={current.setupReprocessingCaseId} /> : null}</> : null}
      {canReconcile ? <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); void reconcile(); }}><TextField label="환불 결과 재확인 사유" value={reason} onValueChange={setReason} maxLength={500} disabled={busy} required /><p>기존 환불의 결과를 한 번 조회하도록 예약합니다.</p><Button type="submit" loading={busy} disabled={!reason.trim()}>기존 환불 결과 조회 예약</Button></form> : null}
      <Button variant="secondary" disabled={busy} onClick={detail.reload}>주문 후속 처리 다시 확인</Button>
    </> : null}
  </section>;
}
