import { useCallback, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, LoadingState, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { seoulInstant } from "../../lib/seoulDateTime";
import { useResource } from "../shared/useResource";

type Terms = components["schemas"]["ManagedSettlementTermsSnapshot"];
/** Immutable contract history and explicit future interval registration for one selected store. */
export function StoreTermsWorkspace({ storeId }: { storeId: string }) {
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const cursor = cursors.at(-1);
  const list = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/stores/{storeId}/settlement-terms", { params: { path: { storeId }, query: { cursor, limit: 20 } } })), [storeId, cursor]));
  const [creating, setCreating] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  return <section className="management-workspace"><h3>정산 계약 버전</h3>
    <p>기존 계약은 변경하지 않습니다. 새 계약은 미래의 겹치지 않는 구간에만 등록할 수 있습니다.</p>
    {saved ? <p role="status">새 정산 계약을 등록했습니다.</p> : null}
    {list.state.status === "loading" ? <LoadingState label="정산 계약을 불러오는 중" /> : list.state.status === "failed" ? <ErrorState error={list.state.error} retry={list.reload} /> : <>
      <Button variant="secondary" disabled={busy} onClick={() => { setCreating(true); setSaved(false); }}>새 정산 계약</Button>
      {creating ? <TermsForm key={list.state.value.revision} storeId={storeId} revision={list.state.value.revision} onBusy={setBusy} onSaved={id => { setCreating(false); setSaved(true); setSelected(id); list.reload(); }} onClose={() => setCreating(false)} onRefresh={() => { setCreating(false); list.reload(); }} /> : null}
      {list.state.value.items.length ? <ul className="menu-authoring-list">{list.state.value.items.map(terms => <li key={terms.termsVersionId}><TermsSummary terms={terms} /><Button variant="ghost" disabled={busy} aria-label={`${terms.sourceReference} 상세`} onClick={() => setSelected(terms.termsVersionId)}>상세</Button></li>)}</ul> : <EmptyState title="등록된 정산 계약이 없습니다" description="승인된 계약 내용을 확인해 미래 적용 버전을 등록해 주세요." />}
      <div className="button-row"><Button variant="ghost" disabled={busy || cursors.length < 2} onClick={() => setCursors(value => value.slice(0, -1))}>이전 정산 계약</Button><Button variant="secondary" disabled={busy || !list.state.value.nextCursor} onClick={() => { if (list.state.status === "ready" && list.state.value.nextCursor) { const next = list.state.value.nextCursor; setCursors(value => [...value, next]); } }}>다음 정산 계약</Button></div>
    </>}
    {selected ? <TermsDetail key={selected} storeId={storeId} termsVersionId={selected} /> : null}
  </section>;
}
function TermsSummary({ terms }: { terms: Terms }) {
  return <div><strong className="support-case-reference">{terms.sourceReference}</strong><p>{(terms.feeRateBps / 100).toFixed(2)}%</p><p>{fullDateTime.format(new Date(terms.effectiveFrom))}부터<br />{terms.effectiveTo ? `${fullDateTime.format(new Date(terms.effectiveTo))} 전까지` : "종료일 없음"}</p></div>;
}
function TermsDetail({ storeId, termsVersionId }: { storeId: string; termsVersionId: string }) {
  const detail = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/stores/{storeId}/settlement-terms/{termsVersionId}", { params: { path: { storeId, termsVersionId } } })), [storeId, termsVersionId]));
  return <section className="surface-card management-card"><h4>계약 상세</h4>{detail.state.status === "loading" ? <LoadingState label="계약 상세를 불러오는 중" /> : detail.state.status === "failed" ? <ErrorState error={detail.state.error} retry={detail.reload} /> : <><TermsSummary terms={detail.state.value.terms} /><p className="support-case-reference">계약 버전 {detail.state.value.terms.termsVersionId}</p><p>등록 이력 {detail.state.value.revision}건</p></>}</section>;
}
function TermsForm({ storeId, revision, onSaved, onClose, onRefresh, onBusy }: { storeId: string; revision: number; onSaved: (id: string) => void; onClose: () => void; onRefresh: () => void; onBusy: (busy: boolean) => void }) {
  const [source, setSource] = useState(""); const [rate, setRate] = useState(""); const [from, setFrom] = useState(""); const [to, setTo] = useState(""); const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false); const [failure, setFailure] = useState<unknown>(null); const [invalid, setInvalid] = useState("");
  const intent = useRef(new SubmissionIntent());
  async function register() {
    if (busy) return;
    setFailure(null); setInvalid("");
    let effectiveFrom: string; let effectiveTo: string | null;
    const feeRateBps = Math.round(Number(rate) * 100);
    try {
      effectiveFrom = seoulInstant(from); effectiveTo = to ? seoulInstant(to) : null;
      if (Date.parse(effectiveFrom) <= Date.now() || (effectiveTo && effectiveTo <= effectiveFrom)) throw new Error("future interval");
      if (!rate || !Number.isSafeInteger(feeRateBps) || feeRateBps < 0 || feeRateBps > 10000) throw new Error("rate");
    } catch { setInvalid("적용 시작은 미래여야 하며 종료는 시작보다 늦어야 합니다. 수수료율은 0~100%로 입력해 주세요."); return; }
    const body = { sourceReference: source.trim(), feeRateBps, effectiveFrom, effectiveTo, expectedRevision: revision, reason: reason.trim() };
    setBusy(true); onBusy(true);
    try { const result = unwrap(await operationsApi.POST("/operations/stores/{storeId}/settlement-terms", { params: { path: { storeId }, header: { "Idempotency-Key": intent.current.keyFor(JSON.stringify({ storeId, ...body })) } }, body })); intent.current.complete(); onSaved(result.terms.termsVersionId); }
    catch (error) { setFailure(error); } finally { setBusy(false); onBusy(false); }
  }
  return <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); void register(); }}><fieldset disabled={busy} className="catalog-fieldset"><legend>미래 정산 계약 등록</legend>
    <TextField label="계약 참조번호" value={source} onValueChange={setSource} maxLength={240} required />
    <TextField label="수수료율 (%)" type="number" min="0" max="100" step="0.01" value={rate} onValueChange={setRate} required />
    <div className="management-card-grid"><TextField label="적용 시작 (한국 시간)" type="datetime-local" value={from} onValueChange={setFrom} required /><TextField label="적용 종료 (한국 시간)" type="datetime-local" value={to} onValueChange={setTo} description="비워 두면 종료일 없는 계약입니다. 이후 계약이 이 구간과 겹치면 등록할 수 없습니다." /></div>
    <TextAreaField label="정산 계약 등록 사유" value={reason} onValueChange={setReason} maxLength={500} required />
    {invalid ? <p role="alert">{invalid}</p> : null}{failure ? <ErrorState error={failure} /> : null}
    <div className="button-row"><Button type="submit" loading={busy} disabled={!source.trim() || !reason.trim()}>정산 계약 등록</Button><Button type="button" variant="secondary" onClick={onRefresh}>현재 계약 목록 다시 읽기</Button><Button type="button" variant="ghost" onClick={onClose}>계약 등록 닫기</Button></div>
  </fieldset></form>;
}
