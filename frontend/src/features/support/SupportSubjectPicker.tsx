import { useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, InlineNotice, LoadingState, SelectField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { subjectLabels } from "./supportCaseLabels";
export type SupportSubjectSelection = { subjectType: components["schemas"]["SupportSubjectType"]; subjectId: string; label: string };
/** Exact owner-backed selection. Raw criteria remain in component memory and are cleared on selection. */
export function SupportSubjectPicker({ subjectType, caseId, value, onSelect, disabled = false }: {
  /** The business subject being linked; DELIVERY is the existing external courier profile. */
  subjectType: SupportSubjectSelection["subjectType"];
  /** Required for an order candidate; the server checks current case assignment. */
  caseId?: string;
  value: SupportSubjectSelection | null;
  onSelect: (value: SupportSubjectSelection | null) => void;
  disabled?: boolean;
}) {
  const [criterion, setCriterion] = useState("");
  const [kind, setKind] = useState<"PHONE" | "EMAIL">("PHONE");
  const [items, setItems] = useState<SupportSubjectSelection[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [ambiguous, setAmbiguous] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const generation = useRef(0);
  useEffect(() => { generation.current++; setCriterion(""); setItems(null); setError(null); setBusy(false); return () => { generation.current++; }; }, [subjectType, caseId]);
  async function search() {
    if (disabled || busy || !criterion.trim() || (subjectType === "ORDER" && !caseId)) return;
    const run = ++generation.current;
    setBusy(true); setError(null); setItems(null); setAmbiguous(false); setHasMore(false);
    try {
      if (subjectType === "ORDER") {
        const order = unwrap(await operationsApi.GET("/support/cases/{caseId}/order-candidates/{orderReference}", { params: { path: { caseId: caseId!, orderReference: criterion.trim().toUpperCase() } } }));
        if (run === generation.current) setItems([{ subjectType, subjectId: order.orderId, label: `${order.publicReference} · ${order.storeName}` }]);
      } else {
        const result = unwrap(await operationsApi.POST("/support/searches", { body: { criterion: { type: kind, value: criterion.trim() }, subjectTypes: [subjectType === "DELIVERY" ? "RIDER" : subjectType], reasonCode: caseId ? "ACTIVE_CASE_LOOKUP" : "CASE_INTAKE" } }));
        if (run === generation.current) { setItems(result.items.map(item => ({ subjectType: item.subjectType === "RIDER" ? "DELIVERY" : item.subjectType, subjectId: item.subjectId, label: `${item.maskedDisplayName} · ${item.maskedMatchedValue}` }))); setAmbiguous(result.ambiguous); setHasMore(result.hasMore); }
      }
    } catch (failure) { if (run === generation.current) setError(failure); }
    finally { if (run === generation.current) setBusy(false); }
  }
  function select(item: SupportSubjectSelection | null) { generation.current++; setCriterion(""); setItems(null); setError(null); setBusy(false); onSelect(item); }
  return <fieldset className="management-workspace" disabled={disabled}><legend>{subjectLabels[subjectType]} 선택</legend>{value ? <><p>선택한 대상: {value.label}</p><Button type="button" variant="secondary" onClick={() => select(null)}>다른 대상 선택</Button></> : <>
    {subjectType !== "ORDER" ? <SelectField label="정확 검색 기준" value={kind} onValueChange={next => { generation.current++; setKind(next as typeof kind); setCriterion(""); setItems(null); setError(null); setBusy(false); }}><option value="PHONE">등록 전화번호</option><option value="EMAIL">등록 이메일</option></SelectField> : null}
    <TextField label={subjectType === "ORDER" ? "공개 주문번호" : "등록 전화번호 또는 이메일"} value={criterion} onValueChange={next => { generation.current++; setCriterion(next); setItems(null); setError(null); setBusy(false); }} maxLength={subjectType === "ORDER" ? 12 : 320} autoComplete="off" onKeyDown={event => { if (event.key === "Enter") { event.preventDefault(); void search(); } }} description={subjectType === "ORDER" ? "고객 주문 내역에 표시된 BF-XXXX-XXXX 번호를 입력합니다." : "등록된 연락처를 정확히 입력하면 마스킹된 후보를 확인할 수 있습니다."} />
    <Button type="button" loading={busy} disabled={disabled || busy || !criterion.trim() || (subjectType === "ORDER" && !caseId)} onClick={() => void search()}>대상 정확 검색</Button>
    {busy ? <LoadingState label="상담 대상을 찾는 중" /> : error ? <ErrorState error={error} retry={() => void search()} /> : items ? <>{ambiguous ? <InlineNotice title="일치하는 대상이 여러 명입니다" description="마스킹된 이름과 연락처를 확인하고 대상을 선택해 주세요." /> : null}{items.length ? items.map(item => <article key={`${item.subjectType}:${item.subjectId}`} className="surface-card management-card"><p><strong>{item.label}</strong></p><Button type="button" variant="secondary" aria-label={`${item.label} 대상 선택`} onClick={() => select(item)}>이 대상 선택</Button></article>) : <EmptyState title="일치하는 대상이 없습니다" description="등록된 연락처를 확인해 다시 검색해 주세요." />}{hasMore ? <InlineNotice title="검색 결과가 표시 범위를 초과했습니다" description="다른 등록 연락처로 정확 검색하여 대상을 좁혀 주세요." /> : null}</> : null}
  </>}</fieldset>;
}
