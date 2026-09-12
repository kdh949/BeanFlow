import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { unwrap } from "../../api/client";
import { Button, ButtonLink, EmptyState, InlineNotice, LoadingState, SelectField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
type Issuer = components["schemas"]["PointCostIssuerResource"];
export type PointCostIssuerSelection = Omit<Issuer, "source"> & { source: Issuer["source"] | "CURRENT_POLICY" };
export const pointIssuerTypeLabels = { PLATFORM: "플랫폼", BRAND: "브랜드", STORE: "매장" };
export function currentPolicyIssuer(policy: { issuerType: Issuer["issuerType"]; issuerReference: string; policyVersionId: number }): PointCostIssuerSelection {
  return { issuerType: policy.issuerType, issuerReference: policy.issuerReference, displayName: `현재 정책의 ${pointIssuerTypeLabels[policy.issuerType]} 비용 주체`, source: "CURRENT_POLICY", sourcePolicyVersion: policy.policyVersionId };
}
export function pointCostIssuerSource(value: PointCostIssuerSelection): string {
  return value.source === "CURRENT_POLICY" || value.source === "GLOBAL_POLICY" ? `정책 버전 ${value.sourcePolicyVersion}에 명시된 비용 주체` : value.source === "REGISTERED_PLATFORM" ? "운영자가 이름으로 등록한 플랫폼 비용 주체" : value.source === "STORE_PROFILE" ? "현재 매장 명부" : "현재 브랜드 명부";
}
type Props = {
  /** Uses the current permission for the command that will consume this selection. */
  purpose: "POLICY" | "ADJUSTMENT";
  /** Only a server candidate or an unchanged policy snapshot may be selected. */
  value: PointCostIssuerSelection | null;
  onValueChange: (value: PointCostIssuerSelection | null) => void;
  disabled?: boolean;
  label?: string;
};
/** Selects actual cost owners by their recorded names and source without requesting internal references. */
export function PointCostIssuerPicker({ purpose, value, onValueChange, disabled = false, label = "포인트 비용 주체" }: Props) {
  const [open, setOpen] = useState(!value), [type, setType] = useState<Issuer["issuerType"] | "">(value?.issuerType ?? "");
  return <fieldset className="catalog-fieldset management-workspace" disabled={disabled}><legend>{label}</legend>
    {value ? <><InlineNotice tone="info" title={`선택한 비용 주체 · ${value.displayName}`} description={pointCostIssuerSource(value)} /><Button type="button" variant="secondary" onClick={() => setOpen(current => !current)} aria-expanded={open}>비용 주체 변경</Button></> : null}
    {open ? <><SelectField label={label} value={type} onValueChange={next => { setType(next as typeof type); onValueChange(null); }}><option value="">비용 주체 선택</option>{Object.entries(pointIssuerTypeLabels).map(([key, name]) => <option key={key} value={key}>{name}</option>)}</SelectField>
      {type ? <IssuerCandidates key={`${purpose}:${type}`} purpose={purpose} type={type} disabled={disabled} onSelect={candidate => { onValueChange(candidate); setOpen(false); }} /> : <p>포인트 비용을 부담할 주체의 유형과 실제 대상을 선택해 주세요.</p>}
    </> : null}
  </fieldset>;
}
function IssuerCandidates({ purpose, type, disabled, onSelect }: { purpose: Props["purpose"]; type: Issuer["issuerType"]; disabled: boolean; onSelect: (value: Issuer) => void }) {
  const [draft, setDraft] = useState(""), [query, setQuery] = useState("");
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]), cursor = cursors[cursors.length - 1];
  const read = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/point-cost-issuers", { params: { query: { purpose, type, query: query || undefined, cursor, limit: 20 } } })), [purpose, type, query, cursor]));
  function search() { if (disabled) return; setQuery(draft.trim()); setCursors([undefined]); }
  return <div className="management-workspace"><div className="button-row"><TextField label="비용 주체 이름 검색" value={draft} maxLength={200} onValueChange={setDraft} onKeyDown={event => { if (event.key === "Enter") { event.preventDefault(); search(); } }} /><Button type="button" variant="secondary" onClick={search}>비용 주체 찾기</Button></div>
    {read.state.status === "loading" ? <LoadingState label="비용 주체를 확인하는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : <>
      {read.state.value.items.length ? read.state.value.items.map(item => <article className="surface-card management-card" key={`${item.issuerType}:${item.issuerReference}`}><strong>{item.displayName}</strong><p>{pointCostIssuerSource(item)}</p><Button type="button" variant="secondary" onClick={() => onSelect(item)} aria-label={`${item.displayName} 선택`}>선택</Button></article>) : <EmptyState title="현재 조회 구간에 선택할 비용 주체가 없습니다" description={read.state.value.nextCursor ? "다음 조회 구간을 확인해 주세요." : "검색어와 등록된 명부를 확인해 주세요."} />}
      <div className="button-row"><Button type="button" variant="secondary" disabled={cursors.length === 1} onClick={() => setCursors(values => values.slice(0, -1))}>이전 비용 주체</Button><Button type="button" variant="secondary" disabled={!read.state.value.nextCursor} onClick={() => { if (read.state.status === "ready" && read.state.value.nextCursor) { const next = read.state.value.nextCursor; setCursors(values => [...values, next]); } }}>다음 비용 주체</Button></div>
      {type === "PLATFORM" && read.state.value.canRegisterPlatform && !disabled ? <ButtonLink variant="ghost" to="/ops/policies?workspace=cost-owners">플랫폼 비용 주체 등록</ButtonLink> : null}
    </>}
  </div>;
}
