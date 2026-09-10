import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, LoadingState, PageHeading, SelectField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime, won } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { DisputeManagementPanel } from "../shared/DisputeManagementPanel";

type State = components["schemas"]["DisputeManagementResponse"]["state"];
const states: Array<{ value: State | ""; label: string }> = [{ value: "", label: "전체" }, { value: "FILED", label: "접수" }, { value: "UNDER_REVIEW", label: "검토 중" }, { value: "ACCEPTED", label: "인정" }, { value: "REJECTED", label: "기각" }, { value: "WITHDRAWN", label: "철회" }];
/** Store-scoped list and decision workflow; no cross-store queue is invented. */
export function OperationsControlPage() {
  const [storeId, setStoreId] = useState("");
  const [filter, setFilter] = useState<State | "">("");
  const [query, setQuery] = useState<{ storeId: string; state?: State; cursor?: string } | null>(null);
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const [selected, setSelected] = useState<string | null>(null);
  const list = useResource(useCallback(async () => query ? unwrap(await operationsApi.GET("/operations/settlement-disputes", { params: { query: { ...query, limit: 20 } } })) : null, [query]));
  const loadDetail = useCallback(async () => unwrap(await operationsApi.GET("/operations/settlement-disputes/{disputeId}", { params: { path: { disputeId: selected! } } })), [selected]);
  return <div className="console-page"><PageHeading title="정산 이의제기 운영" />
    {selected ? <><Button variant="ghost" onClick={() => { setSelected(null); list.reload(); }}>이의제기 검색 결과로</Button><DisputeManagementPanel key={selected} audience="operations" load={loadDetail} command={async (operation, body, key) => {
      const params = { path: { disputeId: selected }, header: { "Idempotency-Key": key } };
      if (operation === "REVIEW") return unwrap(await operationsApi.POST("/operations/settlement-disputes/{disputeId}/reviews", { params, body }));
      if (operation !== "ACCEPTED" && operation !== "REJECTED") throw new Error("Unsupported operator decision");
      return unwrap(await operationsApi.POST("/operations/settlement-disputes/{disputeId}/decisions", { params, body: { ...body, outcome: operation } }));
    }} /></> : <>
      <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); setCursors([undefined]); setQuery({ storeId: storeId.trim(), state: filter || undefined }); }}><div className="management-card-grid"><TextField label="이의제기 매장 ID" value={storeId} onValueChange={setStoreId} required /><SelectField label="이의제기 상태" value={filter} onValueChange={value => setFilter(value as typeof filter)}>{states.map(state => <option key={state.value} value={state.value}>{state.label}</option>)}</SelectField></div><Button type="submit" disabled={!storeId.trim()}>이의제기 조회</Button></form>
      {list.state.status === "loading" ? <LoadingState label="이의제기를 불러오는 중" /> : list.state.status === "failed" ? <ErrorState error={list.state.error} retry={list.reload} /> : list.state.value ? <>
        {list.state.value.items.length ? <div className="management-card-grid">{list.state.value.items.map(dispute => <article className="surface-card management-card" key={dispute.disputeId}><h2>{won.format(dispute.expectedAdjustmentKrw)}</h2><StatusText domain="dispute" state={dispute.state} /><p className="support-case-reference">{dispute.disputeId}</p><p>접수 {fullDateTime.format(new Date(dispute.filedAt))}</p><Button variant="secondary" onClick={() => setSelected(dispute.disputeId)}>이의제기 상세</Button></article>)}</div> : <EmptyState title="조건에 맞는 이의제기가 없습니다" description="선택한 매장과 상태의 접수 내역이 없습니다." />}
        <div className="button-row"><Button variant="ghost" disabled={cursors.length < 2} onClick={() => { const next = cursors.slice(0, -1); setCursors(next); setQuery({ ...query!, cursor: next.at(-1) }); }}>이전 이의제기</Button><Button variant="secondary" disabled={!list.state.value.page.nextCursor} onClick={() => { if (list.state.status === "ready" && list.state.value?.page.nextCursor) { const cursor = list.state.value.page.nextCursor; setCursors(value => [...value, cursor]); setQuery({ ...query!, cursor }); } }}>다음 이의제기</Button></div>
      </> : null}
    </>}
  </div>;
}
