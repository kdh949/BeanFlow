import { act, cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { RefreshCustomerOrderDetailPage } from "../../presentation/beanflow-refresh/CustomerTransactionPages";

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

it("removes the previous pickup state after a failed poll and automatically recovers", async () => {
  vi.useFakeTimers();
  let reads = 0;
  const current = { orderReference: "BF-7K3M-9Q2P", storeId: "store-1", pickupNumber: "A-142", storeName: "시청점", status: "READY", orderedAt: "2026-08-15T03:00:00Z", pickupWindowStart: "2026-08-15T03:20:00Z", pickupWindowEnd: "2026-08-15T03:30:00Z", pricing: { subtotalKrw: 5000, couponDiscountKrw: 0, pointsAppliedKrw: 0, payableKrw: 5000, currency: "KRW" }, lines: [{ lineSequence: 1, menuName: "라떼", optionNames: [], quantity: 1, lineTotalKrw: 5000 }], allowedActions: [] };
  vi.spyOn(customerApi, "GET").mockImplementation(async (path) => {
    if (path === "/me/notification-summary") return { data: { hasUnread: false }, response: new Response() } as never;
    if (++reads === 2) return { error: { code: "DEPENDENCY_UNAVAILABLE" }, response: new Response(null, { status: 503 }) } as never;
    return { data: current, response: new Response() } as never;
  });
  render(<MemoryRouter initialEntries={["/app/orders/BF-7K3M-9Q2P"]}><Routes><Route path="/app/orders/:orderReference" element={<RefreshCustomerOrderDetailPage />} /></Routes></MemoryRouter>);
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  expect(screen.getByText("픽업할 준비가 끝났어요")).toBeInTheDocument();
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(screen.getByRole("alert")).toBeInTheDocument();
  expect(screen.queryByText("픽업할 준비가 끝났어요")).not.toBeInTheDocument();
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(screen.getByText("픽업할 준비가 끝났어요")).toBeInTheDocument();
  expect(reads).toBe(3);
  cleanup();
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(reads).toBe(3);
});
