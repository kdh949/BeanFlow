import { act, cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { RefreshCustomerOrderDetailPage } from "../../presentation/beanflow-refresh/CustomerTransactionPages";
import { orderDetail } from "../../../.storybook/fixtures";

afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });
async function show() {
  await act(async () => { render(<MemoryRouter initialEntries={["/app/orders/BF-2345-6789"]}><Routes><Route path="/app/orders/:orderReference" element={<RefreshCustomerOrderDetailPage />} /></Routes></MemoryRouter>); });
}

it.each([401, 403, 404])("stops automatic retries after permanent HTTP %s", async status => {
  vi.useFakeTimers();
  const get = vi.spyOn(customerApi, "GET").mockResolvedValue({ error: { code: "ACCESS_DENIED" }, response: new Response(null, { status }) } as never);
  await show();
  expect(screen.getByRole("alert")).toBeVisible();
  await act(async () => { await vi.advanceTimersByTimeAsync(20_000); });
  expect(get).toHaveBeenCalledOnce();
});

it("recovers automatically from 503 without retaining the failed read as success", async () => {
  vi.useFakeTimers();
  const get = vi.spyOn(customerApi, "GET").mockResolvedValueOnce({ error: { code: "DEPENDENCY_UNAVAILABLE" }, response: new Response(null, { status: 503 }) } as never).mockResolvedValue({ data: { ...orderDetail, status: "COMPLETED", allowedActions: [] }, response: new Response() } as never);
  await show();
  expect(screen.getByRole("alert")).toBeVisible();
  await act(async () => { await vi.advanceTimersByTimeAsync(5_000); });
  expect(screen.getByText("픽업이 완료됐어요")).toBeVisible();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
  expect((get.mock.calls as unknown as [string][]).filter(([path]) => path === "/me/orders/{orderReference}")).toHaveLength(2);
});

it("does not send a scheduled retry while the page is hidden", async () => {
  vi.useFakeTimers();
  const visibility = vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
  const get = vi.spyOn(customerApi, "GET").mockResolvedValue({ error: { code: "DEPENDENCY_UNAVAILABLE" }, response: new Response(null, { status: 503 }) } as never);
  await show();
  visibility.mockReturnValue("hidden");
  await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
  expect(get).toHaveBeenCalledOnce();
  visibility.mockReturnValue("visible");
  await act(async () => { document.dispatchEvent(new Event("visibilitychange")); });
  expect((get.mock.calls as unknown as [string][]).filter(([path]) => path === "/me/orders/{orderReference}")).toHaveLength(2);
});
