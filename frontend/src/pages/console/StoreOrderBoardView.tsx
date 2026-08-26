import { AlertTriangle, CalendarDays, Clock3, PackageCheck } from "lucide-react";
import { StatusBadge } from "../../components/Ui";
import { Button, ButtonLink } from "../../design-system";
import { shortDateTime } from "../../lib/format";
import {
  storeOrderActionLabels,
  storeOrderBoardColumns,
  storeOrderElapsedLabel,
  storeOrderBoardLaneLabels,
} from "./storeOrderBoardModel";
import type {
  StoreOrderAction,
  StoreOrderBoard,
  StoreOrderBoardItem,
  StoreOrderBoardLane,
  StoreOrderBoardOverflow,
  StoreOrderBoardOverflowPage,
} from "./storeOrderBoardModel";

type BoardColumn = (typeof storeOrderBoardColumns)[number];
type OverflowPage = Pick<StoreOrderBoardOverflowPage, "items" | "nextCursor">;

type OrderInteractionProps = {
  storeId: string;
  busyReference: string | null;
  rejectingReference: string | null;
  rejectionReason: string;
  onBeginReject: (orderReference: string) => void;
  onCancelReject: () => void;
  onReasonChange: (value: string) => void;
  onAction: (item: StoreOrderBoardItem, action: StoreOrderAction, reason?: string) => void;
};

export function StoreOrderBoardView({
  board,
  storeId,
  storeName,
  now,
  overflowPages,
  overflowLoadingLane,
  onLoadOverflow,
  ...interactions
}: OrderInteractionProps & {
  board: StoreOrderBoard;
  storeName: string;
  now: Date;
  overflowPages: Partial<Record<StoreOrderBoardLane, OverflowPage>>;
  overflowLoadingLane: StoreOrderBoardLane | null;
  onLoadOverflow: (overflow: StoreOrderBoardOverflow, cursor: string, append: boolean) => void;
}) {
  const allItems = board.groups.flatMap((group) => group.items);
  return (
    <section className="order-board" aria-label={`${storeName} 실행 주문`}>
      {storeOrderBoardColumns.map((column) => (
        <OrderBoardColumn
          key={column.key}
          column={column}
          items={allItems.filter((item) => item.lane && (column.lanes as readonly string[]).includes(item.lane))}
          overflow={board.overflow.filter((entry) => (column.lanes as readonly string[]).includes(entry.lane))}
          overflowPages={overflowPages}
          overflowLoadingLane={overflowLoadingLane}
          onLoadOverflow={onLoadOverflow}
          storeId={storeId}
          now={now}
          {...interactions}
        />
      ))}
    </section>
  );
}

export function OrderBoardColumn({
  column,
  items,
  overflow,
  overflowPages,
  overflowLoadingLane,
  onLoadOverflow,
  now,
  ...interactions
}: OrderInteractionProps & {
  column: BoardColumn;
  items: StoreOrderBoardItem[];
  overflow: StoreOrderBoardOverflow[];
  overflowPages: Partial<Record<StoreOrderBoardLane, OverflowPage>>;
  overflowLoadingLane: StoreOrderBoardLane | null;
  onLoadOverflow: (entry: StoreOrderBoardOverflow, cursor: string, append: boolean) => void;
  now: Date;
}) {
  return (
    <section className="order-board-column" aria-labelledby={`board-column-${column.key}`}>
      <header>
        <div><h2 id={`board-column-${column.key}`}>{column.title}</h2><p>{column.description}</p></div>
        <strong aria-label={`${column.title} ${items.length}건`}>{items.length}</strong>
      </header>
      <div className="order-board-cards">
        {items.length === 0 ? <div className="order-column-empty"><PackageCheck size={20} /><span>대기 주문 없음</span></div> : null}
        {items.map((item) => (
          <StoreOrderCard
            key={item.orderReference}
            item={item}
            busy={interactions.busyReference === item.orderReference}
            rejecting={interactions.rejectingReference === item.orderReference}
            rejectionReason={interactions.rejectionReason}
            onRejectStart={() => interactions.onBeginReject(item.orderReference)}
            onRejectCancel={interactions.onCancelReject}
            onReasonChange={interactions.onReasonChange}
            onAction={(action, reason) => interactions.onAction(item, action, reason)}
            storeId={interactions.storeId}
            now={now}
          />
        ))}
        {overflow.map((entry) => (
          <OrderBoardOverflowSection
            key={entry.lane}
            overflow={entry}
            page={overflowPages[entry.lane]}
            loading={overflowLoadingLane === entry.lane}
            loadingDisabled={Boolean(overflowLoadingLane)}
            onLoad={(cursor, append) => onLoadOverflow(entry, cursor, append)}
            {...interactions}
            now={now}
          />
        ))}
      </div>
    </section>
  );
}

export function OrderBoardOverflowSection({
  overflow,
  page,
  loading,
  loadingDisabled,
  onLoad,
  now,
  ...interactions
}: OrderInteractionProps & {
  overflow: StoreOrderBoardOverflow;
  page?: OverflowPage;
  loading: boolean;
  loadingDisabled: boolean;
  onLoad: (cursor: string, append: boolean) => void;
  now: Date;
}) {
  const laneLabel = storeOrderBoardLaneLabels[overflow.lane];
  return (
    <section className="order-board-overflow" aria-label={`${laneLabel} 이전 작업`}>
      <div className="order-board-overflow-summary">
        <p>{laneLabel} 이전 작업 <strong>{overflow.overflowCount}건</strong></p>
        {!page ? (
          <Button
            variant="ghost"
            type="button"
            disabled={loadingDisabled}
            onClick={() => onLoad(overflow.nextCursor, false)}
          >
            {loading ? "불러오는 중" : `오래된 ${laneLabel} 작업 ${overflow.overflowCount}건 보기`}
          </Button>
        ) : null}
      </div>
      {page ? (
        <div className="order-board-overflow-cards">
          {page.items.map((item) => (
            <StoreOrderCard
              key={item.orderReference}
              item={item}
              busy={interactions.busyReference === item.orderReference}
              rejecting={interactions.rejectingReference === item.orderReference}
              rejectionReason={interactions.rejectionReason}
              onRejectStart={() => interactions.onBeginReject(item.orderReference)}
              onRejectCancel={interactions.onCancelReject}
              onReasonChange={interactions.onReasonChange}
              onAction={(action, reason) => interactions.onAction(item, action, reason)}
              storeId={interactions.storeId}
              now={now}
            />
          ))}
          {page.nextCursor ? (
            <Button variant="ghost" type="button" disabled={loadingDisabled} onClick={() => onLoad(page.nextCursor as string, true)}>
              {loading ? "불러오는 중" : "이전 작업 더 보기"}
            </Button>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

export function StoreOrderCard({
  item,
  storeId,
  busy,
  rejecting,
  rejectionReason,
  onRejectStart,
  onRejectCancel,
  onReasonChange,
  onAction,
  now,
}: {
  item: StoreOrderBoardItem;
  storeId: string;
  busy: boolean;
  rejecting: boolean;
  rejectionReason: string;
  onRejectStart: () => void;
  onRejectCancel: () => void;
  onReasonChange: (value: string) => void;
  onAction: (action: StoreOrderAction, reason?: string) => void;
  now: Date;
}) {
  const elapsed = storeOrderElapsedLabel(item, now);
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
        {elapsed ? <div><dt><Clock3 size={15} /> 현재 단계</dt><dd>{elapsed}</dd></div> : null}
      </dl>
      {item.acceptancePhase === "WARNING" ? <p className="acceptance-warning"><AlertTriangle size={15} /> 접수 제한 시간이 얼마 남지 않았습니다.</p> : null}
      {item.acceptancePhase === "TIMEOUT_PENDING" ? <p className="acceptance-warning"><AlertTriangle size={15} /> 자동 거절 처리를 확인 중입니다.</p> : null}
      <div className="order-card-actions">
        {item.allowedActions.map((action) => action === "REJECT" ? (
          <Button key={action} variant="danger" type="button" disabled={busy} onClick={onRejectStart}>{storeOrderActionLabels[action]}</Button>
        ) : (
          <Button key={action} type="button" loading={busy} onClick={() => onAction(action)}>{busy ? "처리 중" : storeOrderActionLabels[action]}</Button>
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
