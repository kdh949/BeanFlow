import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { CustomerPointsPage } from "./PointsPage";

function ok<T>(data: T) {
  return { data, response: new Response(null, { status: 200 }) };
}

function failed(status: number, code: string, message: string) {
  return { error: { code, message }, response: new Response(null, { status }) };
}

function routeGet(routes: Record<string, unknown>) {
  return vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
    if (!(path in routes)) throw new Error(`unexpected GET ${path}`);
    return routes[path] as never;
  });
}

function renderPoints() {
  return render(<MemoryRouter><CustomerPointsPage /></MemoryRouter>);
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("customer points", () => {
  it("shows a real zero balance as zero", async () => {
    routeGet({
      "/me/points": ok({ availablePointsKrw: 0, recoveryPendingKrw: 0, currency: "KRW", expiring: [] }),
      "/me/point-transactions": ok({ items: [], page: {} }),
    });

    renderPoints();

    expect(await screen.findByText("0P")).toBeInTheDocument();
    expect(await screen.findByText(/곧 만료되는 포인트가 없어요/)).toBeInTheDocument();
    expect(await screen.findByText("아직 포인트 내역이 없어요")).toBeInTheDocument();
  });

  it("never shows a point account integrity failure as a zero balance", async () => {
    routeGet({
      "/me/points": failed(503, "POINT_ACCOUNT_INTEGRITY_FAILURE", "포인트 계정을 확인할 수 없습니다."),
    });

    renderPoints();

    expect(await screen.findByText(/잔액이 0원이라는 뜻은 아니며/)).toBeInTheDocument();
    expect(screen.queryByText("0P")).not.toBeInTheDocument();
    expect(screen.queryByText("포인트 내역")).not.toBeInTheDocument();
  });

  it("lists expiring amounts and recovery pending balance", async () => {
    routeGet({
      "/me/points": ok({
        availablePointsKrw: 1_500,
        recoveryPendingKrw: 300,
        currency: "KRW",
        expiring: [{ expiresAt: "2026-09-01T00:00:00Z", amountKrw: 1_000 }],
      }),
      "/me/point-transactions": ok({ items: [], page: {} }),
    });

    renderPoints();

    expect(await screen.findByText("1,500P")).toBeInTheDocument();
    expect(screen.getByText(/회수 예정인 300P/)).toBeInTheDocument();
    expect(screen.getByText("1,000P")).toBeInTheDocument();
  });

  it("tells the customer when the expiring list was truncated instead of silently dropping later dates", async () => {
    routeGet({
      "/me/points": ok({
        availablePointsKrw: 2_000,
        recoveryPendingKrw: 0,
        currency: "KRW",
        expiring: [{ expiresAt: "2026-09-01T00:00:00Z", amountKrw: 100 }],
        expiringHasMore: true,
      }),
      "/me/point-transactions": ok({ items: [], page: {} }),
    });

    renderPoints();

    expect(await screen.findByText(/이후에 만료되는 포인트가 더 있어요/)).toBeInTheDocument();
  });

  it("keeps a ledger failure visible instead of showing an empty ledger", async () => {
    routeGet({
      "/me/points": ok({ availablePointsKrw: 500, recoveryPendingKrw: 0, currency: "KRW", expiring: [] }),
      "/me/point-transactions": failed(503, "DEPENDENCY_UNAVAILABLE", "포인트 내역을 조회하지 못했습니다."),
    });

    renderPoints();

    expect(await screen.findByText("포인트 내역을 조회하지 못했습니다.")).toBeInTheDocument();
    expect(screen.queryByText("아직 포인트 내역이 없어요")).not.toBeInTheDocument();
  });

  it("renders signed ledger amounts and asks the server for the next page only by cursor", async () => {
    routeGet({
      "/me/points": ok({ availablePointsKrw: 500, recoveryPendingKrw: 0, currency: "KRW", expiring: [] }),
      "/me/point-transactions": ok({
        items: [
          { transactionId: "transaction-1", type: "ACCRUAL", amountKrw: 200, occurredAt: "2026-08-11T00:00:00Z", sourceReference: "order:1" },
          { transactionId: "transaction-2", type: "USE", amountKrw: -100, occurredAt: "2026-08-10T00:00:00Z", sourceReference: "order:2" },
        ],
        page: { nextCursor: "signed-cursor" },
      }),
    });

    renderPoints();

    expect(await screen.findByText("+200P")).toBeInTheDocument();
    expect(screen.getByText("-100P")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /내역 더 보기/ })).toBeInTheDocument();
  });
});
