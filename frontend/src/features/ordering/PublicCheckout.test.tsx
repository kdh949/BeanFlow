import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { requestTossStandardPayment } from "../../payment/toss";
import { RefreshCheckoutPage } from "../../presentation/beanflow-refresh/CustomerTransactionPages";

vi.mock("../../payment/toss", () => ({ requestTossStandardPayment: vi.fn().mockResolvedValue(undefined) }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); sessionStorage.clear(); });

const order = { orderReference: "BF-7K3M-9Q2P", storeId: "store-1", pickupNumber: "A-1", storeName: "시청점", status: "PENDING_PAYMENT", orderedAt: "2026-08-15T03:00:00Z", reservationExpiresAt: "2099-01-01T00:00:00Z", pickupWindowStart: "2026-08-15T03:20:00Z", pickupWindowEnd: "2026-08-15T03:30:00Z", pricing: { subtotalKrw: 5000, couponDiscountKrw: 0, pointsAppliedKrw: 0, payableKrw: 5000, currency: "KRW" }, lines: [{ lineSequence: 1, menuName: "라떼", optionNames: [], quantity: 1, lineTotalKrw: 5000 }], allowedActions: [] };
const attempt = { paymentId: "payment-1", orderReference: order.orderReference, state: "READY", providerOrderId: "bf_original", customerKey: "customer-key", orderName: "라떼", amount: { value: 5000, currency: "KRW" }, method: "CARD", successUrl: "https://checkout.beanflow.test/success", failUrl: "https://checkout.beanflow.test/fail", expiresAt: order.reservationExpiresAt, updatedAt: "2026-08-15T03:00:00Z", correlationId: "TEST-1" };

function setup(canPay = true) {
  const get = vi.spyOn(customerApi, "GET").mockImplementation(async (path) => ({ data: path === "/me/notification-summary" ? { hasUnread: false } : path === "/payment-config" ? { clientKey: "test_ck_unit" } : { order, canPay, paymentState: canPay ? "READY" : "UNKNOWN", paymentId: attempt.paymentId, ...(canPay ? { readyAttempt: attempt } : {}) }, response: new Response() } as never));
  const post = vi.spyOn(customerApi, "POST");
  render(<MemoryRouter initialEntries={[`/app/orders/${order.orderReference}/checkout`]}><Routes><Route path="/app/orders/:orderReference/checkout" element={<RefreshCheckoutPage />} /></Routes></MemoryRouter>);
  return { get, post };
}

it("rechecks current eligibility and opens the same ready attempt without preparing another payment", async () => {
  const { get, post } = setup();
  await userEvent.click(await screen.findByRole("button", { name: /5,000.*결제하기/ }));
  await waitFor(() => expect(requestTossStandardPayment).toHaveBeenCalledWith("test_ck_unit", expect.objectContaining({ orderId: "bf_original", amount: { value: 5000, currency: "KRW" } })));
  expect((get.mock.calls as unknown as [string][]).filter(([path]) => path === "/me/orders/{orderReference}/checkout").length).toBeGreaterThanOrEqual(2);
  expect(post).not.toHaveBeenCalled();
});

it("never opens the SDK or prepares a new payment for an uncertain result", async () => {
  const { post } = setup(false);
  expect(await screen.findByRole("button", { name: /결제하기/ })).toBeDisabled();
  expect(requestTossStandardPayment).not.toHaveBeenCalled();
  expect(post).not.toHaveBeenCalled();
});


it("keeps the payment control mounted when focus refresh begins during a pointer interaction", async () => {
  setup();
  const button = await screen.findByRole("button", { name: /결제하기/ });
  fireEvent.pointerDown(button);
  fireEvent.focus(window);
  expect(button).toBeInTheDocument();
  fireEvent.pointerUp(button);
  fireEvent.click(button);
  await waitFor(() => expect(requestTossStandardPayment).toHaveBeenCalledOnce());
});

it("allows fresh server eligibility when the device clock is ahead of both deadlines", async () => {
  vi.spyOn(Date, "now").mockReturnValue(Date.parse("2100-01-01T00:00:00Z"));
  setup();
  const button = await screen.findByRole("button", { name: /결제하기/ });
  await waitFor(() => expect(button).toBeEnabled());
  await userEvent.click(button);
  await waitFor(() => expect(requestTossStandardPayment).toHaveBeenCalledOnce());
});
