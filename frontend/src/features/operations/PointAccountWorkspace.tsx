import { useCallback, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, InlineNotice, LoadingState, SelectField, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { seoulInstant } from "../../lib/seoulDateTime";
import { useResource } from "../shared/useResource";
const transactionLabels = { ACCRUAL: "적립", USE: "사용", EXPIRATION: "만료", RESTORE: "복원", COMPENSATION: "보상", RESTORE_SKIPPED_EXPIRED: "만료로 복원 생략", RECOVERY: "회수", ADJUSTMENT: "조정" };
/** Explicit audited account investigation and signed adjustment, preserving issuer and expiry ownership. */
export function PointAccountWorkspace() {
  const [accountId, setAccountId] = useState("");
  const [accessReason, setAccessReason] = useState("");
  const [query, setQuery] = useState<{ id: string; reason: string } | null>(null);
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const [amount, setAmount] = useState("");
  const [issuerType, setIssuerType] = useState<components["schemas"]["PointIssuer"]["issuerType"] | "">("");
  const [issuerReference, setIssuerReference] = useState("");
  const [expiry, setExpiry] = useState("");
  const [reason, setReason] = useState("");
  const [evidence, setEvidence] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [validation, setValidation] = useState<string | null>(null);
  const [result, setResult] = useState<components["schemas"]["PointAdjustmentResult"] | null>(null);
  const intent = useRef(new SubmissionIntent());
  const cursor = cursors.at(-1);
  const account = useResource(useCallback(async () => query ? unwrap(await operationsApi.GET("/operations/point-accounts/{accountId}", { params: { path: { accountId: query.id }, header: { "X-Access-Reason": query.reason } } })) : null, [query]));
  const history = useResource(useCallback(async () => query ? unwrap(await operationsApi.GET("/operations/point-accounts/{accountId}/transactions", { params: { path: { accountId: query.id }, header: { "X-Access-Reason": query.reason }, query: { cursor, limit: 20 } } })) : null, [query, cursor]));
  const evidenceReferences = evidence.split("\n").map(value => value.trim()).filter(Boolean);
  const amountKrw = Number(amount);
  const validAmount = amount.trim() !== "" && Number.isSafeInteger(amountKrw) && amountKrw !== 0;
  const validEvidence = evidenceReferences.length > 0 && evidenceReferences.length <= 20 && evidenceReferences.every(value => value.length <= 500);
  const ready = account.state.status === "ready" && account.state.value && history.state.status === "ready" && history.state.value;
  async function adjust() {
    if (busy || !query || !ready || !validAmount || !validEvidence || !reason.trim()) return;
    let expiresAt: string | undefined;
    if (amountKrw > 0) {
      try { expiresAt = seoulInstant(expiry); } catch { setValidation("유효한 만료 날짜와 시각을 입력해 주세요."); return; }
      if (!issuerType || !issuerReference.trim() || Date.parse(expiresAt) <= Date.now()) { setValidation("비용 주체와 미래의 만료 시각을 입력해 주세요."); return; }
    }
    const body: components["schemas"]["PointAdjustmentRequest"] = { amountKrw, reason: reason.trim(), evidenceReferences, ...(amountKrw > 0 && issuerType ? { issuer: { issuerType, issuerReference: issuerReference.trim() }, expiresAt } : {}) };
    setBusy(true); setFailure(null); setValidation(null); setResult(null);
    try {
      setResult(unwrap(await operationsApi.POST("/operations/point-accounts/{accountId}/adjustments", { params: { path: { accountId: query.id }, header: { "Idempotency-Key": intent.current.keyFor(JSON.stringify({ accountId: query.id, ...body })) } }, body })));
      intent.current.complete(); setAmount(""); setReason(""); setEvidence(""); setIssuerType(""); setIssuerReference(""); setExpiry("");
    } catch (error) { setFailure(error); }
    finally { account.reload(); if (cursor) setCursors([undefined]); else history.reload(); setBusy(false); }
  }
  return <section className="management-workspace"><h2>포인트 계정 조사·조정</h2>
    <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); setQuery({ id: accountId.trim(), reason: accessReason }); setCursors([undefined]); setResult(null); setFailure(null); setAmount(""); setReason(""); setEvidence(""); setIssuerType(""); setIssuerReference(""); setExpiry(""); setValidation(null); intent.current.complete(); }}><fieldset className="catalog-fieldset" disabled={busy}><legend>포인트 계정 조회</legend><TextField label="포인트 계정 ID" value={accountId} onValueChange={setAccountId} required /><SelectField label="포인트 계정 조회 사유" value={accessReason} onValueChange={setAccessReason}><option value="">조회 목적 선택</option><option value="POINT_ACCOUNT_INVESTIGATION">포인트 내역 조사</option></SelectField><Button type="submit" disabled={!accountId.trim() || !accessReason}>포인트 계정 조회</Button></fieldset></form>
    {result ? <section className="surface-card management-card"><h3>포인트 조정을 적용했습니다</h3><p role="status">이번 조정 거래 {result.transactions.length}건</p><ul>{result.transactions.map(item => <li key={item.transactionId}><strong>{item.amountKrw > 0 ? "+" : ""}{item.amountKrw.toLocaleString("ko-KR")}P</strong><p className="support-case-reference">{item.sourceReference}</p></li>)}</ul></section> : null}
    {failure ? <ErrorState error={failure} /> : null}
    {account.state.status === "loading" ? <LoadingState label="포인트 계정을 불러오는 중" /> : account.state.status === "failed" ? <ErrorState error={account.state.error} retry={account.reload} /> : account.state.value ? <section className="surface-card management-card"><h3>현재 포인트</h3><dl className="detail-list"><div><dt>사용 가능</dt><dd>{account.state.value.availablePointsKrw.toLocaleString("ko-KR")}P</dd></div><div><dt>회수 대기</dt><dd>{account.state.value.recoveryPendingKrw.toLocaleString("ko-KR")}P</dd></div></dl></section> : null}
    {query ? history.state.status === "loading" ? <LoadingState label="포인트 거래 내역을 불러오는 중" /> : history.state.status === "failed" ? <ErrorState error={history.state.error} retry={history.reload} /> : history.state.value ? <section className="management-workspace"><h3>포인트 거래 내역</h3>{history.state.value.items.length ? <div className="management-card-grid">{history.state.value.items.map(item => <article className="surface-card management-card" key={item.transactionId}><h4>{transactionLabels[item.type]}</h4><strong>{item.amountKrw > 0 ? "+" : ""}{item.amountKrw.toLocaleString("ko-KR")}P</strong><p>{fullDateTime.format(new Date(item.occurredAt))}</p><p className="support-case-reference">{item.sourceReference}</p></article>)}</div> : <EmptyState title="포인트 거래 내역이 없습니다" description="이 계정에 기록된 거래가 없습니다." />}<div className="button-row"><Button variant="ghost" disabled={busy || cursors.length < 2} onClick={() => setCursors(value => value.slice(0, -1))}>이전 포인트 거래</Button><Button variant="secondary" disabled={busy || !history.state.value.page.nextCursor} onClick={() => { if (history.state.status === "ready" && history.state.value?.page.nextCursor) { const next = history.state.value.page.nextCursor; setCursors(value => [...value, next]); } }}>다음 포인트 거래</Button></div></section> : null : null}
    {ready ? <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); void adjust(); }}><fieldset className="catalog-fieldset" disabled={busy}><legend>포인트 조정</legend><TextField label="조정 포인트" type="number" step="1" min={String(-Number.MAX_SAFE_INTEGER)} max={String(Number.MAX_SAFE_INTEGER)} value={amount} onValueChange={setAmount} description="양수는 추가, 음수는 차감입니다. 0은 입력할 수 없습니다." required />
      {amountKrw > 0 ? <div className="management-card-grid"><SelectField label="추가 포인트 비용 주체" value={issuerType} onValueChange={value => setIssuerType(value as typeof issuerType)}><option value="">비용 주체 선택</option><option value="PLATFORM">플랫폼</option><option value="BRAND">브랜드</option><option value="STORE">매장</option></SelectField><TextField label="추가 포인트 비용 주체 식별값" value={issuerReference} onValueChange={setIssuerReference} maxLength={200} required /><TextField label="추가 포인트 만료 (한국 시간)" type="datetime-local" value={expiry} onValueChange={setExpiry} required /></div> : null}
      <TextField label="포인트 조정 사유" value={reason} onValueChange={setReason} maxLength={160} required /><TextAreaField label="포인트 조정 증빙 위치" value={evidence} onValueChange={setEvidence} description="한 줄에 하나씩 최대 20개, 각 500자까지 입력합니다." required />{validation ? <InlineNotice tone="danger" title="조정 입력을 확인해 주세요" description={validation} /> : null}<Button type="submit" loading={busy} disabled={!validAmount || !validEvidence || !reason.trim() || (amountKrw > 0 && (!issuerType || !issuerReference.trim() || !expiry))}>포인트 조정 적용</Button></fieldset></form> : null}
  </section>;
}
