import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, InlineNotice, LoadingState, SelectField, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { seoulInstant } from "../../lib/seoulDateTime";
import { CustomerPointTargetPicker, type PointCustomerSelection } from "./CustomerPointTargetPicker";
import { useResource } from "../shared/useResource";
const transactionLabels = { ACCRUAL: "적립", USE: "사용", EXPIRATION: "만료", RESTORE: "복원", COMPENSATION: "보상", GOODWILL_COMPENSATION: "고객 보상", RESTORE_SKIPPED_EXPIRED: "만료로 복원 생략", RECOVERY: "회수", ADJUSTMENT: "조정" };
const accessReason = "POINT_ACCOUNT_INVESTIGATION" as const;
type AdjustmentRequest = components["schemas"]["PointAdjustmentRequest"];
type Preparation = components["schemas"]["PointAdjustmentPreparationView"];
type Adjust = (accountId: string, request: AdjustmentRequest) => Promise<void>;

/** Resolve the actor's durable preparation before opening a new financial command. */
export function PointAccountWorkspace() {
  const [customer, setCustomer] = useState<PointCustomerSelection | null>(null);
  const [preparation, setPreparation] = useState<Preparation | null>(null);
  const [recovered, setRecovered] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [needsRecovery, setNeedsRecovery] = useState(false);
  const [revision, setRevision] = useState(0);
  const inFlight = useRef(false);
  const mounted = useRef(false);
  const locked = busy || preparation !== null || needsRecovery;

  async function recover() {
    const current = unwrap(await operationsApi.GET("/operations/point-adjustment-preparations/current"));
    if (!mounted.current) return;
    setPreparation(current.preparation);
    if (current.preparation) setCustomer(current.preparation.customer);
    setNeedsRecovery(false); setRecovered(true); setRevision(value => value + 1);
  }
  useEffect(() => {
    mounted.current = true;
    void recover().catch(error => { if (mounted.current) { setFailure(error); setNeedsRecovery(true); } });
    return () => { mounted.current = false; };
  }, []);

  async function run(work: () => Promise<void>) {
    if (inFlight.current) return;
    inFlight.current = true; setBusy(true); setFailure(null);
    try { await work(); }
    catch (error) { if (mounted.current) setFailure(error); }
    finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  }
  async function apply(saved: Preparation) {
    if (saved.state === "APPLIED") return;
    const result = unwrap(await operationsApi.POST("/operations/point-accounts/{accountId}/adjustments", {
      params: { path: { accountId: saved.accountId }, header: { "Idempotency-Key": saved.preparationId } }, body: saved.request,
    }));
    if (mounted.current) { setPreparation({ ...saved, state: "APPLIED", canExecute: false, result }); setRevision(value => value + 1); }
  }
  const prepare: Adjust = async (accountId, request) => {
    if (locked || !recovered) return;
    await run(async () => {
      let saved: Preparation;
      try { saved = unwrap(await operationsApi.POST("/operations/point-adjustment-preparations", { body: { accountId, request } })); }
      catch (error) { if (mounted.current) setNeedsRecovery(true); throw error; }
      // Do not execute from a screen that was left during preparation; reentry recovers the same server record.
      if (!mounted.current) return;
      setPreparation(saved); setCustomer(saved.customer);
      if (saved.canExecute) await apply(saved);
    });
  };
  async function dismiss(saved: Preparation) {
    await run(async () => {
      setNeedsRecovery(true);
      const response = await operationsApi.DELETE("/operations/point-adjustment-preparations/{preparationId}", {
        params: { path: { preparationId: saved.preparationId } }, body: { expectedState: saved.state },
      });
      if (response.error) {
        // Concurrent apply may have committed before cancellation. Fetch and display that result.
        if (response.response.status === 409) { await recover(); return; }
        setNeedsRecovery(true); unwrap(response); return;
      }
      await recover();
    });
  }
  return <section className="management-workspace"><h2>고객 포인트 조회·조정</h2>
    {failure ? <ErrorState error={failure} /> : null}
    {!recovered && !failure ? <LoadingState label="미확인 포인트 조정을 확인하는 중" /> : null}
    {needsRecovery ? <section className="surface-card management-card"><InlineNotice tone="warning" title="저장된 조정을 확인해야 합니다" description="확인이 끝날 때까지 새 조정을 시작할 수 없습니다. 다시 조회하거나 나중에 이 화면에서 이어서 처리해 주세요." /><Button loading={busy} onClick={() => void run(recover)}>저장된 조정 확인</Button></section> : null}
    {recovered ? <>
      <CustomerPointTargetPicker value={customer} onValueChange={setCustomer} disabled={locked} />
      {preparation ? <section className="surface-card management-card">
        {preparation.state === "APPLIED" ? <><h3>포인트 조정을 적용했습니다</h3><p role="status">이번 조정 거래 {preparation.result?.transactions.length}건</p></> : <InlineNotice tone="warning" title="포인트 조정 결과를 확인해야 합니다" description="고객과 조정 내용을 저장했습니다. 화면을 다시 열어도 같은 요청으로 이어서 확인할 수 있습니다. 취소할 때 이미 적용된 조정은 취소되지 않고 결과가 표시됩니다." />}
        <dl className="detail-list"><div><dt>조정 포인트</dt><dd>{preparation.request.amountKrw.toLocaleString("ko-KR")}P</dd></div><div><dt>사유</dt><dd>{preparation.request.reason}</dd></div>{preparation.request.expiresAt ? <div><dt>만료</dt><dd>{fullDateTime.format(new Date(preparation.request.expiresAt))}</dd></div> : null}</dl>
        {preparation.request.issuer ? <p>비용 주체: {{ PLATFORM: "플랫폼", BRAND: "브랜드", STORE: "매장" }[preparation.request.issuer.issuerType]} · {preparation.request.issuer.issuerReference}</p> : null}
        <ul>{preparation.request.evidenceReferences.map(value => <li key={value} className="support-case-reference">{value}</li>)}</ul>
        <div className="button-row">{preparation.state === "PREPARED" ? <><Button loading={busy} disabled={!preparation.canExecute || needsRecovery} onClick={() => void run(() => apply(preparation))}>같은 요청으로 결과 확인</Button><Button variant="secondary" disabled={busy || needsRecovery} onClick={() => void dismiss(preparation)}>미적용 조정 취소</Button></> : <Button loading={busy} disabled={needsRecovery} onClick={() => void dismiss(preparation)}>확인하고 새 조정</Button>}</div>
      </section> : null}
      {customer ? <SelectedCustomerPoints key={customer.customerId} customerId={customer.customerId} locked={locked} onAdjust={prepare} revision={revision} /> : null}
    </> : null}
  </section>;
}

function SelectedCustomerPoints({ customerId, locked, onAdjust, revision }: { customerId: string; locked: boolean; onAdjust: Adjust; revision: number }) {
  const resolved = useResource(useCallback(async () => {
    const result = unwrap(await operationsApi.GET("/operations/customers/{customerId}/point-account", { params: { path: { customerId }, header: { "X-Access-Reason": accessReason } } }));
    if (result.customerId !== customerId || !result.accountId) throw new ApiRequestError(503, "POINT_ACCOUNT_INTEGRITY_FAILURE", "선택한 고객의 포인트 계정을 확인하지 못했습니다.");
    return result;
  }, [customerId]));
  if (resolved.state.status === "loading") return <LoadingState label="선택한 고객의 포인트 계정을 연결하는 중" />;
  if (resolved.state.status === "failed") return <ErrorState error={resolved.state.error} retry={resolved.reload} />;
  return <PointAccountDetails key={`${resolved.state.value.accountId}:${revision}`} accountId={resolved.state.value.accountId} locked={locked} onAdjust={onAdjust} revision={revision} />;
}

function PointAccountDetails({ accountId, locked, onAdjust, revision }: { accountId: string; locked: boolean; onAdjust: Adjust; revision: number }) {
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const [amount, setAmount] = useState("");
  const [issuerType, setIssuerType] = useState<components["schemas"]["PointIssuer"]["issuerType"] | "">("");
  const [issuerReference, setIssuerReference] = useState("");
  const [expiry, setExpiry] = useState("");
  const [reason, setReason] = useState("");
  const [evidence, setEvidence] = useState("");
  const [validation, setValidation] = useState<string | null>(null);
  const cursor = cursors.at(-1);
  const account = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/point-accounts/{accountId}", { params: { path: { accountId }, header: { "X-Access-Reason": accessReason } } })), [accountId, revision]));
  const history = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/point-accounts/{accountId}/transactions", { params: { path: { accountId }, header: { "X-Access-Reason": accessReason }, query: { cursor, limit: 20 } } })), [accountId, cursor, revision]));
  const evidenceReferences = evidence.split("\n").map(value => value.trim()).filter(Boolean);
  const amountKrw = Number(amount);
  const validAmount = amount.trim() !== "" && Number.isSafeInteger(amountKrw) && amountKrw !== 0;
  const validEvidence = evidenceReferences.length > 0 && evidenceReferences.length <= 20 && evidenceReferences.every(value => value.length <= 500);
  const ready = account.state.status === "ready" && account.state.value && history.state.status === "ready" && history.state.value;
  async function adjust() {
    if (locked || !ready || !validAmount || !validEvidence || !reason.trim()) return;
    let expiresAt: string | undefined;
    if (amountKrw > 0) {
      try { expiresAt = seoulInstant(expiry); } catch { setValidation("유효한 만료 날짜와 시각을 입력해 주세요."); return; }
      if (!issuerType || !issuerReference.trim() || Date.parse(expiresAt) <= Date.now()) { setValidation("비용 주체와 미래의 만료 시각을 입력해 주세요."); return; }
    }
    const body: components["schemas"]["PointAdjustmentRequest"] = { amountKrw, reason: reason.trim(), evidenceReferences, ...(amountKrw > 0 && issuerType ? { issuer: { issuerType, issuerReference: issuerReference.trim() }, expiresAt } : {}) };
    await onAdjust(accountId, body);
  }
  return <div className="management-workspace">
    {account.state.status === "loading" ? <LoadingState label="포인트 계정을 불러오는 중" /> : account.state.status === "failed" ? <ErrorState error={account.state.error} retry={account.reload} /> : account.state.value ? <section className="surface-card management-card"><h3>{locked ? "조회한 포인트" : "현재 포인트"}</h3><dl className="detail-list"><div><dt>사용 가능</dt><dd>{account.state.value.availablePointsKrw.toLocaleString("ko-KR")}P</dd></div><div><dt>회수 대기</dt><dd>{account.state.value.recoveryPendingKrw.toLocaleString("ko-KR")}P</dd></div></dl></section> : null}
    {history.state.status === "loading" ? <LoadingState label="포인트 거래 내역을 불러오는 중" /> : history.state.status === "failed" ? <ErrorState error={history.state.error} retry={history.reload} /> : history.state.value ? <section className="management-workspace"><h3>포인트 거래 내역</h3>{history.state.value.items.length ? <div className="management-card-grid">{history.state.value.items.map(item => <article className="surface-card management-card" key={item.transactionId}><h4>{transactionLabels[item.type]}</h4><strong>{item.amountKrw > 0 ? "+" : ""}{item.amountKrw.toLocaleString("ko-KR")}P</strong><p>{fullDateTime.format(new Date(item.occurredAt))}</p><p className="support-case-reference">{item.sourceReference}</p></article>)}</div> : <EmptyState title="포인트 거래 내역이 없습니다" description="이 계정에 기록된 거래가 없습니다." />}<div className="button-row"><Button variant="ghost" disabled={locked || cursors.length < 2} onClick={() => setCursors(value => value.slice(0, -1))}>이전 포인트 거래</Button><Button variant="secondary" disabled={locked || !history.state.value.page.nextCursor} onClick={() => { if (history.state.status === "ready" && history.state.value?.page.nextCursor) { const next = history.state.value.page.nextCursor; setCursors(value => [...value, next]); } }}>다음 포인트 거래</Button></div></section> : null}
    {ready ? <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); void adjust(); }}><fieldset className="catalog-fieldset" disabled={locked}><legend>포인트 조정</legend><TextField label="조정 포인트" type="number" step="1" min={String(-Number.MAX_SAFE_INTEGER)} max={String(Number.MAX_SAFE_INTEGER)} value={amount} onValueChange={setAmount} description="양수는 추가, 음수는 차감입니다. 0은 입력할 수 없습니다." required />
      {amountKrw > 0 ? <div className="management-card-grid"><SelectField label="추가 포인트 비용 주체" value={issuerType} onValueChange={value => setIssuerType(value as typeof issuerType)}><option value="">비용 주체 선택</option><option value="PLATFORM">플랫폼</option><option value="BRAND">브랜드</option><option value="STORE">매장</option></SelectField><TextField label="추가 포인트 비용 주체 식별값" value={issuerReference} onValueChange={setIssuerReference} maxLength={200} required /><TextField label="추가 포인트 만료 (한국 시간)" type="datetime-local" value={expiry} onValueChange={setExpiry} required /></div> : null}
      <TextField label="포인트 조정 사유" value={reason} onValueChange={setReason} maxLength={160} required /><TextAreaField label="포인트 조정 증빙 위치" value={evidence} onValueChange={setEvidence} description="한 줄에 하나씩 최대 20개, 각 500자까지 입력합니다." required />{validation ? <InlineNotice tone="danger" title="조정 입력을 확인해 주세요" description={validation} /> : null}<Button type="submit" disabled={locked || !validAmount || !validEvidence || !reason.trim() || (amountKrw > 0 && (!issuerType || !issuerReference.trim() || !expiry))}>포인트 조정 적용</Button></fieldset></form> : null}
  </div>;
}
