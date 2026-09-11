import { PointCostIssuerPicker, type PointCostIssuerSelection } from "./PointCostIssuerPicker";
import { useCallback, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, InlineNotice, LoadingState, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { seoulInstant } from "../../lib/seoulDateTime";
import { CustomerPointTargetPicker, type PointCustomerSelection } from "./CustomerPointTargetPicker";
import { useResource } from "../shared/useResource";
const transactionLabels = { ACCRUAL: "적립", USE: "사용", EXPIRATION: "만료", RESTORE: "복원", COMPENSATION: "보상", GOODWILL_COMPENSATION: "고객 보상", RESTORE_SKIPPED_EXPIRED: "만료로 복원 생략", RECOVERY: "회수", ADJUSTMENT: "조정" };
const accessReason = "POINT_ACCOUNT_INVESTIGATION" as const;
type AdjustmentRequest = components["schemas"]["PointAdjustmentRequest"];
type PendingAdjustment = { accountId: string; body: AdjustmentRequest; key: string };

/** Select a server-returned customer in memory, then resolve their account before any point operation. */
export function PointAccountWorkspace() {
  const [customer, setCustomer] = useState<PointCustomerSelection | null>(null);
  const [locked, setLocked] = useState(false);
  return <section className="management-workspace"><h2>고객 포인트 조회·조정</h2>
    <CustomerPointTargetPicker value={customer} onValueChange={setCustomer} disabled={locked} />
    {customer ? <SelectedCustomerPoints key={customer.customerId} customerId={customer.customerId} onLockChange={setLocked} /> : null}
  </section>;
}

function SelectedCustomerPoints({ customerId, onLockChange }: { customerId: string; onLockChange: (locked: boolean) => void }) {
  const resolved = useResource(useCallback(async () => {
    const result = unwrap(await operationsApi.GET("/operations/customers/{customerId}/point-account", { params: { path: { customerId }, header: { "X-Access-Reason": accessReason } } }));
    if (result.customerId !== customerId || !result.accountId) throw new ApiRequestError(503, "POINT_ACCOUNT_INTEGRITY_FAILURE", "선택한 고객의 포인트 계정을 확인하지 못했습니다.");
    return result;
  }, [customerId]));
  if (resolved.state.status === "loading") return <LoadingState label="선택한 고객의 포인트 계정을 연결하는 중" />;
  if (resolved.state.status === "failed") return <ErrorState error={resolved.state.error} retry={resolved.reload} />;
  return <PointAccountDetails key={resolved.state.value.accountId} accountId={resolved.state.value.accountId} onLockChange={onLockChange} />;
}

function PointAccountDetails({ accountId, onLockChange }: { accountId: string; onLockChange: (locked: boolean) => void }) {
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const [amount, setAmount] = useState("");
  const [issuer, setIssuer] = useState<PointCostIssuerSelection | null>(null);
  const issuerType = issuer?.issuerType, issuerReference = issuer?.issuerReference ?? "";
  const [expiry, setExpiry] = useState("");
  const [reason, setReason] = useState("");
  const [evidence, setEvidence] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [validation, setValidation] = useState<string | null>(null);
  const [result, setResult] = useState<components["schemas"]["PointAdjustmentResult"] | null>(null);
  const intent = useRef(new SubmissionIntent());
  const [pending, setPending] = useState<PendingAdjustment | null>(null);
  const submitting = useRef(false);
  const locked = busy || pending !== null;
  const cursor = cursors.at(-1);
  const account = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/point-accounts/{accountId}", { params: { path: { accountId }, header: { "X-Access-Reason": accessReason } } })), [accountId]));
  const history = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/point-accounts/{accountId}/transactions", { params: { path: { accountId }, header: { "X-Access-Reason": accessReason }, query: { cursor, limit: 20 } } })), [accountId, cursor]));
  const evidenceReferences = evidence.split("\n").map(value => value.trim()).filter(Boolean);
  const amountKrw = Number(amount);
  const validAmount = amount.trim() !== "" && Number.isSafeInteger(amountKrw) && amountKrw !== 0;
  const validEvidence = evidenceReferences.length > 0 && evidenceReferences.length <= 20 && evidenceReferences.every(value => value.length <= 500);
  const ready = account.state.status === "ready" && account.state.value && history.state.status === "ready" && history.state.value;
  async function adjust() {
    if (submitting.current || pending || !ready || !validAmount || !validEvidence || !reason.trim()) return;
    let expiresAt: string | undefined;
    if (amountKrw > 0) {
      try { expiresAt = seoulInstant(expiry); } catch { setValidation("유효한 만료 날짜와 시각을 입력해 주세요."); return; }
      if (!issuerType || !issuerReference.trim() || Date.parse(expiresAt) <= Date.now()) { setValidation("비용 주체와 미래의 만료 시각을 입력해 주세요."); return; }
    }
    const body: components["schemas"]["PointAdjustmentRequest"] = { amountKrw, reason: reason.trim(), evidenceReferences, ...(amountKrw > 0 && issuerType ? { issuer: { issuerType, issuerReference: issuerReference.trim() }, expiresAt } : {}) };
    await submit({ accountId, body, key: intent.current.keyFor(JSON.stringify({ accountId, ...body })) });
  }
  async function submit(command: PendingAdjustment) {
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true); onLockChange(true); setFailure(null); setValidation(null); setResult(null);
    let unresolved = pending !== null;
    try {
      setResult(unwrap(await operationsApi.POST("/operations/point-accounts/{accountId}/adjustments", { params: { path: { accountId: command.accountId }, header: { "Idempotency-Key": command.key } }, body: command.body })));
      unresolved = false; setPending(null);
      intent.current.complete(); setAmount(""); setReason(""); setEvidence(""); setIssuer(null); setExpiry("");
    } catch (error) {
      setFailure(error);
      // A later denial cannot prove that an earlier request with a lost response did not commit.
      unresolved = unresolved || !(error instanceof ApiRequestError) || error.status >= 500 || error.status === 408 || error.code.startsWith("IDEMPOTENCY_");
      if (unresolved) setPending(command);
      else intent.current.complete();
    } finally {
      if (!unresolved) { account.reload(); if (cursor) setCursors([undefined]); else history.reload(); }
      submitting.current = false; setBusy(false); onLockChange(unresolved);
    }
  }
  return <div className="management-workspace">
    {pending ? <section className="surface-card management-card"><InlineNotice tone="warning" title="포인트 조정 결과를 확인해야 합니다" description="응답을 확인하지 못해 고객과 조정 내용을 잠갔습니다. 화면을 유지하고 같은 요청으로 결과를 확인해 주세요." /><Button loading={busy} onClick={() => { if (pending) void submit(pending); }}>같은 요청으로 결과 확인</Button></section> : null}
    {result ? <section className="surface-card management-card"><h3>포인트 조정을 적용했습니다</h3><p role="status">이번 조정 거래 {result.transactions.length}건</p><ul>{result.transactions.map(item => <li key={item.transactionId}><strong>{item.amountKrw > 0 ? "+" : ""}{item.amountKrw.toLocaleString("ko-KR")}P</strong><p className="support-case-reference">{item.sourceReference}</p></li>)}</ul></section> : null}
    {failure ? <ErrorState error={failure} /> : null}
    {account.state.status === "loading" ? <LoadingState label="포인트 계정을 불러오는 중" /> : account.state.status === "failed" ? <ErrorState error={account.state.error} retry={account.reload} /> : account.state.value ? <section className="surface-card management-card"><h3>{pending ? "마지막으로 조회한 포인트" : "현재 포인트"}</h3><dl className="detail-list"><div><dt>사용 가능</dt><dd>{account.state.value.availablePointsKrw.toLocaleString("ko-KR")}P</dd></div><div><dt>회수 대기</dt><dd>{account.state.value.recoveryPendingKrw.toLocaleString("ko-KR")}P</dd></div></dl></section> : null}
    {history.state.status === "loading" ? <LoadingState label="포인트 거래 내역을 불러오는 중" /> : history.state.status === "failed" ? <ErrorState error={history.state.error} retry={history.reload} /> : history.state.value ? <section className="management-workspace"><h3>포인트 거래 내역</h3>{history.state.value.items.length ? <div className="management-card-grid">{history.state.value.items.map(item => <article className="surface-card management-card" key={item.transactionId}><h4>{transactionLabels[item.type]}</h4><strong>{item.amountKrw > 0 ? "+" : ""}{item.amountKrw.toLocaleString("ko-KR")}P</strong><p>{fullDateTime.format(new Date(item.occurredAt))}</p><p className="support-case-reference">{item.sourceReference}</p></article>)}</div> : <EmptyState title="포인트 거래 내역이 없습니다" description="이 계정에 기록된 거래가 없습니다." />}<div className="button-row"><Button variant="ghost" disabled={locked || cursors.length < 2} onClick={() => setCursors(value => value.slice(0, -1))}>이전 포인트 거래</Button><Button variant="secondary" disabled={locked || !history.state.value.page.nextCursor} onClick={() => { if (history.state.status === "ready" && history.state.value?.page.nextCursor) { const next = history.state.value.page.nextCursor; setCursors(value => [...value, next]); } }}>다음 포인트 거래</Button></div></section> : null}
    {ready ? <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); void adjust(); }}><fieldset className="catalog-fieldset" disabled={locked}><legend>포인트 조정</legend><TextField label="조정 포인트" type="number" step="1" min={String(-Number.MAX_SAFE_INTEGER)} max={String(Number.MAX_SAFE_INTEGER)} value={amount} onValueChange={setAmount} description="양수는 추가, 음수는 차감입니다. 0은 입력할 수 없습니다." required />
      {amountKrw > 0 ? <div className="management-card-grid"><PointCostIssuerPicker purpose="ADJUSTMENT" label="추가 포인트 비용 주체" value={issuer} onValueChange={setIssuer} disabled={locked} /><TextField label="추가 포인트 만료 (한국 시간)" type="datetime-local" value={expiry} onValueChange={setExpiry} required /></div> : null}
      <TextField label="포인트 조정 사유" value={reason} onValueChange={setReason} maxLength={160} required /><TextAreaField label="포인트 조정 증빙 위치" value={evidence} onValueChange={setEvidence} description="한 줄에 하나씩 최대 20개, 각 500자까지 입력합니다." required />{validation ? <InlineNotice tone="danger" title="조정 입력을 확인해 주세요" description={validation} /> : null}<Button type="submit" loading={busy} disabled={!validAmount || !validEvidence || !reason.trim() || (amountKrw > 0 && (!issuerType || !issuerReference.trim() || !expiry))}>포인트 조정 적용</Button></fieldset></form> : null}
  </div>;
}
