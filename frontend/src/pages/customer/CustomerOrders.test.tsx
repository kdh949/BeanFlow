import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BrowserRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../../api/client";
import { CustomerOrderDetailPage, CustomerOrdersPage, customerOrderTimelineModel, seoulDate } from "./CustomerOrders";

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
  ...summary,
  allowedActions: ["CANCEL" as const],
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
        <Route path="/app/orders/:orderReference" element={<CustomerOrderDetailPage />} />
      </Routes>
    </BrowserRouter>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("customer order list", () => {
  it("derives API calendar dates in Asia Seoul at the UTC day boundary", () => {
    expect(seoulDate(new Date("2026-08-13T15:30:00Z"))).toBe("2026-08-14");
  });

  it("replaces UUID lookup with active and past tabs, date filters, and public-reference links", async () => {
    vi.spyOn(api, "GET").mockResolvedValue(response({ items: [summary], page: {} }) as never);

    renderAt("/app/orders");

    expect(await screen.findByRole("heading", { name: "주문" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "진행 중" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "지난 주문" })).toBeInTheDocument();
    expect(screen.getByLabelText("조회 시작일")).toBeInTheDocument();
    expect(screen.getByLabelText("조회 종료일")).toBeInTheDocument();
    expect(screen.queryByPlaceholderText("UUID 주문 번호")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /아이스 아메리카노 외 1건/ })).toHaveAttribute(
      "href",
      "/app/orders/BF-7K3M-9Q2P",
    );
  });

  it("reloads the server-owned PAST classification when the tab changes", async () => {
    const get = vi.spyOn(api, "GET").mockResolvedValue(response({ items: [], page: {} }) as never);
    const user = userEvent.setup();
    renderAt("/app/orders");
    await screen.findByRole("heading", { name: "주문" });

    await user.click(screen.getByRole("tab", { name: "지난 주문" }));

    await waitFor(() => {
      expect(get).toHaveBeenLastCalledWith(
        "/me/orders",
        expect.objectContaining({ params: { query: expect.objectContaining({ status: "PAST" }) } }),
      );
    });
  });
});

describe("customer order detail", () => {
  it("loads by public reference and renders the pickup identity and immutable line snapshots", async () => {
    const get = vi.spyOn(api, "GET").mockResolvedValue(response(detail) as never);

    renderAt("/app/orders/BF-7K3M-9Q2P");

    expect(await screen.findByText("A-142")).toBeInTheDocument();
    expect(screen.getByText("강남 2호점")).toBeInTheDocument();
    expect(screen.getByText("아이스 아메리카노")).toBeInTheDocument();
    expect(screen.getByText("ICE · 샷 추가 · 2잔")).toBeInTheDocument();
    expect(screen.getByText("취소 가능한 주문")).toBeInTheDocument();
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
});
