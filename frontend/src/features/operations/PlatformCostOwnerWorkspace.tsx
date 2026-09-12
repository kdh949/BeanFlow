import { useCallback, useEffect, useState } from "react";
import { operationsApi } from "../../api/consoleClient";
import { unwrap } from "../../api/client";
import { Button, EmptyState, InlineNotice, LoadingState, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
import { useSupportCommand } from "../support/useSupportCommand";
import { pointCostIssuerSource } from "./PointCostIssuerPicker";
/** Registers immutable named platform cost owners; no point or policy is changed by registration. */
export function PlatformCostOwnerWorkspace({ onBusyChange }: { /** Keeps navigation fixed until registration has a known result. */ onBusyChange: (busy: boolean) => void }) {
  const [name, setName] = useState(""), [reason, setReason] = useState(""), [message, setMessage] = useState("");
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]), cursor = cursors[cursors.length - 1];
  const read = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/point-cost-issuers", { params: { query: { purpose: "POLICY", type: "PLATFORM", cursor, limit: 20 } } })), [cursor]));
  const command = useSupportCommand("operations-platform-point-cost-owner", () => undefined), busy = command.busy || command.pending;
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false); }, [busy, onBusyChange]);
  function register() {
    if (busy || read.state.status !== "ready" || !read.state.value.canRegisterPlatform || !name.trim() || !reason.trim()) return;
    const body = { name: name.trim(), reason: reason.trim() }; setMessage("");
    command.submit(JSON.stringify(body), async key => { const value = unwrap(await operationsApi.POST("/operations/platform-point-cost-owners", { params: { header: { "Idempotency-Key": key } }, body })); setMessage(`${value.displayName} 등록 완료`); }, () => { setName(""); setReason(""); setCursors([undefined]); read.reload(); });
  }
  return <section className="management-workspace" aria-label="플랫폼 포인트 비용 주체"><h2>플랫폼 포인트 비용 주체</h2><InlineNotice tone="info" title="비용을 부담할 주체를 이름으로 등록합니다" description="등록 후 포인트 조정이나 정책 변경에서 선택합니다. 등록만으로 포인트가 지급되거나 기존 비용이 바뀌지 않습니다. 같은 이름은 다시 등록할 수 없습니다." />
    {read.state.status === "loading" ? <LoadingState label="등록된 플랫폼 비용 주체를 읽는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : <>
      {read.state.value.items.length ? <ul className="menu-authoring-list">{read.state.value.items.map(item => <li key={item.issuerReference}><div><strong>{item.displayName}</strong><p>{pointCostIssuerSource(item)}</p></div></li>)}</ul> : <EmptyState title="등록된 플랫폼 비용 주체가 없습니다" description="확인된 비용 부담 주체를 이름으로 등록해 주세요." />}
      <div className="button-row"><Button variant="secondary" disabled={busy || cursors.length === 1} onClick={() => setCursors(values => values.slice(0, -1))}>이전 플랫폼 비용 주체</Button><Button variant="secondary" disabled={busy || !read.state.value.nextCursor} onClick={() => { if (read.state.status === "ready" && read.state.value.nextCursor) { const next = read.state.value.nextCursor; setCursors(values => [...values, next]); } }}>다음 플랫폼 비용 주체</Button></div>
      {read.state.value.canRegisterPlatform ? <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); register(); }}><fieldset className="catalog-fieldset" disabled={busy}><legend>새 플랫폼 비용 주체</legend><TextField label="플랫폼 비용 주체 이름" value={name} onValueChange={setName} maxLength={200} required description="비용 책임을 구분할 업무상 이름을 입력합니다. 계좌번호나 개인정보를 입력하지 않습니다." /><TextAreaField label="비용 주체 등록 사유" value={reason} onValueChange={setReason} maxLength={500} required /><Button type="submit" disabled={!name.trim() || !reason.trim()}>플랫폼 비용 주체 등록</Button></fieldset></form> : null}
    </>}
    {message ? <p role="status">{message}</p> : null}{command.failure ? <ErrorState error={command.failure} /> : null}
    {command.pending ? <><InlineNotice tone="warning" title="비용 주체 등록 결과를 확인하지 못했습니다" description="새 주체를 등록하기 전에 같은 요청으로 결과를 확인해 주세요." /><Button variant="secondary" loading={command.busy} onClick={() => void command.retry()}>같은 비용 주체 등록 결과 확인</Button></> : null}
  </section>;
}
