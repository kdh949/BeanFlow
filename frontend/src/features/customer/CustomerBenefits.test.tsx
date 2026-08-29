import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { couponSelection } from "./couponSelection";
import { CouponWalletPage } from "./CouponWalletPage";
import { FavoriteStoreButton, FavoriteStoresPage } from "./FavoriteStoresPage";
import { RefreshCartPage } from "../../presentation/beanflow-refresh";
import { cart } from "../ordering/cart";

const customerStore = {
  storeId: "store-1",
  name: "시청점",
  orderingAvailable: true,
  pickupAvailable: true,
  nextPickupWindow: { startsAt: "2026-09-01T01:00:00Z", endsAt: "2026-09-01T01:10:00Z" },
  customerDisplay: { operatingStatus: "OPEN" as const },
};

function ok<T>(data: T) {
  return { data, response: new Response(null, { status: 200 }) };
}

function noContent() {
  return { response: new Response(null, { status: 204 }) };
}

function renderRoute(path: string, element: React.ReactElement) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/app/coupons" element={element} />
        <Route path="/app/favorites" element={element} />
        <Route path="*" element={element} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  couponSelection.clear();
  cart.clear();
  document.cookie = "BEANFLOW_CUSTOMER_XSRF=test-csrf; path=/";
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("customer coupon selection", () => {
  it("keeps one store-scoped coupon in memory and clears it explicitly", () => {
    couponSelection.select({ storeId: "store-1", couponIssuanceId: "coupon-1", label: "1,000원 할인" });

    expect(couponSelection.forStore("store-1")?.couponIssuanceId).toBe("coupon-1");
    expect(couponSelection.forStore("store-2")).toBeNull();

    couponSelection.clear();
    expect(couponSelection.forStore("store-1")).toBeNull();
  });

  it("loads coupons with the current store and never enables an inapplicable coupon", async () => {
    vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
      if (path === "/stores/{storeId}") return ok(customerStore) as never;
      if (path === "/me/coupons") return ok({
        items: [
          { couponIssuanceId: "coupon-1", benefit: { discountType: "FIXED_KRW", fixedAmountKrw: 1_000 }, minimumOrderKrw: 5_000, couponExpiresAt: "2026-09-01T00:00:00Z", applicable: true },
          { couponIssuanceId: "coupon-2", benefit: { discountType: "RATE_BPS", rateBps: 1_000, maximumDiscountKrw: 2_000 }, minimumOrderKrw: 10_000, couponExpiresAt: "2026-09-02T00:00:00Z", applicable: false, reasonCode: "STORE_NOT_APPLICABLE" },
        ],
        page: {},
      }) as never;
      throw new Error(`unexpected GET ${path}`);
    });

    renderRoute("/app/coupons?storeId=store-1", <CouponWalletPage />);
    const user = userEvent.setup();

    expect(await screen.findByRole("heading", { name: "쿠폰" })).toBeInTheDocument();
    expect(screen.getByText("선택 매장 · 시청점")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /이 매장에서는 사용할 수 없음/ })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: /₩1,000 할인 쿠폰 선택/ }));

    expect(couponSelection.forStore("store-1")?.couponIssuanceId).toBe("coupon-1");
    expect(screen.getByRole("button", { name: /₩1,000 할인 선택됨/ })).toHaveAttribute("aria-pressed", "true");
  });

  it("sends the selected store coupon with order creation and clears it after success", async () => {
    cart.add(
      { storeId: "store-1", storeName: "시청점" },
      { menuId: "menu-1", optionIds: [], quantity: 1, display: { menuName: "오트 라떼", optionNames: [], unitPriceKrw: 6_000 } },
    );
    couponSelection.select({ storeId: "store-1", couponIssuanceId: "coupon-1", label: "₩1,000 할인" });
    vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
      if (path === "/stores/{storeId}") return ok(customerStore) as never;
      if (path === "/stores/{storeId}/pickup-slots") return ok({ items: [{ pickupSlotId: "slot-1", startsAt: "2026-09-01T01:00:00Z", endsAt: "2026-09-01T01:10:00Z", remainingCapacity: 2 }] }) as never;
      throw new Error(`unexpected GET ${path}`);
    });
    const post = vi.spyOn(customerApi, "POST").mockImplementation(async (path: string) => {
      if (path === "/me/order-quotes") return ok({
        quotedAt: "2026-09-01T00:55:00Z",
        quoteFingerprint: "a".repeat(64),
        store: { storeId: "store-1", name: "시청점" },
        pickupWindow: { startsAt: "2026-09-01T01:00:00Z", endsAt: "2026-09-01T01:10:00Z" },
        lines: [{ menuId: "menu-1", menuName: "오트 라떼", quantity: 1, optionNames: [], lineTotalKrw: 6_000 }],
        pricing: { subtotalKrw: 6_000, couponDiscountKrw: 1_000, pointsAppliedKrw: 0, payableKrw: 5_000, currency: "KRW" },
        guarantee: "NONE",
      }) as never;
      if (path === "/orders") return ok({
        order: { orderId: "order-1", publicReference: "BF-TEST-0001", payableKrw: 5_000 },
      }) as never;
      throw new Error(`unexpected POST ${path}`);
    });

    render(
      <MemoryRouter initialEntries={["/app/cart"]}>
        <Routes>
          <Route path="/app/cart" element={<RefreshCartPage />} />
          <Route path="/app/checkout/:orderId" element={<p>결제 이동 완료</p>} />
        </Routes>
      </MemoryRouter>,
    );
    const user = userEvent.setup();
    await user.click(await screen.findByRole("radio", { name: /2잔 가능/ }));
    await user.click(await screen.findByRole("button", { name: /5,000.*주문하기/ }));

    expect(post.mock.calls[0]?.[0]).toBe("/me/order-quotes");
    expect(post.mock.calls[0]?.[1]).toMatchObject({
      body: expect.objectContaining({ couponIssuanceId: "coupon-1" }),
    });
    await waitFor(() => expect(post).toHaveBeenCalledWith("/orders", expect.objectContaining({
      body: expect.objectContaining({
        couponIssuanceId: "coupon-1",
        expectedQuoteFingerprint: "a".repeat(64),
      }),
    })));
    expect(await screen.findByText("결제 이동 완료")).toBeInTheDocument();
    expect(couponSelection.forStore("store-1")).toBeNull();
  });
});

describe("favorite stores", () => {
  it("removes a favorite idempotently and reloads the server-owned list", async () => {
    let items = [customerStore];
    vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
      if (path === "/me/favorite-stores") return ok({ items }) as never;
      throw new Error(`unexpected GET ${path}`);
    });
    vi.spyOn(customerApi, "DELETE").mockImplementation(async () => {
      items = [];
      return noContent() as never;
    });

    renderRoute("/app/favorites", <FavoriteStoresPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "시청점 즐겨찾기 해제" }));
    await waitFor(() => expect(screen.getByText("즐겨찾기한 매장이 없어요")).toBeInTheDocument());
    expect(customerApi.DELETE).toHaveBeenCalledWith("/me/favorite-stores/{storeId}", expect.objectContaining({
      params: expect.objectContaining({ path: { storeId: "store-1" } }),
    }));
  });

  it("keeps the 200-store limit conflict visible on the store action", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(ok({ items: [] }) as never);
    vi.spyOn(customerApi, "PUT").mockResolvedValue({
      error: { code: "FAVORITE_STORE_LIMIT_EXCEEDED", message: "즐겨찾기는 최대 200개까지 저장할 수 있습니다." },
      response: new Response(null, { status: 409 }),
    } as never);

    renderRoute("/", <FavoriteStoreButton storeId="store-1" storeName="시청점" />);
    await userEvent.click(await screen.findByRole("button", { name: "시청점 즐겨찾기 추가" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("최대 200개");
  });
});
