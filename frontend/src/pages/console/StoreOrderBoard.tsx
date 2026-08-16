import { AlertTriangle, CalendarDays, Clock3, PackageCheck, RefreshCw, Store } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { merchantApi, merchantCsrfToken } from "../../api/consoleClient";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { shortDateTime } from "../../lib/format";
import { Button, FeedbackState } from "../../design-system";

type MerchantStore = components["schemas"]["MerchantStore"];
type Board = components["schemas"]["StoreOrderBoard"];
type BoardItem = components["schemas"]["StoreOrderBoardItem"];
type BoardOverflow = components["schemas"]["StoreOrderBoardOverflow"];
type BoardOverflowPage = components["schemas"]["StoreOrderBoardOverflowPage"];
type BoardAction = components["schemas"]["StoreOrderAction"];
type ExpectedStatus = components["schemas"]["StoreOrderActionRequest"]["expectedStatus"];
type BoardLane = BoardOverflow["lane"];
type OverflowPageState = Pick<BoardOverflowPage, "items" | "nextCursor">;

const POLL_INTERVAL_MS = 3_000;
const columns = [
  { key: "acceptance", title: "접수 대기", description: "결제 완료 후 매장 확인 대기", lanes: ["PENDING_ACCEPTANCE"] },
  { key: "preparing", title: "제조 중", description: "접수 완료 및 제조 진행", lanes: ["ACCEPTED", "PREPARING"] },
  { key: "ready", title: "준비 완료", description: "고객 픽업 대기", lanes: ["READY"] },
] as const;

const actionLabels: Record<BoardAction, string> = {
  ACCEPT: "주문 접수",
  REJECT: "주문 거절",
  START_PREPARING: "제조 시작",
  MARK_READY: "준비 완료",
  COMPLETE: "픽업 완료",
};

const laneLabels: Record<BoardLane, string> = {
  PENDING_ACCEPTANCE: "접수 대기",
  ACCEPTED: "접수 완료",
  PREPARING: "제조 중",
  READY: "준비 완료",
};

async function requestStores(): Promise<MerchantStore[]> {
  return unwrap(await merchantApi.GET("/merchant/me/stores"));
}

async function requestBoard(storeId: string, etag: string | null) {
  const result = await merchantApi.GET("/stores/{storeId}/orders", {
    params: {
      path: { storeId },
      header: etag ? { "If-None-Match": etag } : undefined,
    },
  });
  if (result.response.status === 304) {
    return { board: null, etag, unchanged: true } as const;
  }
  return {
    board: unwrap(result),
    etag: result.response.headers.get("ETag"),
    unchanged: false,
  } as const;
}

async function requestOverflow(storeId: string, lane: BoardLane, cursor: string): Promise<BoardOverflowPage> {
  return unwrap(await merchantApi.GET("/stores/{storeId}/orders/overflow", {
    params: { path: { storeId }, query: { lane, cursor } },
  }));
}

function sortedBoard(groups: Board["groups"], overflow: Board["overflow"]): Board {
  return {
    groups: [...groups]
      .map((group) => ({
        ...group,
        items: [...group.items].sort((left, right) =>
          left.pickupWindowStart.localeCompare(right.pickupWindowStart) || left.orderReference.localeCompare(right.orderReference)),
      }))
      .filter((group) => group.items.length > 0)
      .sort((left, right) => left.pickupBusinessDate.localeCompare(right.pickupBusinessDate)),
    overflow,
  };
}

export function reconcileBoardItem(board: Board, changed: BoardItem): Board {
  const groups = board.groups.map((group) => ({
    ...group,
    items: group.items.filter((item) => item.orderReference !== changed.orderReference),
  }));
  if (changed.lane) {
    const existing = groups.find((group) => group.pickupBusinessDate === changed.pickupBusinessDate);
    if (existing) existing.items.push(changed);
    else groups.push({ pickupBusinessDate: changed.pickupBusinessDate, items: [changed] });
  }
  return sortedBoard(groups, board.overflow);
}

export function StoreOrderBoardPage() {
  const [stores, setStores] = useState<MerchantStore[]>([]);
  const [selectedStoreId, setSelectedStoreId] = useState<string | null>(null);
  const [membershipsLoading, setMembershipsLoading] = useState(true);
  const [board, setBoard] = useState<Board | null>(null);
  const [boardLoading, setBoardLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [forbiddenStoreName, setForbiddenStoreName] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [membershipRefreshNonce, setMembershipRefreshNonce] = useState(0);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [busyReference, setBusyReference] = useState<string | null>(null);
  const [rejectingReference, setRejectingReference] = useState<string | null>(null);
  const [rejectionReason, setRejectionReason] = useState("");
  const [overflowPages, setOverflowPages] = useState<Partial<Record<BoardLane, OverflowPageState>>>({});
  const [overflowLoadingLane, setOverflowLoadingLane] = useState<BoardLane | null>(null);
  const etagRef = useRef<string | null>(null);
  const transitionIntent = useRef(new SubmissionIntent());

  const selectedStore = useMemo(
    () => stores.find((store) => store.storeId === selectedStoreId) ?? null,
    [selectedStoreId, stores],
  );

  useEffect(() => {
    let disposed = false;
    void (async () => {
      try {
        const activeStores = await requestStores();
        if (disposed) return;
        setStores(activeStores);
        setSelectedStoreId(activeStores[0]?.storeId ?? null);
        setForbiddenStoreName(null);
        setError(null);
      } catch (failure) {
        if (disposed) return;
        setStores([]);
        setSelectedStoreId(null);
        setError(failure);
      } finally {
        if (!disposed) setMembershipsLoading(false);
      }
    })();
    return () => { disposed = true; };
  }, [membershipRefreshNonce]);

  useEffect(() => {
    if (!selectedStoreId) return;
    let disposed = false;
    let inFlight = false;
    let interval: ReturnType<typeof setInterval> | undefined;
    etagRef.current = null;
    setBoard(null);
    setBoardLoading(true);
    setError(null);
    setForbiddenStoreName(null);

    async function load(conditional: boolean) {
      if (inFlight || disposed) return;
      inFlight = true;
      try {
        const snapshot = await requestBoard(selectedStoreId as string, conditional ? etagRef.current : null);
        if (disposed) return;
        if (!snapshot.unchanged) {
          setBoard(snapshot.board);
          setOverflowPages({});
          etagRef.current = snapshot.etag;
        }
        setError(null);
      } catch (failure) {
        if (disposed) return;
        setBoard(null);
        etagRef.current = null;
        if (failure instanceof ApiRequestError && failure.status === 403) {
          const revokedName = stores.find((store) => store.storeId === selectedStoreId)?.storeName ?? "선택한 매장";
          let activeStores: MerchantStore[] = [];
          let membershipFailure: unknown = null;
          try {
            activeStores = await requestStores();
          } catch (refreshFailure) {
            membershipFailure = refreshFailure;
          }
          if (disposed) return;
          setStores(activeStores);
          setForbiddenStoreName(revokedName);
          setSelectedStoreId(null);
          setError(membershipFailure);
        } else {
          setError(failure);
        }
      } finally {
        inFlight = false;
        if (!disposed) setBoardLoading(false);
      }
    }

    function startPolling() {
      if (interval) clearInterval(interval);
      interval = undefined;
      if (document.visibilityState !== "visible") return;
      void load(true);
      interval = setInterval(() => void load(true), POLL_INTERVAL_MS);
    }

    void load(false);
    if (document.visibilityState === "visible") {
      interval = setInterval(() => void load(true), POLL_INTERVAL_MS);
    }
    document.addEventListener("visibilitychange", startPolling);
    return () => {
      disposed = true;
      if (interval) clearInterval(interval);
      document.removeEventListener("visibilitychange", startPolling);
    };
  }, [refreshNonce, selectedStoreId]);

  function selectStore(storeId: string) {
    setSelectedStoreId(storeId);
    setNotice(null);
    setForbiddenStoreName(null);
    setRejectingReference(null);
    setRejectionReason("");
    setOverflowPages({});
  }

  async function transition(item: BoardItem, action: BoardAction, reason?: string) {
    if (!selectedStoreId || busyReference) return;
    const fingerprint = JSON.stringify({ selectedStoreId, orderReference: item.orderReference, action, expectedStatus: item.status, reason });
    setBusyReference(item.orderReference);
    setError(null);
    setNotice(null);
    try {
      const csrf = await merchantCsrfToken();
      const result = await merchantApi.POST("/stores/{storeId}/orders/{orderReference}/transitions", {
        params: {
          path: { storeId: selectedStoreId, orderReference: item.orderReference },
          header: {
            "Idempotency-Key": transitionIntent.current.keyFor(fingerprint),
            "X-BEANFLOW-CSRF": csrf,
          },
        },
        body: { action, expectedStatus: item.status as ExpectedStatus, reason },
      });
      const changed = unwrap(result);
      transitionIntent.current.complete();
      setBoard((current) => current ? reconcileBoardItem(current, changed) : current);
      setOverflowPages({});
      etagRef.current = null;
      setRejectingReference(null);
      setRejectionReason("");
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.status === 409 && failure.code === "ORDER_STATE_CONFLICT") {
        transitionIntent.current.complete();
        setBoard(null);
        etagRef.current = null;
        setNotice("다른 작업자가 먼저 처리했습니다. 최신 주문 보드로 갱신했습니다.");
        setRefreshNonce((value) => value + 1);
      } else {
        if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") {
          transitionIntent.current.rotate();
        }
        if (failure instanceof ApiRequestError && failure.status === 403) {
          setBoard(null);
          etagRef.current = null;
        }
        setError(failure);
      }
    } finally {
      setBusyReference(null);
    }
  }

  async function loadOverflow(overflow: BoardOverflow, cursor: string, append: boolean) {
    if (!selectedStoreId || overflowLoadingLane) return;
    setOverflowLoadingLane(overflow.lane);
    setError(null);
    setNotice(null);
    try {
      const page = await requestOverflow(selectedStoreId, overflow.lane, cursor);
      setOverflowPages((current) => {
        const existingItems = append ? current[overflow.lane]?.items ?? [] : [];
        return {
          ...current,
          [overflow.lane]: {
            items: [...existingItems, ...page.items],
            nextCursor: page.nextCursor,
          },
        };
      });
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.status === 400 && failure.code === "INVALID_REQUEST") {
        setOverflowPages({});
        etagRef.current = null;
        setNotice("이전 작업 목록이 갱신되었습니다. 다시 열어 주세요.");
        setRefreshNonce((value) => value + 1);
      } else {
        setError(failure);
      }
    } finally {
      setOverflowLoadingLane(null);
    }
  }

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
      {error ? <ErrorState error={error} retry={() => {
        if (selectedStoreId) setRefreshNonce((value) => value + 1);
        else {
          setMembershipsLoading(true);
          setMembershipRefreshNonce((value) => value + 1);
        }
      }} /> : null}
      {!membershipsLoading && !error && !forbiddenStoreName && stores.length === 0 ? (
        <EmptyState title="접근 가능한 매장이 없습니다" description="ACTIVE 상태의 매장 멤버십이 필요합니다." />
      ) : null}

      {board ? (
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
                      busy={busyReference === item.orderReference}
                      rejecting={rejectingReference === item.orderReference}
                      rejectionReason={rejectionReason}
                      onRejectStart={() => { setRejectingReference(item.orderReference); setRejectionReason(""); }}
                      onRejectCancel={() => { setRejectingReference(null); setRejectionReason(""); }}
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
                                busy={busyReference === item.orderReference}
                                rejecting={rejectingReference === item.orderReference}
                                rejectionReason={rejectionReason}
                                onRejectStart={() => { setRejectingReference(item.orderReference); setRejectionReason(""); }}
                                onRejectCancel={() => { setRejectingReference(null); setRejectionReason(""); }}
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

function OrderCard({ item, busy, rejecting, rejectionReason, onRejectStart, onRejectCancel, onReasonChange, onAction }: {
  item: BoardItem;
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
