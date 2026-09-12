import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { unwrap } from "../../api/client";
import { Button, EmptyState, InlineNotice, LoadingState } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { useExpired } from "./useSupportExpiry";

export type OrderConsentSelection = components["schemas"]["SupportOrderConsentCandidate"];
const label = (value: OrderConsentSelection) => value.authorizationType === "CONFIRMATION" ? "이 승인안에 대한 매장 동의" : "매장의 한시 위임";

/** Chooses an existing request-bound authorization; selection never consumes a use. */
export function SupportOrderConsentPicker({ requestId, value, onValueChange, disabled = false }: { requestId: string; value: OrderConsentSelection | null; onValueChange: (value: OrderConsentSelection | null) => void; disabled?: boolean }) {
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const cursor = cursors.at(-1);
  const read = useResource(useCallback(async () => unwrap(await operationsApi.GET("/support/action-requests/{requestId}/store-consents", { params: { path: { requestId }, query: { cursor, limit: 20 } } })), [requestId, cursor]));
  const expired = useExpired(value?.expiresAt);
  return <fieldset className="catalog-fieldset management-workspace" disabled={disabled}><legend>매장 동의 선택</legend>
    {value ? <><strong>{label(value)}</strong><p>{fullDateTime.format(new Date(value.expiresAt))}까지 · 남은 처리 {value.remainingUses}회</p>{expired ? <InlineNotice tone="warning" title="선택한 매장 동의가 만료되었습니다" description="새로 등록된 동의나 유효한 위임을 선택해 주세요." /> : null}<Button type="button" variant="secondary" onClick={() => { onValueChange(null); read.reload(); }}>다른 매장 동의 선택</Button></> : read.state.status === "loading" ? <LoadingState label="유효한 매장 동의를 확인하는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : <>
      {read.state.value.items.length ? read.state.value.items.map(item => <article key={item.authorizationId} className="surface-card management-card"><strong>{label(item)}</strong><p>{fullDateTime.format(new Date(item.expiresAt))}까지 · 남은 처리 {item.remainingUses}회</p><Button type="button" variant="secondary" onClick={() => onValueChange(item)}>이 동의 선택</Button></article>) : <EmptyState title="사용할 수 있는 매장 동의가 없습니다" description="매장이 주문 변경 동의 화면에서 현재 요청에 동의한 뒤 목록을 새로고침해 주세요." />}
      <div className="button-row"><Button type="button" variant="ghost" disabled={cursors.length < 2} onClick={() => setCursors(list => list.slice(0, -1))}>이전 동의</Button><Button type="button" variant="secondary" disabled={!read.state.value.nextCursor} onClick={() => { const next = read.state.status === "ready" ? read.state.value.nextCursor : null; if (next) setCursors(list => [...list, next]); }}>다음 동의</Button><Button type="button" variant="ghost" onClick={read.reload}>매장 동의 새로고침</Button></div>
    </>}
  </fieldset>;
}
