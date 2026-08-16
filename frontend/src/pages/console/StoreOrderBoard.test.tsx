import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError } from "../../api/client";
import { merchantApi } from "../../api/merchantClient";
import { StoreOrderBoardPage } from "./StoreOrderBoard";

const gangnam = { storeId: "10000000-0000-4000-8000-000000000001", storeName: "강남 2호점", membershipRole: "OWNER" as const };
const yeouido = { storeId: "10000000-0000-4000-8000-000000000002", storeName: "여의도점", membershipRole: "STAFF" as const };

const paidOrder = {
  orderReference: "BF-7K3M-9Q2P",
  pickupNumber: "A-142",
  pickupBusinessDate: "2026-08-14",
  lane: "PENDING_ACCEPTANCE" as const,
  status: "PAID" as const,
  pickupWindowStart: "2026-08-14T03:20:00Z",
  pickupWindowEnd: "2026-08-14T03:30:00Z",
  itemSummary: "아이스 아메리카노 외 1건",
  acceptanceDeadlineAt: "2026-08-14T03:03:00Z",
  acceptancePhase: "WARNING" as const,
  allowedActions: ["ACCEPT" as const, "REJECT" as const],
};

const readyOrder = {
  ...paidOrder,
  orderReference: "BF-4D8N-7R2K",
  pickupNumber: "A-143",
  lane: "READY" as const,
  status: "READY" as const,
  acceptanceDeadlineAt: null,
  acceptancePhase: null,
  allowedActions: ["COMPLETE" as const],
};

const board = {
  groups: [{ pickupBusinessDate: "2026-08-14", items: [paidOrder] }],
  overflow: [],
};

function response<T>(data: T, etag = '"board-v1"') {
  return { data, response: new Response(null, { status: 200, headers: { ETag: etag } }) };
}

function noContent(status = 204) {
  return { response: new Response(null, { status }) };
}

function visible(value: "visible" | "hidden") {
  Object.defineProperty(document, "visibilityState", { configurable: true, value });
  document.dispatchEvent(new Event("visibilitychange"));
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  visible("visible");
});

describe("store order board", () => {
  it("opens a single active store without a selector and renders the three operational columns", async () => {
    const get = vi.spyOn(merchantApi, "GET").mockImplementation(async (path) => {
      if (path === "/merchant/me/stores") return response([gangnam]) as never;
      if (path === "/stores/{storeId}/orders") return response(board) as never;
      throw new Error(`unexpected GET ${path}`);
    });

    render(<StoreOrderBoardPage />);

    expect(await screen.findByRole("heading", { name: "실행 주문 보드" })).toBeInTheDocument();
    expect(await screen.findByText("A-142")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "운영 매장" })).not.toBeInTheDocument();
    expect(screen.getByText("강남 2호점")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "접수 대기" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "제조 중" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "준비 완료" })).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith("/stores/{storeId}/orders", {
      params: { path: { storeId: gangnam.storeId }, header: undefined },
    });
  });

  it("makes bounded older work visible and retrieves it only after the merchant requests it", async () => {
    const get = vi.spyOn(merchantApi, "GET").mockImplementation(async (path, options) => {
      if (path === "/merchant/me/stores") return response([gangnam]) as never;
      if (path === "/stores/{storeId}/orders") {
        return response({
          ...board,
          overflow: [{ lane: "READY", overflowCount: 2, nextCursor: "ready-overflow-cursor" }],
        }) as never;
      }
      if (path === "/stores/{storeId}/orders/overflow") {
        expect(options).toEqual({
          params: {
            path: { storeId: gangnam.storeId },
            query: { lane: "READY", cursor: "ready-overflow-cursor" },
          },
        });
        return response({ lane: "READY", items: [readyOrder], nextCursor: null }) as never;
      }
      throw new Error(`unexpected GET ${path}`);
    });
    const user = userEvent.setup();

    render(<StoreOrderBoardPage />);

    const button = await screen.findByRole("button", { name: "오래된 준비 완료 작업 2건 보기" });
    expect(screen.queryByRole("article", { name: "주문 A-143" })).not.toBeInTheDocument();
    await user.click(button);

    expect(await screen.findByRole("article", { name: "주문 A-143" })).toBeInTheDocument();
    const calls = get.mock.calls as unknown as Array<[string, unknown?]>;
    expect(calls.filter(([path]) => path === "/stores/{storeId}/orders/overflow")).toHaveLength(1);
  });

  it("recovers one fresh board snapshot after an expired overflow cursor without retrying the queue", async () => {
    let boardReads = 0;
    const boardOptions: unknown[] = [];
    const get = vi.spyOn(merchantApi, "GET").mockImplementation(async (path, options) => {
      if (path === "/merchant/me/stores") return response([gangnam]) as never;
      if (path === "/stores/{storeId}/orders") {
        boardReads += 1;
        boardOptions.push(options);
        return response({
          ...board,
          overflow: [{ lane: "READY", overflowCount: 2, nextCursor: `ready-overflow-cursor-${boardReads}` }],
        }, `W/"board-v${boardReads}"`) as never;
      }
      if (path === "/stores/{storeId}/orders/overflow") {
        throw new ApiRequestError(400, "INVALID_REQUEST", "Overflow cursor expired");
      }
      throw new Error(`unexpected GET ${path}`);
    });
    const user = userEvent.setup();

    render(<StoreOrderBoardPage />);

    await user.click(await screen.findByRole("button", { name: "오래된 준비 완료 작업 2건 보기" }));

    await waitFor(() => expect(boardReads).toBe(2));
    expect(boardOptions).toEqual([
      { params: { path: { storeId: gangnam.storeId }, header: undefined } },
      { params: { path: { storeId: gangnam.storeId }, header: undefined } },
    ]);
    expect(await screen.findByRole("status", { name: "주문 상태 갱신 안내" })).toHaveTextContent("이전 작업 목록이 갱신되었습니다");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    const calls = get.mock.calls as unknown as Array<[string, unknown?]>;
    expect(calls.filter(([path]) => path === "/stores/{storeId}/orders/overflow")).toHaveLength(1);
  });

  it("keeps a membership dependency failure explicit and retries the membership query", async () => {
    let storeReads = 0;
    vi.spyOn(merchantApi, "GET").mockImplementation(async (path) => {
      if (path === "/merchant/me/stores") {
        storeReads += 1;
        if (storeReads === 1) throw new ApiRequestError(503, "DEPENDENCY_UNAVAILABLE", "매장 목록을 불러오지 못했습니다");
        return response([gangnam]) as never;
      }
      if (path === "/stores/{storeId}/orders") return response(board) as never;
      throw new Error(`unexpected GET ${path}`);
    });
    const user = userEvent.setup();

    render(<StoreOrderBoardPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("매장 목록을 불러오지 못했습니다");
    expect(screen.queryByText("접근 가능한 매장이 없습니다")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "다시 시도" }));

    expect(await screen.findByText("A-142")).toBeInTheDocument();
    expect(storeReads).toBe(2);
  });

  it("switches only between active memberships and sends a board action for the selected store", async () => {
    document.cookie = "BEANFLOW_MERCHANT_XSRF=merchant-csrf-token; path=/";
    const get = vi.spyOn(merchantApi, "GET").mockImplementation(async (path, options) => {
      if (path === "/merchant/me/stores") return response([gangnam, yeouido]) as never;
      if (path === "/auth/merchant/csrf") return noContent() as never;
      if (path === "/stores/{storeId}/orders") {
        const storeId = (options as { params: { path: { storeId: string } } }).params.path.storeId;
        return response(storeId === yeouido.storeId ? board : { groups: [], overflow: [] }) as never;
      }
      throw new Error(`unexpected GET ${path}`);
    });
    const post = vi.spyOn(merchantApi, "POST").mockResolvedValue(response({ ...paidOrder, lane: "ACCEPTED", status: "ACCEPTED", allowedActions: ["START_PREPARING"] }) as never);
    const user = userEvent.setup();

    render(<StoreOrderBoardPage />);
    await screen.findByRole("combobox", { name: "운영 매장" });
    await user.selectOptions(screen.getByRole("combobox", { name: "운영 매장" }), yeouido.storeId);
    const card = await screen.findByRole("article", { name: "주문 A-142" });
    await user.click(within(card).getByRole("button", { name: "주문 접수" }));

    await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
    expect(post).toHaveBeenCalledWith("/stores/{storeId}/orders/{orderReference}/transitions", {
      params: {
        path: { storeId: yeouido.storeId, orderReference: paidOrder.orderReference },
        header: { "Idempotency-Key": expect.any(String), "X-BEANFLOW-CSRF": "merchant-csrf-token" },
      },
      body: { action: "ACCEPT", expectedStatus: "PAID", reason: undefined },
    });
    expect(await screen.findByText("주문 접수")).toBeInTheDocument();
    const calls = get.mock.calls as unknown as Array<[string, unknown?]>;
    expect(calls.filter(([path]) => path === "/stores/{storeId}/orders")).toHaveLength(2);
  });

  it("stops conditional polling while hidden and resumes immediately with the current ETag", async () => {
    vi.useFakeTimers();
    const get = vi.spyOn(merchantApi, "GET").mockImplementation(async (path, options) => {
      if (path === "/merchant/me/stores") return response([gangnam]) as never;
      if (path === "/stores/{storeId}/orders") {
        const tag = (options as { params: { header?: { "If-None-Match"?: string } } }).params.header?.["If-None-Match"];
        return tag ? noContent(304) as never : response(board) as never;
      }
      throw new Error(`unexpected GET ${path}`);
    });

    render(<StoreOrderBoardPage />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByText("A-142")).toBeInTheDocument();

    visible("hidden");
    await act(async () => { await vi.advanceTimersByTimeAsync(6_000); });
    const calls = get.mock.calls as unknown as Array<[string, unknown?]>;
    expect(calls.filter(([path]) => path === "/stores/{storeId}/orders")).toHaveLength(1);

    visible("visible");
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    const boardCalls = calls.filter(([path]) => path === "/stores/{storeId}/orders");
    expect(boardCalls).toHaveLength(2);
    expect(boardCalls[1]?.[1]).toEqual({
      params: { path: { storeId: gangnam.storeId }, header: { "If-None-Match": '"board-v1"' } },
    });
  });

  it("removes a revoked current store and shows an explicit forbidden state instead of stale board data", async () => {
    vi.useFakeTimers();
    let boardReads = 0;
    let storeReads = 0;
    vi.spyOn(merchantApi, "GET").mockImplementation(async (path) => {
      if (path === "/merchant/me/stores") {
        storeReads += 1;
        return response(storeReads === 1 ? [gangnam, yeouido] : [yeouido]) as never;
      }
      if (path === "/stores/{storeId}/orders") {
        boardReads += 1;
        if (boardReads === 1) return response(board) as never;
        throw new ApiRequestError(403, "FORBIDDEN", "Store membership is not active");
      }
      throw new Error(`unexpected GET ${path}`);
    });

    render(<StoreOrderBoardPage />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByText("A-142")).toBeInTheDocument();

    await act(async () => { await vi.advanceTimersByTimeAsync(3_000); await Promise.resolve(); });

    expect(screen.getByRole("alert")).toHaveTextContent("매장 접근 권한이 변경되었습니다");
    expect(screen.queryByText("A-142")).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "운영 매장" })).not.toHaveValue(gangnam.storeId);
    expect(screen.queryByRole("option", { name: "강남 2호점" })).not.toBeInTheDocument();
  });

  it("refreshes the board and presents an informational message when a stale action loses with 409", async () => {
    document.cookie = "BEANFLOW_MERCHANT_XSRF=merchant-csrf-token; path=/";
    let boardReads = 0;
    vi.spyOn(merchantApi, "GET").mockImplementation(async (path) => {
      if (path === "/merchant/me/stores") return response([gangnam]) as never;
      if (path === "/auth/merchant/csrf") return noContent() as never;
      if (path === "/stores/{storeId}/orders") {
        boardReads += 1;
        return response(boardReads === 1 ? board : { groups: [], overflow: [] }, `"board-v${boardReads}"`) as never;
      }
      throw new Error(`unexpected GET ${path}`);
    });
    vi.spyOn(merchantApi, "POST").mockResolvedValue({
      error: { code: "ORDER_STATE_CONFLICT", message: "Order state changed" },
      response: new Response(null, { status: 409 }),
    } as never);
    const user = userEvent.setup();

    render(<StoreOrderBoardPage />);
    const card = await screen.findByRole("article", { name: "주문 A-142" });
    await user.click(within(card).getByRole("button", { name: "주문 접수" }));

    expect(await screen.findByRole("status", { name: "주문 상태 갱신 안내" })).toHaveTextContent("다른 작업자가 먼저 처리했습니다");
    await waitFor(() => expect(boardReads).toBe(2));
    expect(screen.queryByText("A-142")).not.toBeInTheDocument();
  });
});
