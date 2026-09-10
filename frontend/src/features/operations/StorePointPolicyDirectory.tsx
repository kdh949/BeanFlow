import { useCallback, useState } from "react";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, LoadingState, SelectField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { StorePointPolicyWorkspace } from "./StorePointPolicyWorkspace";

/** Explicit heads are not a complete store directory: headless stores can be opened by known ID. */
export function StorePointPolicyDirectory() {
  const [reason, setReason] = useState(""); const [state, setState] = useState<"" | "OVERRIDE" | "INHERIT_GLOBAL">(""); const [storeId, setStoreId] = useState(""); const [selected, setSelected] = useState<string | null>(null);
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const [request, setRequest] = useState<{ reason: string; state?: "OVERRIDE" | "INHERIT_GLOBAL"; cursor?: string } | null>(null);
  const list = useResource(useCallback(async () => request ? unwrap(await operationsApi.GET("/operations/policies/ordinary-point-accrual/stores", { params: { header: { "X-Access-Reason": request.reason }, query: { state: request.state, cursor: request.cursor, limit: 20 } } })) : null, [request]));
  return <section className="management-workspace"><h2>매장별 포인트 설정</h2><p>이 목록은 별도로 저장된 설정이 있는 매장만 포함합니다. 설정이 없는 매장은 공통 정책을 사용하며 매장 ID로 조회할 수 있습니다.</p>
    <form className="button-row" onSubmit={event => { event.preventDefault(); setSelected(storeId.trim()); }}><TextField label="포인트를 관리할 매장 ID" value={storeId} onValueChange={setStoreId} maxLength={36} /><Button type="submit" variant="secondary" disabled={!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(storeId.trim())}>지정 매장 포인트 관리</Button></form>
    <div className="button-row"><SelectField label="매장 정책 목록 조회 사유" value={reason} onValueChange={setReason}><option value="">조회 목적 선택</option><option value="POLICY_AUDIT_REVIEW">매장 정책 감사</option></SelectField><SelectField label="매장 정책 상태" value={state} onValueChange={value => setState(value as typeof state)}><option value="">모든 설정</option><option value="OVERRIDE">전용 정책</option><option value="INHERIT_GLOBAL">공통 상속</option></SelectField><Button variant="secondary" disabled={!reason} onClick={() => { setCursors([undefined]); setRequest({ reason, state: state || undefined }); }}>매장 정책 목록 조회</Button></div>
    {list.state.status === "loading" ? <LoadingState label="매장 정책 목록을 불러오는 중" /> : list.state.status === "failed" ? <ErrorState error={list.state.error} retry={list.reload} /> : list.state.value ? <>
      {list.state.value.items.length ? <ul className="menu-authoring-list">{list.state.value.items.map(policy => <li key={policy.scopeReference}><div><p className="support-case-reference">{policy.scopeReference}</p><p>{policy.state === "INHERIT_GLOBAL" ? "공통 상속" : "전용 정책"} · 버전 {policy.policyVersionId}</p><p>{fullDateTime.format(new Date(policy.effectiveAt))}</p></div><Button variant="secondary" aria-label={`${policy.scopeReference} 포인트 관리`} onClick={() => setSelected(policy.scopeReference)}>정책 관리</Button></li>)}</ul> : <EmptyState title="일치하는 매장 정책이 없습니다" description="별도 설정이 없는 매장은 이 목록에 표시되지 않습니다." />}
      <div className="button-row"><Button variant="ghost" disabled={cursors.length < 2} onClick={() => { const next = cursors.slice(0, -1); setCursors(next); setRequest({ ...request!, cursor: next.at(-1) }); }}>이전 매장 정책</Button><Button variant="secondary" disabled={!list.state.value.page.nextCursor} onClick={() => { if (list.state.status === "ready" && list.state.value?.page.nextCursor) { const cursor = list.state.value.page.nextCursor; setCursors(value => [...value, cursor]); setRequest({ ...request!, cursor }); } }}>다음 매장 정책</Button></div>
    </> : null}
    {selected ? <StorePointPolicyWorkspace key={selected} storeId={selected} /> : null}
  </section>;
}
