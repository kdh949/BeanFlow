import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BrowserRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError } from "../../api/client";
import * as client from "../../api/customerClient";
import { cart } from "../../features/ordering/cart";
import { RefreshCartPage } from "./CustomerCommercePages";

const customerStore = { storeId: "store-1", name: "시청점", orderingAvailable: true, pickupAvailable: true, customerDisplay: { addressLine: "서울시 중구" } };
const line = { menuId: "menu", optionIds: [], quantity: 1, display: { menuName: "라떼", optionNames: [], unitPriceKrw: 5000 } };
const quote = { quoteFingerprint: "a".repeat(64), lines: [{ menuId: "menu", menuName: "라떼", optionNames: [], quantity: 1, lineTotalKrw: 5000 }], pricing: { subtotalKrw: 5000, couponDiscountKrw: 0, pointsAppliedKrw: 0, payableKrw: 5000, currency: "KRW" } };
function response(data: unknown) { return { data, response: new Response(null, { status: 200 }) } as never; }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; }

beforeEach(() => {
  cart.clear();
  cart.add({ storeId: customerStore.storeId, storeName: customerStore.name }, line);
  vi.spyOn(client, "customerCsrfHeader").mockResolvedValue({ "X-BEANFLOW-CSRF": "test" });
  vi.spyOn(client.customerApi, "GET").mockImplementation(async (path) => {
    if (path === "/stores/{storeId}") return response(customerStore);
    if (path === "/me/points") return response({ availablePointsKrw: 1000 });
    if (path === "/stores/{storeId}/menus") return response({ items: [] });
    throw new Error(`Unexpected GET ${path}`);
  });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); cart.clear(); });
function openCart() { return render(<BrowserRouter><RefreshCartPage /></BrowserRouter>); }

describe("cart submission boundaries", () => {
  it("sends one order while CSRF is pending and keeps all competing inputs locked", async () => {
    const order = deferred<never>();
    const post = vi.spyOn(client.customerApi, "POST").mockImplementation(async (path) => path === "/me/order-quotes" ? response(quote) : order.promise);
    openCart();
    const button = await screen.findByRole("button", { name: /5,000.*주문하기/ });
    const csrf = deferred<{ "X-BEANFLOW-CSRF": string }>();
    vi.mocked(client.customerCsrfHeader).mockReturnValueOnce(csrf.promise);
    act(() => { fireEvent.click(button); fireEvent.click(button); });
    expect(screen.getByRole("button", { name: "쿠폰 보기" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "메뉴 더 담기" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "사용할 포인트" })).toBeDisabled();
    await act(async () => { csrf.resolve({ "X-BEANFLOW-CSRF": "test" }); });
    await waitFor(() => expect((post.mock.calls as unknown[][]).filter(([path]) => path === "/orders")).toHaveLength(1));
    await act(async () => { order.resolve({ error: { code: "DEPENDENCY_UNAVAILABLE" }, response: new Response(null, { status: 503 }) } as never); });
  });

  it("does not submit a captured cart after its revision changes during CSRF preparation", async () => {
    const post = vi.spyOn(client.customerApi, "POST").mockResolvedValue(response(quote));
    openCart();
    const button = await screen.findByRole("button", { name: /5,000.*주문하기/ });
    const csrf = deferred<{ "X-BEANFLOW-CSRF": string }>();
    vi.mocked(client.customerCsrfHeader).mockReturnValueOnce(csrf.promise);
    fireEvent.click(button);
    act(() => { cart.setQuantity(0, 2); });
    await act(async () => { csrf.resolve({ "X-BEANFLOW-CSRF": "test" }); });
    expect((post.mock.calls as unknown[][]).filter(([path]) => path === "/orders")).toHaveLength(0);
    expect(cart.read()).toMatchObject({ status: "ready", cart: { lines: [expect.objectContaining({ quantity: 2 })] } });
  });

  it("ignores an old quote arriving after a newer cart quote", async () => {
    const old = deferred<never>();
    let quotes = 0;
    vi.spyOn(client.customerApi, "POST").mockImplementation(async () => ++quotes === 1 ? old.promise : response({ ...quote, pricing: { ...quote.pricing, payableKrw: 10000 } }));
    openCart();
    await waitFor(() => expect(quotes).toBe(1));
    act(() => { cart.setQuantity(0, 2); });
    await screen.findByRole("button", { name: /10,000.*주문하기/ });
    await act(async () => { old.resolve(response(quote)); });
    expect(screen.getByRole("button", { name: /10,000.*주문하기/ })).toBeEnabled();
    expect(screen.queryByRole("button", { name: /5,000.*주문하기/ })).not.toBeInTheDocument();
  });

  it("requires explicit confirmation of a stale quote before allowing another order", async () => {
    const updated = { ...quote, quoteFingerprint: "b".repeat(64), pricing: { ...quote.pricing, payableKrw: 5500 } };
    const post = vi.spyOn(client.customerApi, "POST").mockImplementation(async (path) => {
      if (path === "/me/order-quotes") return response(quote);
      throw new ApiRequestError(409, "ORDER_QUOTE_STALE", "changed", undefined, undefined, updated);
    });
    openCart();
    await userEvent.click(await screen.findByRole("button", { name: /5,000.*주문하기/ }));
    await screen.findByRole("button", { name: "변경 내용 확인" });
    expect(screen.getByRole("button", { name: "견적 확인 후 주문하기" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "변경 내용 확인" }));
    expect(screen.getByRole("button", { name: /5,500.*주문하기/ })).toBeEnabled();
    expect((post.mock.calls as unknown[][]).filter(([path]) => path === "/orders")).toHaveLength(1);
  });

  it("keeps a newer quote when the previous order returns a late stale response", async () => {
    const stale = deferred<never>();
    const post = vi.spyOn(client.customerApi, "POST").mockImplementation(async (path, options) => {
      if (path === "/orders") return stale.promise;
      const quantity = (options as unknown as { body: { lines: Array<{ quantity: number }> } }).body.lines[0]!.quantity;
      return response({ ...quote, pricing: { ...quote.pricing, payableKrw: quantity * 5000 } });
    });
    openCart();
    await userEvent.click(await screen.findByRole("button", { name: /5,000.*주문하기/ }));
    await waitFor(() => expect((post.mock.calls as unknown[][]).filter(([path]) => path === "/orders")).toHaveLength(1));
    act(() => { cart.setQuantity(0, 2); });
    await screen.findByRole("button", { name: /10,000.*주문하기/ });
    await act(async () => { stale.resolve({ error: { code: "ORDER_QUOTE_STALE", currentQuote: quote }, response: new Response(null, { status: 409 }) } as never); });
    expect(screen.getByRole("button", { name: /10,000.*주문하기/ })).toBeEnabled();
    expect(screen.queryByText("주문 금액과 조건이 변경됐어요")).not.toBeInTheDocument();
  });

  it("does not submit after leaving the cart during CSRF preparation", async () => {
    const post = vi.spyOn(client.customerApi, "POST").mockResolvedValue(response(quote));
    const view = openCart();
    const button = await screen.findByRole("button", { name: /5,000.*주문하기/ });
    const csrf = deferred<{ "X-BEANFLOW-CSRF": string }>();
    vi.mocked(client.customerCsrfHeader).mockReturnValueOnce(csrf.promise);
    fireEvent.click(button);
    view.unmount();
    await act(async () => { csrf.resolve({ "X-BEANFLOW-CSRF": "test" }); });
    expect((post.mock.calls as unknown[][]).filter(([path]) => path === "/orders")).toHaveLength(0);
  });
});
