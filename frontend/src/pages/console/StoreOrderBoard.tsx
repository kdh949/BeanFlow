import { RefreshCw, Store } from "lucide-react";
import { EmptyState, ErrorState, LoadingState } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { FeedbackState } from "../../design-system";
import { StoreOrderBoardView } from "./StoreOrderBoardView";
import { useStoreOrderBoard } from "./useStoreOrderBoard";

export { reconcileBoardItem } from "./storeOrderBoardModel";

export function StoreOrderBoardPage({ now = new Date() }: { now?: Date }) {
  const {
    stores, selectedStoreId, selectedStore, membershipsLoading, board, boardLoading, error,
    forbiddenStoreName, notice, busyReference, rejectingReference, rejectionReason,
    overflowPages, overflowLoadingLane, selectStore, beginReject, cancelReject,
    setRejectionReason, retry, transition, loadOverflow,
  } = useStoreOrderBoard();

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
        <StoreOrderBoardView
          board={board}
          storeId={selectedStoreId}
          storeName={selectedStore?.storeName ?? "선택한 매장"}
          now={now}
          busyReference={busyReference}
          rejectingReference={rejectingReference}
          rejectionReason={rejectionReason}
          overflowPages={overflowPages}
          overflowLoadingLane={overflowLoadingLane}
          onBeginReject={beginReject}
          onCancelReject={cancelReject}
          onReasonChange={setRejectionReason}
          onAction={(item, action, reason) => void transition(item, action, reason)}
          onLoadOverflow={(entry, cursor, append) => void loadOverflow(entry, cursor, append)}
        />
      ) : null}
    </div>
  );
}
