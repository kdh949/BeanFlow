import { AlertTriangle, ArrowLeft, CalendarDays, Clock3, PackageCheck, RefreshCw, RotateCcw, Store } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { useResource } from "../../features/shared/useResource";
import { shortDateTime, shortTime, won } from "../../lib/format";
import { storeOrderActionLabels, storeOrderBoardColumns, storeOrderElapsedLabel, storeOrderBoardLaneLabels } from "../../pages/console/storeOrderBoardModel";
import type { StoreOrderAction, StoreOrderBoardItem, StoreOrderBoardOverflow } from "../../pages/console/storeOrderBoardModel";
import { useStoreOrderBoard } from "../../pages/console/useStoreOrderBoard";
import { RefreshEmpty, RefreshError, RefreshLoading, RefreshPageHeading } from "./RefreshShared";
import { Button, ButtonLink, FeedbackState, QuantityStepper, SelectField, TextAreaField } from "../../design-system";
import { StatusText } from "../shared";

export function RefreshStoreOrderBoardPage({ now = new Date() }: { now?: Date }) {
  const state = useStoreOrderBoard();
  const allItems = state.board?.groups.flatMap((group) => group.items) ?? [];
  return (
    <div className="bfr-merchant-page bfr-board-page">
      <RefreshPageHeading title="주문 보드" description="실시간 주문을 상태별로 확인하고 처리합니다." action={state.selectedStore ? <span className="bfr-live"><span />3초마다 확인</span> : undefined} />
      <div className="bfr-board-toolbar">
        {state.membershipsLoading ? <RefreshLoading label="접근 가능한 매장을 확인하는 중" /> : null}
        {!state.membershipsLoading && state.stores.length > 1 ? <div><Store size={16} /><SelectField label="운영 매장" value={state.selectedStoreId ?? ""} onValueChange={state.selectStore}>{state.stores.map((store) => <option key={store.storeId} value={store.storeId}>{store.storeName}</option>)}</SelectField></div> : state.selectedStore ? <p className="bfr-selected-store"><Store size={16} />{state.selectedStore.storeName}</p> : null}
        <Button variant="secondary" onClick={state.retry} disabled={state.boardLoading}><RefreshCw size={15} />새로고침</Button>
      </div>
      {state.forbiddenStoreName ? <FeedbackState kind="error" title="매장 접근 권한이 변경되었습니다" description={`${state.forbiddenStoreName} 주문 보드를 더 이상 표시할 수 없습니다.`} /> : null}
      {state.notice ? <p className="bfr-board-notice" role="status" aria-label="주문 상태 갱신 안내"><RefreshCw size={15} />{state.notice}</p> : null}
      {state.boardLoading && !state.board && !state.forbiddenStoreName ? <RefreshLoading label="실행 주문을 불러오는 중" /> : null}
      {state.error ? <RefreshError error={state.error} retry={state.retry} /> : null}
      {!state.membershipsLoading && !state.error && !state.forbiddenStoreName && !state.stores.length ? <RefreshEmpty title="접근 가능한 매장이 없습니다" description="ACTIVE 상태의 매장 멤버십이 필요합니다." /> : null}
      {state.board && state.selectedStoreId ? <section className="bfr-order-board" aria-label={`${state.selectedStore?.storeName ?? "선택한 매장"} 실행 주문`}>
        {storeOrderBoardColumns.map((column) => {
          const items = allItems.filter((item) => item.lane && (column.lanes as readonly string[]).includes(item.lane));
          const overflow = state.board?.overflow.filter((entry) => (column.lanes as readonly string[]).includes(entry.lane)) ?? [];
          return <section className="bfr-board-lane" key={column.key} aria-labelledby={`bfr-lane-${column.key}`}><header><div><h2 id={`bfr-lane-${column.key}`}>{column.title}</h2><p>{column.description}</p></div><strong>{items.length}</strong></header><div>{!items.length ? <p className="bfr-lane-empty"><PackageCheck size={19} />대기 주문 없음</p> : null}{items.map((item) => <RefreshOrderCard key={item.orderReference} item={item} storeId={state.selectedStoreId as string} now={now} busy={state.busyReference === item.orderReference} rejecting={state.rejectingReference === item.orderReference} rejectionReason={state.rejectionReason} onBeginReject={() => state.beginReject(item.orderReference)} onCancelReject={state.cancelReject} onReasonChange={state.setRejectionReason} onAction={(action, reason) => void state.transition(item, action, reason)} />)}{overflow.map((entry) => <RefreshOverflow key={entry.lane} entry={entry} state={state} now={now} />)}</div></section>;
        })}
      </section> : null}
    </div>
  );
}

function RefreshOverflow({ entry, state, now }: { entry: StoreOrderBoardOverflow; state: ReturnType<typeof useStoreOrderBoard>; now: Date }) {
  const page = state.overflowPages[entry.lane];
  const loading = state.overflowLoadingLane === entry.lane;
  return <section className="bfr-overflow"><p>{storeOrderBoardLaneLabels[entry.lane]} 이전 작업 <strong>{entry.overflowCount}건</strong></p>{!page ? <Button variant="ghost" disabled={Boolean(state.overflowLoadingLane)} onClick={() => void state.loadOverflow(entry, entry.nextCursor, false)}>{loading ? "불러오는 중" : `오래된 ${storeOrderBoardLaneLabels[entry.lane]} 작업 ${entry.overflowCount}건 보기`}</Button> : <>{page.items.map((item) => <RefreshOrderCard key={item.orderReference} item={item} storeId={state.selectedStoreId as string} now={now} busy={state.busyReference === item.orderReference} rejecting={state.rejectingReference === item.orderReference} rejectionReason={state.rejectionReason} onBeginReject={() => state.beginReject(item.orderReference)} onCancelReject={state.cancelReject} onReasonChange={state.setRejectionReason} onAction={(action, reason) => void state.transition(item, action, reason)} />)}{page.nextCursor ? <Button variant="ghost" disabled={Boolean(state.overflowLoadingLane)} onClick={() => void state.loadOverflow(entry, page.nextCursor as string, true)}>이전 작업 더 보기</Button> : null}</>}</section>;
}

function RefreshOrderCard({ item, storeId, now, busy, rejecting, rejectionReason, onBeginReject, onCancelReject, onReasonChange, onAction }: { item: StoreOrderBoardItem; storeId: string; now: Date; busy: boolean; rejecting: boolean; rejectionReason: string; onBeginReject: () => void; onCancelReject: () => void; onReasonChange: (value: string) => void; onAction: (action: StoreOrderAction, reason?: string) => void }) {
  const elapsed = storeOrderElapsedLabel(item, now);
  return <article className={`bfr-order-card ${item.acceptancePhase === "WARNING" ? "is-warning" : ""}`} aria-label={`주문 ${item.pickupNumber}`}>
    <header><div><small>{item.orderReference}</small><strong>{item.pickupNumber}</strong></div><StatusText state={item.status} /></header>
    <p className="bfr-order-summary">{item.itemSummary}</p>
    <dl><div><dt><CalendarDays size={13} />픽업 영업일</dt><dd>{item.pickupBusinessDate}</dd></div><div><dt><Clock3 size={13} />픽업 시간</dt><dd>{shortDateTime.format(new Date(item.pickupWindowStart))}</dd></div>{elapsed ? <div><dt><Clock3 size={13} />현재 단계</dt><dd>{elapsed}</dd></div> : null}</dl>
    {item.acceptancePhase === "WARNING" ? <p className="bfr-card-warning"><AlertTriangle size={14} />접수 제한 시간이 얼마 남지 않았습니다.</p> : null}
    {item.acceptancePhase === "TIMEOUT_PENDING" ? <p className="bfr-card-warning"><AlertTriangle size={14} />자동 거절 처리를 확인 중입니다.</p> : null}
    <div className="bfr-card-actions">{item.allowedActions.map((action) => action === "REJECT" ? <Button key={action} size="sm" variant="danger" disabled={busy} onClick={onBeginReject}>{storeOrderActionLabels[action]}</Button> : <Button key={action} size="sm" variant="brand" loading={busy} onClick={() => onAction(action)}>{busy ? "처리 중" : storeOrderActionLabels[action]}</Button>)}<ButtonLink size="sm" variant="ghost" to={`/store/refunds/${storeId}/${item.orderReference}`}>부분 환불</ButtonLink></div>
    {rejecting ? <form className="bfr-reject" onSubmit={(event) => { event.preventDefault(); onAction("REJECT", rejectionReason.trim()); }}><TextAreaField label="거절 사유" value={rejectionReason} maxLength={500} required onValueChange={onReasonChange} /><div><Button size="sm" variant="ghost" onClick={onCancelReject}>취소</Button><Button size="sm" variant="secondary" type="submit" disabled={busy || !rejectionReason.trim()}>거절 확정</Button></div></form> : null}
  </article>;
}

type Preview = components["schemas"]["MerchantRefundPreview"];
type PreviewLine = components["schemas"]["MerchantRefundPreviewLine"];
type RefundResult = components["schemas"]["MerchantRefundResult"];
type Selection = Record<number, number>;

async function requestPreview(storeId: string, orderReference: string, selection: Selection): Promise<Preview> {
  const lines = Object.entries(selection).filter(([, quantity]) => quantity > 0).map(([lineSequence, quantity]) => ({ lineSequence: Number(lineSequence), quantity }));
  return unwrap(await merchantApi.POST("/stores/{storeId}/orders/{orderReference}/refund-previews", { params: { path: { storeId, orderReference }, header: await merchantCsrfHeader() }, body: lines.length ? { lines } : {} }));
}

export function RefreshStoreRefundPage() {
  const { storeId = "", orderReference = "" } = useParams();
  const [selection, setSelection] = useState<Selection>({});
  const [preview, setPreview] = useState<Preview | null>(null);
  const [reason, setReason] = useState("");
  const [result, setResult] = useState<RefundResult | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [stale, setStale] = useState(false);
  const intent = useRef(new SubmissionIntent());
  const generation = useRef(0);
  const resource = useResource<Preview>(useCallback(() => requestPreview(storeId, orderReference, {}), [storeId, orderReference]));
  const current = preview ?? (resource.state.status === "ready" ? resource.state.value : null);

  async function reprice(next: Selection) {
    setSelection(next); setFailure(null); const currentGeneration = ++generation.current;
    try { const updated = await requestPreview(storeId, orderReference, next); if (currentGeneration === generation.current) setPreview(updated); }
    catch (error) { if (currentGeneration === generation.current) setFailure(error); }
  }
  function change(line: PreviewLine, quantity: number) { const bounded = Math.max(0, Math.min(quantity, line.remainingQuantity)); intent.current.rotate(); void reprice({ ...selection, [line.lineSequence]: bounded }); }
  async function refresh() { setStale(false); intent.current.rotate(); await reprice(selection); }
  async function submit() {
    if (!current) return;
    const lines = current.lines.filter((line) => line.selectedQuantity > 0).map((line) => ({ lineSequence: line.lineSequence, quantity: line.selectedQuantity }));
    if (!lines.length) return;
    const fingerprint = JSON.stringify({ storeId, orderReference, lines, reason: reason.trim() });
    setSubmitting(true); setFailure(null); setStale(false);
    try {
      const response = unwrap(await merchantApi.POST("/stores/{storeId}/orders/{orderReference}/refunds", { params: { path: { storeId, orderReference }, header: { "Idempotency-Key": intent.current.keyFor(fingerprint), ...(await merchantCsrfHeader()) } }, body: { lines, previewVersion: current.previewVersion, reason: reason.trim() } }));
      setResult(response); intent.current.complete(); setSelection({}); await reprice({});
    } catch (error) {
      if (error instanceof ApiRequestError && error.code === "REFUND_PREVIEW_STALE") { setStale(true); intent.current.rotate(); await reprice(selection); }
      else { if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") intent.current.rotate(); setFailure(error); }
    } finally { setSubmitting(false); }
  }

  if (resource.state.status === "loading" && !preview) return <div className="bfr-merchant-page"><RefreshLoading label="환불 가능 품목을 불러오는 중" /></div>;
  if (resource.state.status === "failed" && !preview) return <div className="bfr-merchant-page"><RefreshError error={resource.state.error} retry={resource.reload} /></div>;
  if (!current) return <div className="bfr-merchant-page"><RefreshLoading label="환불 가능 품목을 불러오는 중" /></div>;
  const selectedTotal = current.totals.cashRefundKrw + current.totals.pointsRestorationKrw;
  return <div className="bfr-merchant-page bfr-refund-page">
    <Link className="bfr-back-link" to="/store"><ArrowLeft size={16} />주문 관리로</Link>
    <RefreshPageHeading title="부분 환불" description={`주문 ${current.orderReference} · 품목과 수량을 선택하면 서버가 최신 환불 금액을 다시 계산합니다.`} />
    {result ? <RefundOutcome result={result} /> : null}
    <section className="bfr-refund-context"><header><div><h2>환불 대상 주문</h2></div><StatusText state={current.orderContext.status} /></header><dl><div><dt>주문 시각</dt><dd>{shortDateTime.format(new Date(current.orderContext.orderedAt))}</dd></div><div><dt>픽업 시간</dt><dd>{shortDateTime.format(new Date(current.orderContext.pickupWindow.startsAt))}–{shortTime.format(new Date(current.orderContext.pickupWindow.endsAt))}</dd></div><div><dt>결제 방식</dt><dd>{current.orderContext.paymentKind === "ONE_TIME_EXTERNAL" ? "일회성 결제" : "혜택 전액 사용"}</dd></div><div><dt>결제 금액</dt><dd>{won.format(current.orderContext.pricing.payableKrw)}</dd></div></dl><p>환불 판단과 실행에 필요한 주문 정보만 표시합니다.</p></section>
    <div className="bfr-refund-workspace">
      <section className="bfr-refund-lines"><header><h2>환불 품목</h2><span>서버 계산 금액</span></header>{current.lines.map((line) => <article key={line.lineSequence}><div><strong>{line.menuName}</strong><small>남은 환불 가능 {line.remainingQuantity}개</small></div><QuantityStepper value={selection[line.lineSequence] ?? line.selectedQuantity} min={0} max={line.remainingQuantity} label={`${line.menuName} 환불 수량`} onChange={(value) => change(line, value)} /><dl><div><dt>현금</dt><dd>{won.format(line.cashRefundKrw)}</dd></div><div><dt>포인트</dt><dd>{won.format(line.pointsRestorationKrw)}</dd></div><div><dt>쿠폰 귀속</dt><dd>{won.format(line.couponAttributionKrw)}</dd></div></dl></article>)}</section>
      <aside className="bfr-refund-side">
        <section className="bfr-refund-summary"><div><span>현금 환불</span><strong>{won.format(current.totals.cashRefundKrw)}</strong></div><div><span>포인트 복원</span><strong>{won.format(current.totals.pointsRestorationKrw)}</strong></div><p>쿠폰 귀속액 {won.format(current.totals.couponAttributionKrw)}은 쿠폰 복원을 의미하지 않습니다.</p></section>
        {!current.lines.some((line) => line.remainingQuantity > 0) ? <p className="bfr-refund-alert" role="status">이 주문에는 남은 환불 가능 수량이 없습니다.</p> : null}
        {stale ? <p className="bfr-refund-alert" role="alert">다른 요청이 먼저 처리되어 금액이 바뀌었습니다. 새 금액을 확인한 뒤 다시 실행해 주세요.</p> : null}
        {failure ? <RefreshError error={failure} retry={() => void refresh()} /> : null}
      </aside>
    </div>
    <form className="bfr-refund-form" onSubmit={(event) => { event.preventDefault(); void submit(); }}><TextAreaField label="환불 사유" value={reason} required maxLength={500} placeholder="환불 사유를 입력해 주세요" onValueChange={(value) => { setReason(value); intent.current.rotate(); }} /><div><Button variant="secondary" type="button" disabled={submitting} onClick={() => void refresh()}>금액 다시 계산</Button><Button variant="brand" type="submit" loading={submitting} disabled={selectedTotal <= 0 || !reason.trim()}><RotateCcw size={16} />부분 환불 실행</Button></div></form>
  </div>;
}

export function RefundOutcome({ result }: { result: RefundResult }) {
  const copy = refundOutcomeCopy(result);
  return <section className="bfr-refund-outcome" role="status"><StatusText state={result.state} /><div><h2>{copy[0]}</h2><p>{copy[1]}</p><small>문의 코드 {result.correlationId}</small></div></section>;
}
function refundOutcomeCopy(result: RefundResult): [string, string] {
  switch (result.state) {
    case "SUCCEEDED": return ["현금 환불이 확인되었습니다", `${won.format(result.cashRefundedKrw ?? result.cashRefundRequestedKrw)} 환불이 확정되었습니다.`];
    case "FAILED": return ["환불에 실패했습니다", "문의 코드를 남기고 운영팀에 알려 주세요. 같은 요청을 다시 보내도 새 환불이 만들어지지 않습니다."];
    case "MANUAL_REVIEW": return ["운영팀 확인이 필요합니다", "자동 처리로 결과를 확정하지 못했습니다. 같은 요청을 다시 보내지 않아도 됩니다."];
    case "REQUESTED": case "PROCESSING": case "RETRY_SCHEDULED": case "UNKNOWN": case "RECONCILING": return ["환불 결과를 확인하고 있습니다", "아직 성공도 실패도 아닙니다. 같은 요청을 다시 보내도 새 환불이 만들어지지 않습니다."];
  }
}
