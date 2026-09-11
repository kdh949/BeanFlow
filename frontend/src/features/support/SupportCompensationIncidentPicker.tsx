import { useCallback, useEffect, useState } from "react";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { unwrap } from "../../api/client";
import { Button, Checkbox, EmptyState, InlineNotice, LoadingState, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { shortDateTime } from "../../lib/format";
import { seoulInstant } from "../../lib/seoulDateTime";
import { useResource } from "../shared/useResource";
import { caseCategoryLabels } from "./supportCaseLabels";
import { useSupportCommand } from "./useSupportCommand";
export type CompensationIncident = components["schemas"]["SupportCompensationIncidentResource"];
type Props = {
  /** Current assigned case and completed customer verification bind every candidate. */
  caseId: string; sessionId: string; orderId?: string;
  /** A revision link may narrow the list to its original incident; it never supplies a new identity. */
  initialIncidentId?: string;
  selected?: CompensationIncident | null; disabled?: boolean;
  onSelect: (incident: CompensationIncident) => void;
  /** Keeps parent case, verification and order fixed while registration is pending or uncertain. */
  onBusyChange: (busy: boolean) => void;
};
/** Composes existing form and request-state primitives into stable incident selection and registration. */
export function SupportCompensationIncidentPicker({ caseId, sessionId, orderId, initialIncidentId, selected, disabled = false, onSelect, onBusyChange }: Props) {
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]), [filter, setFilter] = useState(initialIncidentId);
  const [registering, setRegistering] = useState(false), [occurred, setOccurred] = useState(""), [confirmed, setConfirmed] = useState(false), [validation, setValidation] = useState("");
  const cursor = cursors[cursors.length - 1];
  const read = useResource(useCallback(async () => unwrap(await operationsApi.GET("/support/cases/{caseId}/compensation-incidents", { params: { path: { caseId }, query: { verificationSessionId: sessionId, orderId, incidentId: filter, cursor, limit: 20 } } })), [caseId, sessionId, orderId, filter, cursor]));
  const command = useSupportCommand(() => undefined);
  const ownBusy = command.busy || command.pending, blocked = disabled || ownBusy;
  useEffect(() => { onBusyChange(ownBusy); return () => onBusyChange(false); }, [ownBusy, onBusyChange]);
  function register() {
    if (blocked || !confirmed || !occurred || read.state.status !== "ready") return;
    setValidation("");
    let occurredAt: string;
    try { occurredAt = seoulInstant(occurred); if (Date.parse(occurredAt) > Date.now()) throw new Error("미래 시각은 사고 발생 시각으로 등록할 수 없습니다."); }
    catch (failure) { setValidation(failure instanceof Error ? failure.message : "발생 시각을 확인해 주세요."); return; }
    const body = { verificationSessionId: sessionId, orderId: orderId || null, occurredAt };
    command.submit(JSON.stringify(body), async key => { const created = unwrap(await operationsApi.POST("/support/cases/{caseId}/compensation-incidents", { params: { path: { caseId }, header: { "Idempotency-Key": key } }, body })); onSelect(created); }, () => { setRegistering(false); setConfirmed(false); setOccurred(""); read.reload(); });
  }
  return <section className="management-workspace" aria-label="보상 사고 선택">
    <fieldset className="catalog-fieldset management-workspace" disabled={blocked}><legend>보상 사고</legend>
      <p>같은 문제를 다시 검토할 때는 기존 사고를 선택합니다. 사고마다 혜택은 한 번만 지급할 수 있습니다.</p>
      {selected ? <InlineNotice tone="info" title={`선택한 사고 · ${caseCategoryLabels[selected.category]}`} description={`${selected.occurredAt ? `발생 ${shortDateTime.format(new Date(selected.occurredAt))}` : "발생 시각 미기록"} · 등록 ${shortDateTime.format(new Date(selected.createdAt))}`} /> : null}
      {filter ? <><p>재검토 링크의 기존 사고를 확인합니다.</p><Button type="button" variant="ghost" onClick={() => { setFilter(undefined); setCursors([undefined]); }}>이 고객과 주문의 모든 사고 보기</Button></> : null}
      {read.state.status === "loading" ? <LoadingState label="기존 보상 사고를 확인하는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : <>
        {read.state.value.items.length ? read.state.value.items.map(item => <article className="surface-card management-card" key={item.incidentId}><strong>{caseCategoryLabels[item.category]} 사고</strong><p>{item.occurredAt ? `발생 ${shortDateTime.format(new Date(item.occurredAt))}` : "기존 보상에 기록된 사고 · 발생 시각 미기록"} · 등록 {shortDateTime.format(new Date(item.createdAt))}</p>{item.benefitIssued ? <p>이미 보상한 사고 · 추가 지급 불가</p> : null}<Button type="button" variant="secondary" disabled={item.benefitIssued} onClick={() => { onSelect(item); setRegistering(false); }}>이 사고 선택</Button></article>) : <EmptyState title={filter ? "재검토할 사고를 현재 고객과 주문에서 찾지 못했습니다" : "이 고객과 주문의 보상 사고가 없습니다"} description="관련 주문과 고객 본인확인을 확인한 뒤 별개의 새 사고인지 판단해 주세요." />}
        <div className="button-row"><Button type="button" variant="secondary" disabled={cursors.length === 1} onClick={() => setCursors(values => values.slice(0, -1))}>이전 사고</Button><Button type="button" variant="secondary" disabled={!read.state.value.nextCursor} onClick={() => { if (read.state.status === "ready" && read.state.value.nextCursor) setCursors(values => [...values, read.state.status === "ready" ? read.state.value.nextCursor! : undefined]); }}>다음 사고</Button><Button type="button" variant="ghost" onClick={read.reload}>사고 목록 새로고침</Button></div>
        <Button type="button" variant="secondary" onClick={() => setRegistering(value => !value)} aria-expanded={registering}>별개의 새 사고 등록</Button>
        {registering ? <><TextField label="사고 발생 시각 (한국 시간)" type="datetime-local" value={occurred} onValueChange={setOccurred} required /><Checkbox label="기존 사고와 다른 사고임을 확인했습니다" description="같은 사고의 조건 수정·재시도에는 기존 사고를 사용합니다. 새 등록은 혜택 지급이 아닙니다." checked={confirmed} onCheckedChange={setConfirmed} /><Button type="button" disabled={!confirmed || !occurred} onClick={register}>확인한 새 사고 등록</Button></> : null}
      </>}
      {validation ? <InlineNotice tone="warning" title="발생 시각을 확인해 주세요" description={validation} /> : null}
    </fieldset>
    {command.failure ? <ErrorState error={command.failure} /> : null}
    {command.pending ? <><InlineNotice tone="warning" title="사고 등록 결과를 확인하지 못했습니다" description="같은 요청으로 결과를 확인할 때까지 고객·주문·사고 선택을 유지합니다." /><Button type="button" variant="secondary" loading={command.busy} onClick={() => void command.retry()}>같은 사고 등록 결과 확인</Button></> : null}
  </section>;
}
