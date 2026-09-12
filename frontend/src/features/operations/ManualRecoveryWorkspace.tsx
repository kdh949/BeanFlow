import { useCallback, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, InlineNotice, LoadingState, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";

type Kind = "notification" | "publication";
type Source = { kind: "notification"; value: components["schemas"]["NotificationRecoveryView"] } | { kind: "publication"; value: components["schemas"]["PublicationRecoveryView"] };
const publicationLabels: Record<string, string> = { COMPLETED: "전달 완료", PUBLISHED: "전달 대기", RESUBMITTED: "재전달 중", PROCESSING: "처리 중", FAILED: "전달 실패" };
const caseLabels = { OPEN: "접수", MANUAL_REVIEW: "확인 필요", RUNNING: "처리 중", RESOLVED: "해결됨" };
/** Lists manual cases and reserves one additional attempt only when the source permits it. */
export function ManualRecoveryWorkspace({ kind }: { kind: Kind }) {
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const [selected, setSelected] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [accepted, setAccepted] = useState<components["schemas"]["ManualRecoveryAcceptance"] | null>(null);
  const intent = useRef(new SubmissionIntent());
  const cursor = cursors.at(-1);
  const list = useResource(useCallback(async () => kind === "notification" ? unwrap(await operationsApi.GET("/operations/notification-delivery-recoveries", { params: { query: { cursor, limit: 20 } } })) : unwrap(await operationsApi.GET("/operations/event-publication-recoveries", { params: { query: { cursor, limit: 20 } } })), [kind, cursor]));
  const detail = useResource<Source | null>(useCallback(async () => {
    if (!selected) return null;
    if (kind === "notification") return { kind, value: unwrap(await operationsApi.GET("/operations/notification-delivery-recoveries/{deliveryId}", { params: { path: { deliveryId: selected } } })) };
    return { kind, value: unwrap(await operationsApi.GET("/operations/event-publication-recoveries/{publicationId}", { params: { path: { publicationId: selected } } })) };
  }, [kind, selected]));
  const source = detail.state.status === "ready" ? detail.state.value : null;
  const recoveryCase = source?.value.recoveryCase;
  const eligible = recoveryCase?.status === "MANUAL_REVIEW" && (source?.kind === "notification" ? source.value.state === "MANUAL_REVIEW" : source?.kind === "publication" && source.value.recoverable && !source.value.retryBlockedReason);
  async function retry() {
    if (busy || !source || !recoveryCase || !eligible || !reason.trim()) return;
    setBusy(true); setFailure(null); setAccepted(null);
    const common = { expectedCaseVersion: recoveryCase.version, reason: reason.trim() };
    try {
      if (source.kind === "notification") {
        const body = { ...common, expectedVersion: source.value.version };
        setAccepted(unwrap(await operationsApi.POST("/operations/notification-delivery-recoveries/{deliveryId}/retries", { params: { path: { deliveryId: source.value.deliveryId }, header: { "Idempotency-Key": intent.current.keyFor(JSON.stringify({ kind, selected, ...body })) } }, body })));
      } else {
        setAccepted(unwrap(await operationsApi.POST("/operations/event-publication-recoveries/{publicationId}/retries", { params: { path: { publicationId: source.value.publicationId }, header: { "Idempotency-Key": intent.current.keyFor(JSON.stringify({ kind, selected, ...common })) } }, body: common })));
      }
      intent.current.complete(); setReason("");
    } catch (error) { setFailure(error); }
    finally { detail.reload(); setBusy(false); }
  }
  return <section className="management-workspace"><h2>{kind === "notification" ? "알림 전달 복구" : "이벤트 전달 복구"}</h2>
    {selected ? <>
      <Button variant="ghost" disabled={busy} onClick={() => { setSelected(null); setReason(""); setFailure(null); setAccepted(null); intent.current.complete(); list.reload(); }}>복구 목록으로</Button>
      {accepted ? <InlineNotice title="재시도를 접수했습니다. 처리 결과를 다시 확인해 주세요." description={`접수 ${fullDateTime.format(new Date(accepted.acceptedAt))} · 요청 ${accepted.commandId}`} /> : null}
      {failure ? <ErrorState error={failure} /> : null}
      {detail.state.status === "loading" ? <LoadingState label="복구 원본 상태를 확인하는 중" /> : detail.state.status === "failed" ? <ErrorState error={detail.state.error} retry={detail.reload} /> : source ? <>
        <article className="surface-card management-card"><h3>원본 처리 상태</h3><p className="support-case-reference">{selected}</p><dl className="detail-list"><div><dt>상태</dt><dd><StatusText state={source.kind === "notification" ? source.value.state : source.value.status} label={source.kind === "publication" ? publicationLabels[source.value.status] : source.value.state === "SKIPPED" ? "발송 생략" : undefined} /></dd></div><div><dt>누적 시도</dt><dd>{source.value.attemptCount}회</dd></div>
          {source.kind === "notification" ? <><div><dt>현재 시도 한도</dt><dd>{source.value.attemptLimit}회</dd></div><div><dt>다음 시도</dt><dd>{source.value.nextAttemptAt ? fullDateTime.format(new Date(source.value.nextAttemptAt)) : "예약 없음"}</dd></div><div><dt>최근 실패 코드</dt><dd className="support-case-reference">{source.value.lastFailureCode ?? "없음"}</dd></div></> : <><div><dt>이벤트 유형</dt><dd className="support-case-reference">{source.value.eventType}</dd></div><div><dt>처리 대상</dt><dd className="support-case-reference">{source.value.listenerId}</dd></div><div><dt>마지막 실행 결과</dt><dd>{source.value.executionOutcome ? <StatusText state={source.value.executionOutcome} /> : "확인된 결과 없음"}</dd></div><div><dt>완료 시각</dt><dd>{source.value.completedAt ? fullDateTime.format(new Date(source.value.completedAt)) : "완료되지 않음"}</dd></div></>}
        </dl></article>
        {source.kind === "publication" && source.value.executionOutcome === "UNKNOWN" ? <InlineNotice tone="warning" title="실행 결과를 아직 확인하지 못했습니다" description={source.value.retryBlockedReason ? "업무 결과 조사 후 재실행 여부를 확인해야 합니다." : "중복 처리 방지가 확인된 대상입니다. 필요한 경우 같은 전달 건을 한 번 재시도할 수 있습니다."} /> : null}
        {source.kind === "publication" && source.value.retryBlockedReason ? <p className="support-case-reference">재시도 제한 코드: {source.value.retryBlockedReason}</p> : null}
        {recoveryCase ? <article className="surface-card management-card"><h3>복구 처리 내역</h3><StatusText state={recoveryCase.status} label={caseLabels[recoveryCase.status]} /><p className="support-case-reference">{recoveryCase.caseId}</p><p>{recoveryCase.reason}</p>{recoveryCase.resolution ? <p>{recoveryCase.resolution}</p> : null}<p>갱신 {fullDateTime.format(new Date(recoveryCase.updatedAt))}</p></article> : <EmptyState title="연결된 복구 내역이 없습니다" description="수동 복구 건이 등록된 대상만 재시도할 수 있습니다." />}
        {recoveryCase?.status === "RUNNING" ? <InlineNotice title="복구 진행 중입니다" description="접수된 시도의 실제 결과가 확인될 때까지 추가 재시도를 요청할 수 없습니다." /> : recoveryCase?.status === "RESOLVED" ? <InlineNotice title="복구가 해결되었습니다" description="원본 상태와 처리 내역에서 결과를 확인해 주세요." /> : null}
        {eligible ? <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); void retry(); }}><TextField label="재시도 사유" value={reason} onValueChange={setReason} maxLength={500} disabled={busy} required /><Button type="submit" loading={busy} disabled={!reason.trim()}>한 번 재시도 요청</Button></form> : null}
        <Button variant="secondary" disabled={busy} onClick={detail.reload}>복구 결과 다시 확인</Button>
      </> : null}
    </> : list.state.status === "loading" ? <LoadingState label="복구 내역을 불러오는 중" /> : list.state.status === "failed" ? <ErrorState error={list.state.error} retry={list.reload} /> : <>
      {list.state.value.items.length ? <div className="management-card-grid">{list.state.value.items.map(item => <article className="surface-card management-card" key={item.caseId}><StatusText state={item.status} label={caseLabels[item.status]} /><p className="support-case-reference">{item.targetId}</p><p>{item.reason}</p><p>{fullDateTime.format(new Date(item.updatedAt))}</p><Button variant="secondary" onClick={() => setSelected(item.targetId)}>복구 상세</Button></article>)}</div> : <EmptyState title="복구 내역이 없습니다" description="등록된 수동 복구 건이 없습니다." />}
      <div className="button-row"><Button variant="ghost" disabled={cursors.length < 2} onClick={() => setCursors(value => value.slice(0, -1))}>이전 복구 내역</Button><Button variant="secondary" disabled={!list.state.value.nextCursor} onClick={() => { if (list.state.status === "ready" && list.state.value.nextCursor) { const next = list.state.value.nextCursor; setCursors(value => [...value, next]); } }}>다음 복구 내역</Button><Button variant="ghost" onClick={list.reload}>복구 목록 새로고침</Button></div>
    </>}
  </section>;
}
