import { useCallback, useEffect, useState } from "react";
import type { components } from "../../api/schema";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { customerApi } from "../../api/customerClient";
import { unwrap } from "../../api/client";
import { Button, Checkbox, EmptyState, InlineNotice, LoadingState, SelectField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { shortDateTime } from "../../lib/format";
import { cancellationReasonLabels, orderActionLabels, orderChangeDigest, type CancellationReason, type OrderChangeAction } from "../../lib/supportOrderPayload";
import { useSupportCommand } from "../support/useSupportCommand";
import { useResource } from "../shared/useResource";
import { StoreSelector } from "./StoreSelector";
import { useMerchantStores } from "./useMerchantStores";
const policyVersion = "support-order-change-policy/2026-08-12/v1";
type Target = components["schemas"]["StoreSupportOrderChangeRequestResource"];
type Authorization = components["schemas"]["SupportOrderChangeAuthorizationResource"];

/** Store actors accept the exact request or an explicitly bounded delegation, with store cost responsibility. */
export function StoreSupportOrderChangeWorkspace({ storeId, onBusyChange }: { storeId: string; onBusyChange?: (busy: boolean) => void }) {
  const [type, setType] = useState<"CONFIRMATION" | "DELEGATION">("CONFIRMATION");
  const [action, setAction] = useState<OrderChangeAction>("ORDER_CANCELLATION");
  const [target, setTarget] = useState<(Target & { orderReference: string }) | null>(null);
  const [reasonCode, setReasonCode] = useState<CancellationReason>("CHANGED_MIND"), [slotId, setSlotId] = useState("");
  const [digest, setDigest] = useState(""); const [accepted, setAccepted] = useState(false);
  const [loading, setLoading] = useState(false), [failure, setFailure] = useState<unknown>(null);
  const [result, setResult] = useState<Authorization | null>(null); const [clock, setClock] = useState(Date.now());
  const command = useSupportCommand(() => { setAccepted(false); setTarget(null); });
  const blocked = command.busy || command.pending || loading;
  useEffect(() => { onBusyChange?.(blocked); return () => onBusyChange?.(false); }, [blocked, onBusyChange]);
  const effectiveAction = target?.action ?? action;
  const slots = useResource(useCallback(async () => target?.action === "PICKUP_RESCHEDULE" ? unwrap(await customerApi.GET("/stores/{storeId}/pickup-slots", { params: { path: { storeId } } })) : null, [storeId, target?.action]));
  useEffect(() => { let live = true; setDigest(""); if (target && (target.action === "ORDER_CANCELLATION" || slotId)) void orderChangeDigest(target.action, target.orderId, reasonCode, slotId).then(value => { if (live) setDigest(value); }); return () => { live = false; }; }, [target, reasonCode, slotId]);
  useEffect(() => { if (!target) return; const timer = window.setTimeout(() => setClock(Date.now()), Math.max(0, new Date(target.expiresAt).getTime() - Date.now()) + 1); return () => window.clearTimeout(timer); }, [target]);
  const current = !!target && new Date(target.expiresAt).getTime() > Math.max(clock, Date.now());
  const matches = !!target && digest === target.actionPayloadDigest;
  async function lookup(candidate: components["schemas"]["StoreOrderChangeCandidate"]) {
    if (blocked) return;
    setLoading(true); setFailure(null); setTarget(null); setAccepted(false); setResult(null); setSlotId("");
    try { setTarget({ ...unwrap(await merchantApi.GET("/stores/{storeId}/support-order-change-requests/{requestId}", { params: { path: { storeId, requestId: candidate.requestId } } })), orderReference: candidate.orderReference }); }
    catch (error) { setFailure(error); } finally { setLoading(false); }
  }
  function submit() {
    if (blocked || !accepted || (type === "CONFIRMATION" && (!target || !current || !matches))) return;
    const body: components["schemas"]["CreateSupportOrderChangeAuthorizationRequest"] = { authorizationType: type, action: effectiveAction, policyVersion, costResponsibility: "STORE", ...(type === "CONFIRMATION" && target ? { requestId: target.requestId, revisionNumber: target.revisionNumber, expectedRequestVersion: target.requestVersion } : {}) };
    command.submit(JSON.stringify(body), async key => { const header = await merchantCsrfHeader(); setResult(unwrap(await merchantApi.POST("/stores/{storeId}/support-order-change-authorizations", { params: { path: { storeId }, header: { ...header, "Idempotency-Key": key } }, body }))); }, () => undefined);
  }
  return <section className="surface-card management-card management-workspace" aria-label="상담 주문 변경 매장 동의">
    <h2>상담 주문 변경 동의</h2>
    <InlineNotice tone="info" title="수락한 주문의 변경 범위를 확인합니다" description="상담원의 취소·픽업 변경 요청에 동의하거나 제한된 시간 동안 위임합니다. 실제 주문 변경은 상담원이 별도로 실행합니다." />
    {result ? <div className="management-workspace"><p role="status">매장 동의를 등록했습니다</p><p>{orderActionLabels[result.action]} · {shortDateTime.format(new Date(result.expiresAt))}까지 · 성공한 처리 {result.maxSuccessfulUses}회 한도</p><p>담당 상담원이 요청 화면에서 이 동의를 선택해 처리할 수 있습니다.</p><Button variant="secondary" onClick={() => setResult(null)}>다른 동의 작성</Button></div> : <>
      <SelectField label="동의 범위" value={type} disabled={blocked} onValueChange={value => { setType(value as typeof type); setTarget(null); setAccepted(false); }}><option value="CONFIRMATION">한 승인안에 대한 동의</option><option value="DELEGATION">업무별 한시 위임</option></SelectField>
      {type === "CONFIRMATION" ? <StoreOrderChangeList storeId={storeId} disabled={blocked} onSelect={candidate => void lookup(candidate)} /> : <><SelectField label="위임할 업무" value={action} onValueChange={value => { setAction(value as OrderChangeAction); setAccepted(false); }} disabled={blocked}>{Object.entries(orderActionLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField><p>{action === "ORDER_CANCELLATION" ? "10분 동안 성공한 취소 1회까지 위임합니다" : "30분 동안 성공한 변경 3회까지 위임합니다"}</p></>}
      {target ? <><p><strong>주문 {target.orderReference}</strong></p><p>{orderActionLabels[target.action]} · 승인안 {target.revisionNumber} · 주문 버전 {target.targetVersion} · {shortDateTime.format(new Date(target.expiresAt))}까지</p>
        {target.action === "ORDER_CANCELLATION" ? <SelectField label="요청받은 취소 사유" value={reasonCode} onValueChange={value => { setReasonCode(value as CancellationReason); setAccepted(false); }} disabled={blocked}>{Object.entries(cancellationReasonLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField> : slots.state.status === "loading" ? <LoadingState label="픽업 시간을 읽는 중" /> : slots.state.status === "failed" ? <ErrorState error={slots.state.error} retry={slots.reload} /> : <SelectField label="요청받은 픽업 시간" value={slotId} onValueChange={value => { setSlotId(value); setAccepted(false); }} disabled={blocked}><option value="">픽업 시간을 선택해 주세요</option>{slots.state.value?.items.map(slot => <option key={slot.pickupSlotId} value={slot.pickupSlotId}>{shortDateTime.format(new Date(slot.startsAt))}</option>)}</SelectField>}
        {!current ? <InlineNotice tone="warning" title="동의 대상이 만료되었습니다" description="상담원에게 현재 요청을 확인해 주세요." /> : !matches ? <InlineNotice tone="warning" title="요청받은 내용과 현재 승인안을 확인해 주세요" description="취소 사유 또는 픽업 시간이 승인안과 일치해야 동의할 수 있습니다." /> : null}
      </> : null}
      {(type === "DELEGATION" || target) ? <><Checkbox label="매장 비용 책임에 동의합니다" description="이 동의 범위에서 발생하는 비용은 매장이 부담합니다." checked={accepted} onCheckedChange={setAccepted} disabled={blocked} /><Button disabled={blocked || !accepted || (type === "CONFIRMATION" && (!current || !matches))} onClick={submit}>{type === "CONFIRMATION" ? "이 승인안의 주문 변경에 동의" : "한시 위임 등록"}</Button></> : null}
    </>}
    {failure ? <ErrorState error={failure} /> : null}{command.failure ? <ErrorState error={command.failure} /> : null}
    {command.pending ? <><InlineNotice tone="warning" title="동의 등록 결과를 확인하지 못했습니다" description="새 동의를 만들기 전에 같은 요청으로 결과를 확인해 주세요." /><Button variant="secondary" loading={command.busy} onClick={() => void command.retry()}>같은 동의 결과 확인</Button></> : null}
  </section>;
}

export function StoreSupportOrderChanges() {
  const stores = useMerchantStores();
  const [busy, setBusy] = useState(false);
  if (stores.state.status === "loading") return <LoadingState label="매장을 불러오는 중" />;
  if (stores.state.status === "failed") return <ErrorState error={stores.state.error} retry={stores.reload} />;
  return <div className="management-workspace"><StoreSelector stores={stores.stores} selected={stores.selected} onSelect={stores.select} disabled={busy} />{stores.selected ? <StoreSupportOrderChangeWorkspace key={stores.selected.storeId} storeId={stores.selected.storeId} onBusyChange={setBusy} /> : <EmptyState title="관리할 매장이 없습니다" description="소속 매장과 권한을 확인해 주세요." />}</div>;
}

function StoreOrderChangeList({ storeId, onSelect, disabled }: { storeId: string; onSelect: (value: components["schemas"]["StoreOrderChangeCandidate"]) => void; disabled: boolean }) {
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const cursor = cursors.at(-1);
  const read = useResource(useCallback(async () => unwrap(await merchantApi.GET("/stores/{storeId}/support-order-change-requests", { params: { path: { storeId }, query: { cursor, limit: 20 } } })), [storeId, cursor]));
  return <fieldset className="catalog-fieldset management-workspace" disabled={disabled}><legend>동의 대기 주문 변경</legend>{read.state.status === "loading" ? <LoadingState label="매장에 요청된 주문 변경을 확인하는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : <>
    {read.state.value.items.length ? read.state.value.items.map(item => <article className="surface-card management-card" key={item.requestId}><strong>주문 {item.orderReference}</strong><p>{orderActionLabels[item.action as OrderChangeAction]} · 승인안 {item.revisionNumber}</p><p>요청 {shortDateTime.format(new Date(item.createdAt))} · {shortDateTime.format(new Date(item.expiresAt))}까지</p><Button type="button" variant="secondary" onClick={() => onSelect(item)}>이 주문 변경 확인</Button></article>) : <EmptyState title="현재 조회 구간에 동의할 주문 변경이 없습니다" description={read.state.value.nextCursor ? "다음 조회 구간을 확인해 주세요." : "현재 매장의 유효한 요청이 등록되면 여기에서 확인할 수 있습니다."} />}
    <div className="button-row"><Button type="button" variant="ghost" disabled={cursors.length < 2} onClick={() => setCursors(list => list.slice(0, -1))}>이전 주문 변경</Button><Button type="button" variant="secondary" disabled={!read.state.value.nextCursor} onClick={() => { const next = read.state.status === "ready" ? read.state.value.nextCursor : null; if (next) setCursors(list => [...list, next]); }}>다음 주문 변경</Button><Button type="button" variant="ghost" onClick={read.reload}>주문 변경 목록 새로고침</Button></div>
  </>}</fieldset>;
}
