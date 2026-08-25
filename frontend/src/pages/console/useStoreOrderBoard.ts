import { useEffect, useMemo, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { merchantApi, merchantCsrfToken } from "../../api/merchantClient";
import { reconcileBoardItem } from "./storeOrderBoardModel";
import type {
  StoreOrderAction,
  StoreOrderBoard,
  StoreOrderBoardItem,
  StoreOrderBoardLane,
  StoreOrderBoardOverflow,
  StoreOrderBoardOverflowPage,
} from "./storeOrderBoardModel";

type MerchantStore = components["schemas"]["MerchantStore"];
type ExpectedStatus = components["schemas"]["StoreOrderActionRequest"]["expectedStatus"];
type OverflowPageState = Pick<StoreOrderBoardOverflowPage, "items" | "nextCursor">;

const POLL_INTERVAL_MS = 3_000;

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

async function requestOverflow(
  storeId: string,
  lane: StoreOrderBoardLane,
  cursor: string,
): Promise<StoreOrderBoardOverflowPage> {
  return unwrap(await merchantApi.GET("/stores/{storeId}/orders/overflow", {
    params: { path: { storeId }, query: { lane, cursor } },
  }));
}

export function useStoreOrderBoard() {
  const [stores, setStores] = useState<MerchantStore[]>([]);
  const [selectedStoreId, setSelectedStoreId] = useState<string | null>(null);
  const [membershipsLoading, setMembershipsLoading] = useState(true);
  const [board, setBoard] = useState<StoreOrderBoard | null>(null);
  const [boardLoading, setBoardLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [forbiddenStoreName, setForbiddenStoreName] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [membershipRefreshNonce, setMembershipRefreshNonce] = useState(0);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [busyReference, setBusyReference] = useState<string | null>(null);
  const [rejectingReference, setRejectingReference] = useState<string | null>(null);
  const [rejectionReason, setRejectionReason] = useState("");
  const [overflowPages, setOverflowPages] = useState<Partial<Record<StoreOrderBoardLane, OverflowPageState>>>({});
  const [overflowLoadingLane, setOverflowLoadingLane] = useState<StoreOrderBoardLane | null>(null);
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

  function beginReject(orderReference: string) {
    setRejectingReference(orderReference);
    setRejectionReason("");
  }

  function cancelReject() {
    setRejectingReference(null);
    setRejectionReason("");
  }

  function retry() {
    if (selectedStoreId) {
      setRefreshNonce((value) => value + 1);
    } else {
      setMembershipsLoading(true);
      setMembershipRefreshNonce((value) => value + 1);
    }
  }

  async function transition(item: StoreOrderBoardItem, action: StoreOrderAction, reason?: string) {
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
      cancelReject();
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

  async function loadOverflow(overflow: StoreOrderBoardOverflow, cursor: string, append: boolean) {
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

  return {
    stores,
    selectedStoreId,
    selectedStore,
    membershipsLoading,
    board,
    boardLoading,
    error,
    forbiddenStoreName,
    notice,
    busyReference,
    rejectingReference,
    rejectionReason,
    overflowPages,
    overflowLoadingLane,
    selectStore,
    beginReject,
    cancelReject,
    setRejectionReason,
    retry,
    transition,
    loadOverflow,
  };
}
