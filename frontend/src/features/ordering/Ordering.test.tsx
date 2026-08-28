import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { runCustomerLogoutHandlers } from "../shared/customerLogout";
import { CART_STORAGE_KEY, type CartLine, cart } from "./cart";
import { RefreshCartPage, RefreshStoreDetailPage } from "../../presentation/beanflow-refresh";
import { orderConflictGuidance, shouldRotateIdempotencyKey } from "./orderConflicts";

const line = (menuId: string, quantity = 1): CartLine => ({
  menuId,
  optionIds: [],
  quantity,
  display: { menuName: `메뉴 ${menuId}`, optionNames: [], unitPriceKrw: 4_500 },
});

function ok<T>(data: T) {
  return { data, response: new Response(null, { status: 200 }) };
}

function failed(status: number, code: string, message: string) {
  return { error: { code, message }, response: new Response(null, { status }) };
}

const menus = {
  items: [
    { menuId: "menu-1", name: "아메리카노", displayCategory: "커피", description: "고소한 원두의 긴 여운", basePriceKrw: 4_500, currency: "KRW", available: true, options: [{ optionId: "option-1", name: "샷 추가", additionalPriceKrw: 500, available: true }], image: { url: "/demo/catalog/americano.webp", expiresAt: "2099-01-01T00:00:00Z" } },
    { menuId: "menu-2", name: "오트 라떼", description: "부드러운 귀리 음료", basePriceKrw: 5_500, currency: "KRW", available: false, options: [] },
  ],
};
const store = {
  storeId: "store-1",
  name: "성수 로스터리",
  orderingAvailable: true,
  pickupAvailable: true,
  nextPickupWindow: { startsAt: "2026-08-16T02:00:00Z", endsAt: "2026-08-16T02:10:00Z" },
  customerDisplay: {
    addressLine: "서울 성동구 연무장길 10",
    directionsHint: "성수역 3번 출구에서 도보 4분",
    operatingStatus: "OPEN",
  },
};
const openSlots = { items: [{ pickupSlotId: "slot-1", startsAt: "2026-08-16T02:00:00Z", endsAt: "2026-08-16T02:10:00Z", remainingCapacity: 4 }] };
const closedSlots = { items: [{ pickupSlotId: "slot-1", startsAt: "2026-08-16T02:00:00Z", endsAt: "2026-08-16T02:10:00Z", remainingCapacity: 0 }] };
const quote = (payableKrw = 9_000, fingerprint = "a".repeat(64)) => ({
  quotedAt: "2026-08-16T01:55:00Z",
  quoteFingerprint: fingerprint,
  store: { storeId: "store-1", name: "성수 로스터리" },
  pickupWindow: { startsAt: "2026-08-16T02:00:00Z", endsAt: "2026-08-16T02:10:00Z" },
  lines: [{ menuId: "menu-1", menuName: "메뉴 menu-1", quantity: payableKrw / 4_500, optionNames: [], lineTotalKrw: payableKrw }],
  pricing: { subtotalKrw: payableKrw, couponDiscountKrw: 0, pointsAppliedKrw: 0, payableKrw, currency: "KRW" },
  guarantee: "NONE",
});

function routeGet(routes: Record<string, unknown>) {
  return vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
    if (!(path in routes)) throw new Error(`unexpected GET ${path}`);
    return routes[path] as never;
  });
}

function renderStore() {
  return render(
    <MemoryRouter initialEntries={["/app/stores/store-1"]}>
      <Routes>
        <Route path="/app/stores/:storeId" element={<RefreshStoreDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

function renderCart() {
  return render(
    <MemoryRouter initialEntries={["/app/cart"]}>
      <Routes>
        <Route path="/app/cart" element={<RefreshCartPage />} />
        <Route path="/app/checkout/:orderId" element={<h1>결제 화면</h1>} />
      </Routes>
    </MemoryRouter>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  cart.clear();
  document.cookie = "BEANFLOW_CUSTOMER_XSRF=; Max-Age=0; path=/";
});

describe("client cart", () => {
  it("keeps one store and asks before replacing the other one", () => {
    expect(cart.add({ storeId: "store-1", storeName: "성수" }, line("menu-1"))).toEqual({ outcome: "added" });

    expect(cart.add({ storeId: "store-2", storeName: "합정" }, line("menu-9"))).toEqual({
      outcome: "other-store",
      currentStoreName: "성수",
    });
    expect(cart.read()).toMatchObject({ status: "ready", cart: { storeId: "store-1" } });

    cart.replaceWith({ storeId: "store-2", storeName: "합정" }, line("menu-9"));
    expect(cart.read()).toMatchObject({ status: "ready", cart: { storeId: "store-2" } });
  });

  it("merges an identical menu and option selection", () => {
    cart.add({ storeId: "store-1", storeName: "성수" }, line("menu-1", 2));
    cart.add({ storeId: "store-1", storeName: "성수" }, line("menu-1", 1));

    const state = cart.read();
    expect(state.status === "ready" && state.cart.lines).toHaveLength(1);
    expect(state.status === "ready" && state.cart.lines[0]?.quantity).toBe(3);
  });

  it("reports a damaged cart instead of overwriting it with an empty one", () => {
    localStorage.setItem(CART_STORAGE_KEY, "{not json");
    expect(cart.read()).toEqual({ status: "corrupt" });

    localStorage.setItem(CART_STORAGE_KEY, JSON.stringify({ version: 99, storeId: "store-1", storeName: "성수", lines: [] }));
    expect(cart.read()).toEqual({ status: "corrupt" });
    expect(localStorage.getItem(CART_STORAGE_KEY)).not.toBeNull();
  });

  it("clears a damaged cart only when the customer asks", async () => {
    localStorage.setItem(CART_STORAGE_KEY, "{not json");

    renderCart();
    expect(await screen.findByText("장바구니 정보를 읽지 못했어요")).toBeInTheDocument();
    expect(localStorage.getItem(CART_STORAGE_KEY)).not.toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "장바구니 비우기" }));
    expect(localStorage.getItem(CART_STORAGE_KEY)).toBeNull();
  });

  it("drops the in-memory cache on customer logout so the next customer never sees the previous cart", () => {
    cart.add({ storeId: "store-1", storeName: "성수" }, line("menu-1"));
    expect(cart.read()).toMatchObject({ status: "ready", cart: { storeId: "store-1" } });

    // Logout removes the storage key directly, without going through cart.clear(),
    // so the module-level cache is still serving the previous customer's cart.
    localStorage.removeItem(CART_STORAGE_KEY);
    expect(cart.read()).toMatchObject({ status: "ready", cart: { storeId: "store-1" } });

    runCustomerLogoutHandlers();

    expect(cart.read()).toEqual({ status: "empty" });
  });
});

describe("store identity comes from the server", () => {
  it("names the store on a direct visit that carries no navigation state", async () => {
    routeGet({ "/stores/{storeId}": ok(store), "/stores/{storeId}/menus": ok(menus), "/stores/{storeId}/pickup-slots": ok(openSlots) });

    renderStore();

    expect(await screen.findByRole("heading", { name: "성수 로스터리" })).toBeInTheDocument();
    expect(screen.getByText("서울 성동구 연무장길 10")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "매장" })).not.toBeInTheDocument();
  });

  it("stores the server name in the cart rather than a placeholder", async () => {
    routeGet({ "/stores/{storeId}": ok(store), "/stores/{storeId}/menus": ok(menus), "/stores/{storeId}/pickup-slots": ok(openSlots) });

    const { container } = renderStore();
    const user = userEvent.setup();
    expect(await screen.findByRole("button", { name: /아메리카노/ })).toBeInTheDocument();
    expect(container.querySelector(".bfr-menu-row__media img")).toHaveAttribute("src", "/demo/catalog/americano.webp");
    await user.click(screen.getByRole("button", { name: /아메리카노/ }));
    await user.click(screen.getByRole("button", { name: /담기/ }));

    expect(cart.read()).toMatchObject({ status: "ready", cart: { storeName: "성수 로스터리" } });
  });

  it("shows a missing store as a failure instead of an unnamed menu list", async () => {
    routeGet({
      "/stores/{storeId}": failed(404, "RESOURCE_NOT_FOUND", "매장을 찾을 수 없습니다."),
      "/stores/{storeId}/menus": ok(menus),
      "/stores/{storeId}/pickup-slots": ok(openSlots),
    });

    renderStore();

    expect(await screen.findByText("지금은 주문할 수 없는 매장이에요")).toBeInTheDocument();
    // The server sentence is written for an operator, not for the customer.
    expect(screen.queryByText("매장을 찾을 수 없습니다.")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /아메리카노/ })).not.toBeInTheDocument();
  });

  it("prefers the current server name over the one saved with the cart", async () => {
    cart.add({ storeId: "store-1", storeName: "예전 이름" }, line("menu-1"));
    routeGet({ "/stores/{storeId}": ok(store), "/stores/{storeId}/pickup-slots": ok(openSlots) });

    renderCart();

    expect(await screen.findByText("성수 로스터리")).toBeInTheDocument();
    expect(screen.queryByText("예전 이름")).not.toBeInTheDocument();
  });

  it("keeps the saved name and stays orderable when the store read fails", async () => {
    cart.add({ storeId: "store-1", storeName: "성수 로스터리" }, line("menu-1"));
    routeGet({
      "/stores/{storeId}": failed(503, "DEPENDENCY_UNAVAILABLE", "매장 정보를 조회하지 못했습니다."),
      "/stores/{storeId}/pickup-slots": ok(openSlots),
    });

    renderCart();

    expect(await screen.findByText("성수 로스터리")).toBeInTheDocument();
    expect(screen.getByText("매장 안내를 불러오지 못했어요.")).toBeInTheDocument();
    expect(await screen.findByRole("radio", { name: /가능/ })).toBeEnabled();
  });
});

describe("store detail", () => {
  it("disables a sold-out menu and shows a closed pickup window", async () => {
    routeGet({ "/stores/{storeId}": ok(store), "/stores/{storeId}/menus": ok(menus), "/stores/{storeId}/pickup-slots": ok(closedSlots) });

    renderStore();

    expect(await screen.findByText("지금은 픽업 시간이 모두 마감됐어요.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "커피" })).toBeInTheDocument();
    expect(screen.getByText("고소한 원두의 긴 여운")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "메뉴" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /오트 라떼/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /아메리카노/ })).toBeDisabled();
  });

  it("keeps operating hours separate from the store ordering switch", async () => {
    routeGet({
      "/stores/{storeId}": ok({ ...store, orderingAvailable: false, pickupAvailable: false, nextPickupWindow: undefined }),
      "/stores/{storeId}/menus": ok(menus),
      "/stores/{storeId}/pickup-slots": ok(openSlots),
    });

    renderStore();

    expect(await screen.findByText("영업 중")).toBeInTheDocument();
    expect(screen.getByText("주문 쉬는 중")).toBeInTheDocument();
    expect(screen.getByText(/현재 주문을 받지 않아요/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /아메리카노/ })).toBeDisabled();
  });

  it("adds the selected menu and options to the cart", async () => {
    routeGet({ "/stores/{storeId}": ok(store), "/stores/{storeId}/menus": ok(menus), "/stores/{storeId}/pickup-slots": ok(openSlots) });

    renderStore();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /아메리카노/ }));
    await user.click(screen.getByRole("checkbox", { name: /샷 추가/ }));
    await user.click(screen.getByRole("button", { name: /담기/ }));

    const state = cart.read();
    expect(state.status === "ready" && state.cart.lines[0]).toMatchObject({
      menuId: "menu-1",
      optionIds: ["option-1"],
      display: { unitPriceKrw: 5_000 },
    });
  });
});

describe("order creation conflicts", () => {
  it("asks for another pickup time without changing the cart", async () => {
    cart.add({ storeId: "store-1", storeName: "성수 로스터리" }, line("menu-1"));
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
    routeGet({ "/stores/{storeId}": ok(store), "/stores/{storeId}/pickup-slots": ok(openSlots) });
    vi.spyOn(customerApi, "POST").mockImplementation(async (path: string) =>
      path === "/me/order-quotes"
        ? ok(quote(4_500)) as never
        : failed(409, "PICKUP_SLOT_FULL", "슬롯 수용량이 없습니다.") as never,
    );

    renderCart();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("radio", { name: /가능/ }));
    await user.click(await screen.findByRole("button", { name: /4,500.*주문하기/ }));

    expect(await screen.findByText("고른 픽업 시간이 방금 마감됐어요")).toBeInTheDocument();
    expect(cart.read()).toMatchObject({ status: "ready", cart: { lines: [{ menuId: "menu-1" }] } });
  });

  it("does not treat an in-progress idempotent request as a new order", () => {
    const guidance = orderConflictGuidance(new ApiRequestError(409, "IDEMPOTENCY_REQUEST_IN_PROGRESS", "처리 중입니다."));
    expect(guidance?.recovery).toBe("wait");
    expect(shouldRotateIdempotencyKey(new ApiRequestError(409, "IDEMPOTENCY_REQUEST_IN_PROGRESS", ""))).toBe(false);
    expect(shouldRotateIdempotencyKey(new ApiRequestError(409, "IDEMPOTENCY_KEY_REUSED", ""))).toBe(true);
    expect(shouldRotateIdempotencyKey(new ApiRequestError(409, "ORDER_QUOTE_STALE", ""))).toBe(false);
  });

  it("sends the CSRF header and the cart lines on order creation", async () => {
    cart.add({ storeId: "store-1", storeName: "성수 로스터리" }, line("menu-1", 2));
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
    routeGet({ "/stores/{storeId}": ok(store), "/stores/{storeId}/pickup-slots": ok(openSlots) });
    const post = vi.spyOn(customerApi, "POST").mockImplementation(async (path: string) =>
      path === "/me/order-quotes"
        ? ok(quote()) as never
        : ok({ order: { orderId: "order-1", publicReference: "BF-2345-6789", payableKrw: 9_000 } }) as never,
    );

    renderCart();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("radio", { name: /가능/ }));
    await user.click(await screen.findByRole("button", { name: /9,000.*주문하기/ }));

    expect(await screen.findByRole("heading", { name: "결제 화면" })).toBeInTheDocument();
    expect(post.mock.calls[0]?.[0]).toBe("/me/order-quotes");
    expect(post.mock.calls[0]?.[1]).toMatchObject({
      params: { header: { "X-BEANFLOW-CSRF": "customer-csrf-token" } },
      body: { storeId: "store-1", pickupSlotId: "slot-1", lines: [{ menuId: "menu-1", optionIds: [], quantity: 2 }] },
    });
    expect(post.mock.calls[1]?.[0]).toBe("/orders");
    expect(post.mock.calls[1]?.[1]).toMatchObject({
      params: { header: { "X-BEANFLOW-CSRF": "customer-csrf-token" } },
      body: {
        storeId: "store-1",
        pickupSlotId: "slot-1",
        lines: [{ menuId: "menu-1", optionIds: [], quantity: 2 }],
        expectedQuoteFingerprint: "a".repeat(64),
      },
    });
    expect(cart.read()).toEqual({ status: "empty" });
  });

  it("clears the previous money while a changed cart is being requoted", async () => {
    cart.add({ storeId: "store-1", storeName: "성수 로스터리" }, line("menu-1", 2));
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
    routeGet({ "/stores/{storeId}": ok(store), "/stores/{storeId}/pickup-slots": ok(openSlots) });
    let quoteCall = 0;
    vi.spyOn(customerApi, "POST").mockImplementation(async (path: string) => {
      if (path !== "/me/order-quotes") throw new Error(`unexpected POST ${path}`);
      quoteCall += 1;
      return ok(quote(quoteCall === 1 ? 9_000 : 13_500, quoteCall === 1 ? "a".repeat(64) : "b".repeat(64))) as never;
    });

    renderCart();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("radio", { name: /가능/ }));
    expect(await screen.findByRole("button", { name: /9,000.*주문하기/ })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "메뉴 menu-1 수량 늘리기" }));

    expect(screen.queryAllByText("₩9,000")).toHaveLength(0);
    expect(await screen.findByText("현재 주문 금액을 확인하는 중")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /13,500.*주문하기/ })).toBeEnabled();
    expect(quoteCall).toBe(2);
  });

  it("requires explicit stale quote confirmation and submits a new key with the new fingerprint", async () => {
    cart.add({ storeId: "store-1", storeName: "성수 로스터리" }, line("menu-1", 2));
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
    routeGet({ "/stores/{storeId}": ok(store), "/stores/{storeId}/pickup-slots": ok(openSlots) });
    const currentQuote = quote(10_000, "b".repeat(64));
    const orderRequests: Array<{ body?: unknown; key?: string }> = [];
    vi.spyOn(customerApi, "POST").mockImplementation(async (path: string, options: unknown) => {
      if (path === "/me/order-quotes") return ok(quote()) as never;
      const request = options as { body?: unknown; params?: { header?: Record<string, string> } };
      orderRequests.push({ body: request.body, key: request.params?.header?.["Idempotency-Key"] });
      if (orderRequests.length === 1) {
        return {
          error: { code: "ORDER_QUOTE_STALE", message: "changed", currentQuote },
          response: new Response(null, { status: 409 }),
        } as never;
      }
      return ok({ order: { orderId: "order-2", publicReference: "BF-2345-6790", payableKrw: 10_000 } }) as never;
    });

    renderCart();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("radio", { name: /가능/ }));
    await user.click(await screen.findByRole("button", { name: /9,000.*주문하기/ }));

    expect(await screen.findByText("주문 금액과 조건이 변경됐어요")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "견적 확인 후 주문하기" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "변경 내용 확인" }));
    await user.click(screen.getByRole("button", { name: /10,000.*주문하기/ }));
    await waitFor(() => expect(screen.getByRole("heading", { name: "결제 화면" })).toBeInTheDocument());

    expect(orderRequests).toHaveLength(2);
    expect(orderRequests[0]?.body).toMatchObject({ expectedQuoteFingerprint: "a".repeat(64) });
    expect(orderRequests[1]?.body).toMatchObject({ expectedQuoteFingerprint: "b".repeat(64) });
    expect(orderRequests[1]?.key).not.toBe(orderRequests[0]?.key);
  });
});
