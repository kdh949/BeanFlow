import { PointCostIssuerPicker, currentPolicyIssuer, pointIssuerTypeLabels, type PointCostIssuerSelection } from "./PointCostIssuerPicker";
import { useCallback, useRef, useState } from "react";
import type { components, operations } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, LoadingState, SelectField, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
import { PointPolicyHistory } from "./PointPolicyHistory";

type Policy = components["schemas"]["StoreOrdinaryPointAccrualPolicy"];
type Snapshot = components["schemas"]["OrdinaryPointAccrualPolicySnapshot"];
type PendingPolicy = { body: operations["changeStoreOrdinaryPointAccrualPolicy"]["requestBody"]["content"]["application/json"]; key: string };
const sources = { STORE_OVERRIDE: "매장 전용 정책 적용", GLOBAL_INHERITED: "명시적으로 공통 정책 상속", GLOBAL_NO_OVERRIDE: "별도 설정 없이 공통 정책 적용" };
/** Displays the explicit head separately from the policy selected by the server. */
export function StorePointPolicyWorkspace({ storeId, onLockChange }: { storeId: string; /** Locks the enclosing target picker until the command result is known. */ onLockChange?: (locked: boolean) => void }) {
  const [locked, setLocked] = useState(false);
  const [reason, setReason] = useState(""); const [request, setRequest] = useState<{ reason: string } | null>(null); const [saved, setSaved] = useState(false); const [revision, setRevision] = useState(0);
  const current = useResource(useCallback(async () => request ? unwrap(await operationsApi.GET("/operations/policies/ordinary-point-accrual/stores/{storeId}", { params: { path: { storeId }, header: { "X-Access-Reason": request.reason } } })) : null, [storeId, request]));
  return <section className="management-workspace"><h3>매장 포인트 정책</h3>
    <div className="button-row"><SelectField label="매장 포인트 조회 사유" value={reason} onValueChange={setReason}><option value="">조회 목적 선택</option><option value="POLICY_CHANGE_REVIEW">정책 변경 전 확인</option><option value="POLICY_AUDIT_REVIEW">정책 감사</option></SelectField><Button variant="secondary" disabled={locked || !reason} onClick={() => { setRequest({ reason }); setSaved(false); }}>현재 매장 포인트 정책 조회</Button></div>
    {saved ? <p role="status">매장 포인트 정책을 저장했습니다. 이후 생성되는 주문에 적용합니다.</p> : null}
    {current.state.status === "loading" ? <LoadingState label="현재 매장 포인트 정책을 불러오는 중" /> : current.state.status === "failed" ? <ErrorState error={current.state.error} retry={current.reload} /> : current.state.value ? <>
      <section className="surface-card management-card"><h4>{sources[current.state.value.selectionSource]}</h4><p>실제 적립률 {(current.state.value.effectivePolicy.accrualRateBps / 100).toFixed(2)}%</p><dl className="detail-list"><div><dt>적용 버전</dt><dd>{current.state.value.effectivePolicy.policyVersionId}</dd></div><div><dt>반올림</dt><dd>{current.state.value.effectivePolicy.roundingMode === "FLOOR" ? "버림" : "반올림"}</dd></div><div><dt>비용 주체</dt><dd>{pointIssuerTypeLabels[current.state.value.effectivePolicy.issuerType]} · 정책 버전 {current.state.value.effectivePolicy.policyVersionId}의 비용 주체</dd></div><div><dt>유효기간</dt><dd>{current.state.value.effectivePolicy.validityDays}일 · {current.state.value.effectivePolicy.expiryRule === "EXACT_DURATION_FROM_COMPLETION" ? "정확한 시간" : "서울 달력일"}</dd></div></dl></section>
      <PolicyForm key={`${current.state.value.explicitHead?.policyVersionId ?? "none"}:${current.state.value.effectivePolicy.policyVersionId}`} storeId={storeId} current={current.state.value} onSaved={() => { setSaved(true); setRevision(value => value + 1); current.reload(); }} onRefresh={current.reload} onLockChange={value => { setLocked(value); onLockChange?.(value); }} />
    </> : null}
    <PointPolicyHistory key={revision} storeId={storeId} />
  </section>;
}
function PolicyForm({ storeId, current, onSaved, onRefresh, onLockChange }: { storeId: string; current: Policy; onSaved: () => void; onRefresh: () => void; onLockChange: (locked: boolean) => void }) {
  const [state, setState] = useState<"OVERRIDE" | "INHERIT_GLOBAL">(current.explicitHead?.state ?? "INHERIT_GLOBAL");
  const [rate, setRate] = useState(String(current.effectivePolicy.accrualRateBps / 100));
  const [rounding, setRounding] = useState(current.effectivePolicy.roundingMode);
  const [issuer, setIssuer] = useState<PointCostIssuerSelection | null>(() => currentPolicyIssuer(current.effectivePolicy));
  const [expiry, setExpiry] = useState(current.effectivePolicy.expiryRule);
  const [days, setDays] = useState(String(current.effectivePolicy.validityDays));
  const [reason, setReason] = useState(""); const [busy, setBusy] = useState(false); const [failure, setFailure] = useState<unknown>(null);
  const intent = useRef(new SubmissionIntent());
  const submitting = useRef(false);
  const [pending, setPending] = useState<PendingPolicy | null>(null);
  async function save() {
    if (busy || pending || (state === "OVERRIDE" && !issuer)) return;
    const common = { expectedPolicyVersionId: current.explicitHead?.policyVersionId, reason: reason.trim() };
    const body = state === "INHERIT_GLOBAL" ? { ...common, state } : { ...common, state, accrualRateBps: Math.round(Number(rate) * 100), roundingMode: rounding, issuerType: issuer!.issuerType, issuerReference: issuer!.issuerReference, expiryRule: expiry, validityDays: Number(days) };
    await submit({ body, key: intent.current.keyFor(JSON.stringify({ storeId, ...body })) });
  }
  async function submit(command: PendingPolicy) {
    if (submitting.current) return;
    submitting.current = true; setBusy(true); onLockChange(true); setFailure(null);
    let unresolved = pending !== null;
    try {
      unwrap(await operationsApi.PATCH("/operations/policies/ordinary-point-accrual/stores/{storeId}", { params: { path: { storeId }, header: { "Idempotency-Key": command.key } }, body: command.body }));
      unresolved = false; setPending(null); intent.current.complete(); onSaved();
    } catch (error) {
      setFailure(error);
      const terminal = error instanceof ApiRequestError && (error.code === "IDEMPOTENCY_KEY_REUSED" || error.code === "IDEMPOTENCY_MANUAL_REVIEW_REQUIRED");
      unresolved = !terminal && (unresolved || !(error instanceof ApiRequestError) || error.status >= 500 || error.status === 408 || error.code === "IDEMPOTENCY_REQUEST_IN_PROGRESS");
      if (unresolved) setPending(command); else { setPending(null); intent.current.complete(); }
    } finally { submitting.current = false; setBusy(false); onLockChange(unresolved); }
  }
  return <>{pending ? <section className="surface-card management-card"><p role="status">정책 변경 응답을 확인하지 못했습니다. 선택한 매장과 변경 내용을 유지하고 같은 요청의 결과를 확인해 주세요.</p><Button loading={busy} onClick={() => { if (pending) void submit(pending); }}>같은 정책 변경 결과 확인</Button></section> : null}<form className="surface-card management-card" onSubmit={event => { event.preventDefault(); void save(); }}><fieldset className="catalog-fieldset" disabled={busy || pending !== null}><legend>매장 정책 변경</legend>
    <SelectField label="매장 포인트 적용 방식" value={state} onValueChange={value => setState(value as typeof state)}><option value="INHERIT_GLOBAL">공통 정책 상속</option><option value="OVERRIDE">매장 전용 정책</option></SelectField>
    {state === "OVERRIDE" ? <div className="management-card-grid">
      <TextField label="매장 적립률 (%)" type="number" min="0" max="100" step="0.01" value={rate} onValueChange={setRate} required />
      <SelectField label="매장 적립 반올림" value={rounding} onValueChange={value => setRounding(value as Snapshot["roundingMode"])}><option value="FLOOR">버림</option><option value="HALF_UP">반올림</option></SelectField>
      <PointCostIssuerPicker purpose="POLICY" value={issuer} onValueChange={setIssuer} disabled={busy || pending !== null} />
      <SelectField label="매장 포인트 만료 계산" value={expiry} onValueChange={value => setExpiry(value as Snapshot["expiryRule"])}><option value="SEOUL_CALENDAR_DAYS_FROM_COMPLETION">서울 달력일</option><option value="EXACT_DURATION_FROM_COMPLETION">정확한 시간</option></SelectField>
      <TextField label="매장 포인트 유효일수" type="number" min="1" max="3650" step="1" value={days} onValueChange={setDays} required />
    </div> : <p>앞으로 생성되는 주문은 그 시점의 공통 정책을 적용합니다. 이미 생성된 주문의 적립 조건은 바뀌지 않습니다.</p>}
    <TextAreaField label="매장 포인트 변경 사유" value={reason} onValueChange={setReason} maxLength={500} required />
    {failure ? <ErrorState error={failure} /> : null}
    <div className="button-row"><Button type="submit" loading={busy} disabled={!reason.trim() || (state === "OVERRIDE" && !issuer)}>매장 포인트 정책 저장</Button><Button type="button" variant="secondary" onClick={onRefresh}>현재 매장 정책 다시 읽기</Button></div>
  </fieldset></form></>;
}
