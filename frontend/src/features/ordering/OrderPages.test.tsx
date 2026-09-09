import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BrowserRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { won } from "../../lib/format";
import { CustomerOrdersPage, seoulDate } from "./CustomerOrdersPage";
import { customerOrderTimelineModel } from "./orderPresentation";
import { RefreshCustomerOrderDetailPage } from "../../presentation/beanflow-refresh";

const summary = {
  orderReference: "BF-7K3M-9Q2P",
  pickupNumber: "A-142",
  storeName: "강남 2호점",
  status: "READY" as const,
  orderedAt: "2026-08-14T03:00:00Z",
  pickupWindowStart: "2026-08-14T03:20:00Z",
  pickupWindowEnd: "2026-08-14T03:30:00Z",
  totalAmountKrw: 12_800,
  currency: "KRW" as const,
  itemSummary: "아이스 아메리카노 외 1건",
  allowedActions: [],
};

const detail = {
  orderReference: summary.orderReference,
  storeId: "store-1",
  pickupNumber: summary.pickupNumber,
  storeName: summary.storeName,
  status: summary.status,
  orderedAt: summary.orderedAt,
  pickupWindowStart: summary.pickupWindowStart,
  pickupWindowEnd: summary.pickupWindowEnd,
  pricing: {
    subtotalKrw: 15_000,
    couponDiscountKrw: 1_200,
    pointsAppliedKrw: 1_000,
    payableKrw: 12_800,
    currency: "KRW" as const,
  },
  lifecycle: {
    paidAt: "2026-08-14T03:01:00Z",
    acceptedAt: "2026-08-14T03:02:00Z",
    preparingAt: "2026-08-14T03:03:00Z",
    readyAt: "2026-08-14T03:04:00Z",
  },
  allowedActions: [],
  lines: [
    { lineSequence: 0, menuName: "아이스 아메리카노", optionNames: ["ICE", "샷 추가"], quantity: 2, lineTotalKrw: 9_000 },
    { lineSequence: 1, menuName: "오트 라떼", optionNames: ["HOT"], quantity: 1, lineTotalKrw: 3_800 },
  ],
};

function response<T>(data: T) {
  return { data, response: new Response(null, { status: 200 }) };
}

function renderAt(url: string) {
  window.history.replaceState(null, "", url);
  return render(
    <BrowserRouter>
      <Routes>
        <Route path="/app/orders" element={<CustomerOrdersPage />} />
        <Route path="/app/orders/:orderReference" element={<RefreshCustomerOrderDetailPage />} />
      </Routes>
    </BrowserRouter>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("customer order list", () => {
  it("derives API calendar dates in Asia Seoul at the UTC day boundary", () => {
    expect(seoulDate(new Date("2026-08-13T15:30:00Z"))).toBe("2026-08-14");
  });

  it("replaces UUID lookup with active and past tabs, date filters, and public-reference links", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(response({ items: [summary], page: {} }) as never);

    renderAt("/app/orders");

    expect(await screen.findByRole("heading", { name: "주문 내역" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "진행 중" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "지난 주문" })).toBeInTheDocument();
    expect(screen.queryByLabelText("조회 시작일")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "기간 변경" }));
    expect(screen.getByLabelText("조회 시작일")).toBeInTheDocument();
    expect(screen.getByLabelText("조회 종료일")).toBeInTheDocument();
    expect(screen.queryByPlaceholderText("UUID 주문 번호")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /아이스 아메리카노 외 1건/ })).toHaveAttribute(
      "href",
      "/app/orders/BF-7K3M-9Q2P",
    );
  });

  it("reloads the server-owned PAST classification when the tab changes", async () => {
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response({ items: [], page: {} }) as never);
    const user = userEvent.setup();
    renderAt("/app/orders");
    await screen.findByRole("heading", { name: "주문 내역" });

    await user.click(screen.getByRole("tab", { name: "지난 주문" }));

    await waitFor(() => {
      expect(get).toHaveBeenLastCalledWith(
        "/me/orders",
        expect.objectContaining({ params: { query: expect.objectContaining({ status: "PAST" }) } }),
      );
    });
  });

  it("shows refund access on the owning order without creating a separate refund lookup", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(response({
      items: [{ ...summary, status: "CANCELLED", allowedActions: ["VIEW_REFUND"] }],
      page: {},
    }) as never);

    renderAt("/app/orders?status=PAST");

    const order = await screen.findByRole("link", { name: /환불 내역 확인/ });
    expect(order).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
  });

  it("discards a slower ACTIVE response that resolves after the customer has switched to PAST", async () => {
    type Resolver = (value: unknown) => void;
    let resolveActive: Resolver = () => {};
    let resolvePast: Resolver = () => {};
    vi.spyOn(customerApi, "GET").mockImplementation((_path: string, options?: { params?: { query?: { status?: string } } }) => {
      const status = options?.params?.query?.status;
      if (status === "PAST") return new Promise((resolve) => { resolvePast = resolve as Resolver; });
      return new Promise((resolve) => { resolveActive = resolve as Resolver; });
    });
    const user = userEvent.setup();

    renderAt("/app/orders");
    await screen.findByRole("heading", { name: "주문 내역" });
    await user.click(screen.getByRole("tab", { name: "지난 주문" }));

    // The PAST tab the customer is now looking at answers first.
    resolvePast(response({ items: [{ ...summary, storeName: "패스트 매장" }], page: {} }));
    expect(await screen.findByText("패스트 매장")).toBeInTheDocument();

    // The stale ACTIVE request from before the tab switch answers late and
    // must not overwrite the PAST tab that is now on screen.
    resolveActive(response({ items: [{ ...summary, storeName: "액티브 매장" }], page: {} }));
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(screen.queryByText("액티브 매장")).not.toBeInTheDocument();
    expect(screen.getByText("패스트 매장")).toBeInTheDocument();
  });
});

describe("customer order detail", () => {
  it("loads by public reference and renders the pickup identity and immutable line snapshots", async () => {
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response(detail) as never);

    renderAt("/app/orders/BF-7K3M-9Q2P");

    expect(await screen.findAllByText("A-142")).toHaveLength(1);
    expect(screen.getByText("강남 2호점")).toBeInTheDocument();
    expect(screen.getByText("아이스 아메리카노")).toBeInTheDocument();
    expect(screen.getByText("ICE · 샷 추가")).toBeInTheDocument();
    expect(screen.getAllByText("2")).toHaveLength(2);
    const timeline = screen.getByRole("list", { name: "주문 진행 단계" });
    expect(timeline).toHaveTextContent("픽업 준비");
    expect(timeline.closest("section")).toHaveTextContent(/픽업 시간.*8\. 14\./);
    expect(screen.getByRole("heading", { name: "거래 요약" }).closest("section")).toHaveTextContent("상품 금액₩15,000");
    expect(screen.getByRole("heading", { name: "거래 요약" }).closest("section")).toHaveTextContent("결제 금액₩12,800");
    expect(screen.queryByRole("button", { name: "주문 취소" })).not.toBeInTheDocument();
    expect(get).toHaveBeenCalledWith("/me/orders/{orderReference}", {
      params: { path: { orderReference: "BF-7K3M-9Q2P" } },
    });
  });

  it.each([
    ["PENDING_PAYMENT", "pending", null],
    ["PAID", "progress", 0],
    ["ACCEPTED", "progress", 1],
    ["PREPARING", "progress", 2],
    ["READY", "progress", 3],
    ["COMPLETED", "progress", 4],
    ["CANCELLED", "terminal", null],
    ["REJECTED", "terminal", null],
    ["EXPIRED", "terminal", null],
  ] as const)("maps %s to presentation-only timeline metadata", (state, kind, activeIndex) => {
    expect(customerOrderTimelineModel(state)).toMatchObject({ kind, activeIndex });
  });

  it("discards a stale scheduled poll response that answers after a manual reload returns fresher data", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    type Resolver = (value: unknown) => void;
    const resolvers: Resolver[] = [];
    vi.spyOn(customerApi, "GET").mockImplementation(
      (path) => path === "/me/notification-summary" ? Promise.resolve(response({ hasUnread: false }) as never) : new Promise((resolve) => { resolvers.push(resolve as Resolver); }),
    );

    renderAt("/app/orders/BF-7K3M-9Q2P");
    await act(async () => {
      resolvers[0]?.(response({ ...detail, status: "PAID" }));
    });
    expect(await screen.findByText("준비가 끝나면 이 번호로 알려드릴게요.")).toBeInTheDocument();

    // PAID is live, so it schedules another read 5s later. Let that fire and
    // sit in flight as the second request.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    expect(resolvers).toHaveLength(2);

    // Before that scheduled poll answers, the customer manually refreshes.
    await act(async () => {
      await userEvent.click(screen.getByRole("button", { name: "새로고침" }));
    });
    expect(resolvers).toHaveLength(3);

    // The manual refresh answers first with the freshest status ...
    await act(async () => {
      resolvers[2]?.(response({ ...detail, status: "READY" }));
    });
    expect(await screen.findByText("픽업대에서 번호를 확인해 주세요.")).toBeInTheDocument();

    // ... and the stale scheduled poll (still PAID) answers late. It must not
    // roll the screen back to an earlier status.
    await act(async () => {
      resolvers[1]?.(response({ ...detail, status: "PAID" }));
    });
    expect(screen.getByText("픽업대에서 번호를 확인해 주세요.")).toBeInTheDocument();
    expect(screen.queryByText("준비가 끝나면 이 번호로 알려드릴게요.")).not.toBeInTheDocument();
  });
});

describe("customer order actions", () => {
  const cancellable = {
    ...detail,
    status: "PAID" as const,
    allowedActions: ["CANCEL" as const],
    cancellationPreview: {
      estimate: true as const,
      calculatedAt: "2026-08-14T03:05:00Z",
      orderVersion: 3,
      cashRefundAmountKrw: 12_800,
      restoredPoints: 200,
      couponRestoration: "NOT_APPLICABLE" as const,
      pendingAccrual: "WILL_NOT_ACCRUE" as const,
    },
  };

  it("enables the cancel command only from allowedActions", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(
      response({ ...detail, status: "PREPARING", allowedActions: [] }) as never,
    );

    renderAt("/app/orders/BF-7K3M-9Q2P");

    await screen.findAllByText("A-142");
    expect(screen.queryByRole("button", { name: "주문 취소" })).not.toBeInTheDocument();
  });

  it("sends a CSRF-protected cancellation with the declared reason", async () => {
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
    vi.spyOn(customerApi, "GET").mockResolvedValue(response(cancellable) as never);
    const post = vi.spyOn(customerApi, "POST").mockResolvedValue({
      data: {
        orderReference: "BF-7K3M-9Q2P",
        orderState: "CANCELLED",
        reasonCode: "CHANGED_MIND",
        paymentRecovery: { state: "PROCESSING" },
        cancelledAt: "2026-08-14T03:06:00Z",
        correlationId: "correlation-id",
      },
      response: new Response(null, { status: 202 }),
    } as never);

    renderAt("/app/orders/BF-7K3M-9Q2P");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "주문 취소" }));
    expect(screen.getByLabelText("주문 취소")).toHaveTextContent(won.format(12_800));
    await user.click(screen.getByRole("button", { name: "주문 취소하기" }));

    await waitFor(() => expect(post).toHaveBeenCalled());
    expect(post.mock.calls[0]?.[1]).toMatchObject({
      params: {
        path: { orderReference: "BF-7K3M-9Q2P" },
        header: { "X-BEANFLOW-CSRF": "customer-csrf-token" },
      },
      body: { reasonCode: "CHANGED_MIND" },
    });
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=; Max-Age=0; path=/";
  });

  it("separates a cancelled order from a refund that is still moving", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(
      response({
        ...detail,
        status: "CANCELLED",
        allowedActions: ["VIEW_REFUND"],
        paymentRecovery: { state: "PROCESSING", noticeCode: "REFUND_DELAYED", cancellationRequestedRefundAmountKrw: 12_800 },
      }) as never,
    );

    renderAt("/app/orders/BF-7K3M-9Q2P");

    expect(await screen.findByText("취소된 주문이에요")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "환불 내역" })).toBeInTheDocument();
    expect(screen.getByText("환불 확인이 지연되고 있어요")).toBeInTheDocument();
    expect(screen.getByText(/담당자가 결과를 확인하고 있습니다/)).toBeInTheDocument();
    expect(screen.queryByText("환불이 완료됐어요")).not.toBeInTheDocument();
  });

  it("reports a completed refund only when the server says SUCCEEDED", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(
      response({
        ...detail,
        status: "CANCELLED",
        allowedActions: ["VIEW_REFUND"],
        paymentRecovery: { state: "SUCCEEDED", cancellationRequestedRefundAmountKrw: 12_800 },
      }) as never,
    );

    renderAt("/app/orders/BF-7K3M-9Q2P");

    expect(await screen.findByRole("heading", { name: "환불 내역" })).toBeInTheDocument();
    expect(await screen.findByText("환불이 완료됐어요")).toBeInTheDocument();
  });
});

describe("customer reorder", () => {
  const reorderable = { ...detail, status: "COMPLETED" as const, allowedActions: ["REORDER" as const] };

  it("offers reorder only from allowedActions", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(response({ ...detail, allowedActions: [] }) as never);

    renderAt("/app/orders/BF-7K3M-9Q2P");

    await screen.findAllByText("A-142");
    expect(screen.queryByRole("button", { name: /같은 메뉴로 다시 주문/ })).not.toBeInTheDocument();
  });

  it("sends the public reference with no source identifier or price", async () => {
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
    vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
      if (path === "/stores/{storeId}/pickup-slots") {
        return response({ items: [{ pickupSlotId: "slot-1", startsAt: "2026-08-16T02:00:00Z", endsAt: "2026-08-16T02:10:00Z", remainingCapacity: 3 }] }) as never;
      }
      return response(reorderable) as never;
    });
    const post = vi.spyOn(customerApi, "POST").mockResolvedValue(
      response({ order: { orderId: "order-2", publicReference: "BF-2345-6789", payableKrw: 4500 }, priceComparison: { hasPriceChanges: false } }) as never,
    );

    renderAt("/app/orders/BF-7K3M-9Q2P");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /같은 메뉴로 다시 주문/ }));
    await user.click(await screen.findByRole("radio", { name: /가능/ }));
    await user.click(screen.getByRole("button", { name: "이 시간으로 주문" }));

    await waitFor(() => expect(post).toHaveBeenCalled());
    expect(post.mock.calls[0]?.[0]).toBe("/me/orders/{orderReference}/reorders");
    expect(post.mock.calls[0]?.[1]).toMatchObject({
      params: {
        path: { orderReference: "BF-7K3M-9Q2P" },
        header: { "X-BEANFLOW-CSRF": "customer-csrf-token" },
      },
      body: { pickupSlotId: "slot-1", pointsToUseKrw: 0 },
    });
    expect(JSON.stringify(post.mock.calls[0]?.[1])).not.toContain("sourceOrderId");
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=; Max-Age=0; path=/";
  });

  it("reports the server revalidation reason per item", async () => {
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
    vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
      if (path === "/stores/{storeId}/pickup-slots") {
        return response({ items: [{ pickupSlotId: "slot-1", startsAt: "2026-08-16T02:00:00Z", endsAt: "2026-08-16T02:10:00Z", remainingCapacity: 3 }] }) as never;
      }
      return response(reorderable) as never;
    });
    vi.spyOn(customerApi, "POST").mockResolvedValue({
      error: {
        code: "REORDER_ITEMS_UNAVAILABLE",
        message: "재구성할 수 없는 상품이 있습니다.",
        details: [{ lineSequence: 0, reason: "MENU_NOT_AVAILABLE" }],
      },
      response: new Response(null, { status: 409 }),
    } as never);

    renderAt("/app/orders/BF-7K3M-9Q2P");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /같은 메뉴로 다시 주문/ }));
    await user.click(await screen.findByRole("radio", { name: /가능/ }));
    await user.click(screen.getByRole("button", { name: "이 시간으로 주문" }));

    expect(await screen.findByText("지금 그대로 다시 주문할 수 없어요")).toBeInTheDocument();
    expect(screen.getByText("지금은 판매하지 않아요")).toBeInTheDocument();
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=; Max-Age=0; path=/";
  });
});
