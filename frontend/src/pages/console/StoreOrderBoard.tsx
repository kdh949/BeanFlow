import { AlertTriangle, CalendarDays, Clock3, PackageCheck, RefreshCw, Store } from "lucide-react";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { shortDateTime } from "../../lib/format";
import { Button, ButtonLink, FeedbackState } from "../../design-system";
import {
  storeOrderActionLabels as actionLabels,
  storeOrderBoardColumns as columns,
  storeOrderBoardLaneLabels as laneLabels,
} from "./storeOrderBoardModel";
import type {
  StoreOrderAction as BoardAction,
  StoreOrderBoardItem as BoardItem,
} from "./storeOrderBoardModel";
import { useStoreOrderBoard } from "./useStoreOrderBoard";

export { reconcileBoardItem } from "./storeOrderBoardModel";

export function StoreOrderBoardPage() {
  const {
    stores, selectedStoreId, selectedStore, membershipsLoading, board, boardLoading, error,
    forbiddenStoreName, notice, busyReference, rejectingReference, rejectionReason,
    overflowPages, overflowLoadingLane, selectStore, beginReject, cancelReject,
    setRejectionReason, retry, transition, loadOverflow,
  } = useStoreOrderBoard();

  const allItems = board?.groups.flatMap((group) => group.items) ?? [];

  return (
    <div className="console-page store-board-page">
      <PageTitle
        eyebrow="STORE WORKSPACE"
        title="실행 주문 보드"
        description="픽업 날짜와 관계없이 지금 처리할 주문을 서버 상태 기준으로 표시합니다."
        action={selectedStore ? <span className="board-live-indicator"><span /> 3초마다 확인</span> : undefined}
      />

      {membershipsLoading ? <LoadingState label="접근 가능한 매장을 확인하는 중" /> : null}
      {!membershipsLoading && stores.length === 1 && selectedStore && !forbiddenStoreName ? (
        <div className="store-selection-single"><Store size={18} /><div><small>운영 매장</small><strong>{selectedStore.storeName}</strong></div></div>
      ) : null}
      {!membershipsLoading && (stores.length > 1 || Boolean(forbiddenStoreName && stores.length)) ? (
        <label className="store-selector" htmlFor="store-order-board-store">
          <span><Store size={18} /> 운영 매장</span>
          <select id="store-order-board-store" aria-label="운영 매장" value={selectedStoreId ?? ""} onChange={(event) => selectStore(event.target.value)}>
            <option value="" disabled>매장을 선택하세요</option>
            {stores.map((store) => <option key={store.storeId} value={store.storeId}>{store.storeName}</option>)}
          </select>
        </label>
      ) : null}

      {forbiddenStoreName ? (
        <FeedbackState
          kind="error"
          title="매장 접근 권한이 변경되었습니다"
          description={`${forbiddenStoreName} 주문 보드를 더 이상 표시할 수 없습니다. 접근 가능한 매장을 다시 선택해 주세요.`}
        />
      ) : null}
      {notice ? <div className="board-notice" role="status" aria-label="주문 상태 갱신 안내"><RefreshCw size={17} />{notice}</div> : null}
      {boardLoading && !board && !forbiddenStoreName ? <LoadingState label="실행 주문을 불러오는 중" /> : null}
      {error ? <ErrorState error={error} retry={retry} /> : null}
      {!membershipsLoading && !error && !forbiddenStoreName && stores.length === 0 ? (
        <EmptyState title="접근 가능한 매장이 없습니다" description="ACTIVE 상태의 매장 멤버십이 필요합니다." />
      ) : null}

      {board && selectedStoreId ? (
        <section className="order-board" aria-label={`${selectedStore?.storeName ?? "선택한 매장"} 실행 주문`}>
          {columns.map((column) => {
            const items = allItems.filter((item) => item.lane && (column.lanes as readonly string[]).includes(item.lane));
            const overflow = board.overflow.filter((entry) => (column.lanes as readonly string[]).includes(entry.lane));
            return (
              <section className="order-board-column" key={column.key} aria-labelledby={`board-column-${column.key}`}>
                <header>
                  <div><h2 id={`board-column-${column.key}`}>{column.title}</h2><p>{column.description}</p></div>
                  <strong aria-label={`${column.title} ${items.length}건`}>{items.length}</strong>
                </header>
                <div className="order-board-cards">
                  {items.length === 0 ? <div className="order-column-empty"><PackageCheck size={20} /><span>대기 주문 없음</span></div> : null}
                  {items.map((item) => (
                    <OrderCard
                      key={item.orderReference}
                      item={item}
                      storeId={selectedStoreId}
                      busy={busyReference === item.orderReference}
                      rejecting={rejectingReference === item.orderReference}
                      rejectionReason={rejectionReason}
                      onRejectStart={() => beginReject(item.orderReference)}
                      onRejectCancel={cancelReject}
                      onReasonChange={setRejectionReason}
                      onAction={(action, reason) => void transition(item, action, reason)}
                    />
                  ))}
                  {overflow.map((entry) => {
                    const page = overflowPages[entry.lane];
                    const loading = overflowLoadingLane === entry.lane;
                    const laneLabel = laneLabels[entry.lane];
                    return (
                      <section className="order-board-overflow" key={entry.lane} aria-label={`${laneLabel} 이전 작업`}>
                        <div className="order-board-overflow-summary">
                          <p>{laneLabel} 이전 작업 <strong>{entry.overflowCount}건</strong></p>
                          {!page ? (
                            <Button
                              variant="ghost"
                              type="button"
                              disabled={Boolean(overflowLoadingLane)}
                              onClick={() => void loadOverflow(entry, entry.nextCursor, false)}
                            >
                              {loading ? "불러오는 중" : `오래된 ${laneLabel} 작업 ${entry.overflowCount}건 보기`}
                            </Button>
                          ) : null}
                        </div>
                        {page ? (
                          <div className="order-board-overflow-cards">
                            {page.items.map((item) => (
                              <OrderCard
                                key={item.orderReference}
                                item={item}
                                storeId={selectedStoreId}
                                busy={busyReference === item.orderReference}
                                rejecting={rejectingReference === item.orderReference}
                                rejectionReason={rejectionReason}
                                onRejectStart={() => beginReject(item.orderReference)}
                                onRejectCancel={cancelReject}
                                onReasonChange={setRejectionReason}
                                onAction={(action, reason) => void transition(item, action, reason)}
                              />
                            ))}
                            {page.nextCursor ? (
                              <Button
                                variant="ghost"
                                type="button"
                                disabled={Boolean(overflowLoadingLane)}
                                onClick={() => void loadOverflow(entry, page.nextCursor as string, true)}
                              >
                                {loading ? "불러오는 중" : "이전 작업 더 보기"}
                              </Button>
                            ) : null}
                          </div>
                        ) : null}
                      </section>
                    );
                  })}
                </div>
              </section>
            );
          })}
        </section>
      ) : null}
    </div>
  );
}

function OrderCard({ item, storeId, busy, rejecting, rejectionReason, onRejectStart, onRejectCancel, onReasonChange, onAction }: {
  item: BoardItem;
  storeId: string;
  busy: boolean;
  rejecting: boolean;
  rejectionReason: string;
  onRejectStart: () => void;
  onRejectCancel: () => void;
  onReasonChange: (value: string) => void;
  onAction: (action: BoardAction, reason?: string) => void;
}) {
  return (
    <article className={`order-board-card ${item.acceptancePhase === "WARNING" ? "is-warning" : ""}`} aria-label={`주문 ${item.pickupNumber}`}>
      <div className="order-card-heading">
        <div><small>{item.orderReference}</small><strong>{item.pickupNumber}</strong></div>
        <StatusBadge state={item.status} />
      </div>
      <p className="order-card-summary">{item.itemSummary}</p>
      <dl className="order-card-time">
        <div><dt><CalendarDays size={15} /> 픽업 영업일</dt><dd>{item.pickupBusinessDate}</dd></div>
        <div><dt><Clock3 size={15} /> 픽업 시간</dt><dd>{shortDateTime.format(new Date(item.pickupWindowStart))}</dd></div>
      </dl>
      {item.acceptancePhase === "WARNING" ? <p className="acceptance-warning"><AlertTriangle size={15} /> 접수 제한 시간이 얼마 남지 않았습니다.</p> : null}
      {item.acceptancePhase === "TIMEOUT_PENDING" ? <p className="acceptance-warning"><AlertTriangle size={15} /> 자동 거절 처리를 확인 중입니다.</p> : null}
      <div className="order-card-actions">
        {item.allowedActions.map((action) => action === "REJECT" ? (
          <Button key={action} variant="danger" type="button" disabled={busy} onClick={onRejectStart}>{actionLabels[action]}</Button>
        ) : (
          <Button key={action} type="button" loading={busy} onClick={() => onAction(action)}>{busy ? "처리 중" : actionLabels[action]}</Button>
        ))}
        <ButtonLink variant="ghost" to={`/store/refunds/${storeId}/${item.orderReference}`}>부분 환불</ButtonLink>
      </div>
      {rejecting ? (
        <form className="order-reject-form" onSubmit={(event) => { event.preventDefault(); onAction("REJECT", rejectionReason.trim()); }}>
          <label htmlFor={`reject-${item.orderReference}`}>거절 사유</label>
          <textarea id={`reject-${item.orderReference}`} value={rejectionReason} onChange={(event) => onReasonChange(event.target.value)} required maxLength={500} />
          <div><Button variant="ghost" type="button" onClick={onRejectCancel}>취소</Button><Button variant="secondary" type="submit" disabled={busy || !rejectionReason.trim()}>거절 확정</Button></div>
        </form>
      ) : null}
    </article>
  );
}
