import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { merchantApi } from "../../api/merchantClient";
import { merchantSession } from "../auth/merchant/merchantSession";
import { RefundOutcome, StoreRefundPage } from "./StoreRefundPage";

const storeId = "10000000-0000-4000-8000-000000000001";
const orderReference = "BF-7K3M-9Q2P";

function sourceFiles(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) return sourceFiles(path);
    return /\.(ts|tsx)$/.test(entry) && !/\.(test|stories)\.tsx?$/.test(entry) ? [path] : [];
  });
}

function preview(selectedQuantity: number) {
  return {
    orderReference,
    orderContext: {
      orderedAt: "2026-08-17T02:50:00Z",
      pickupWindow: { startsAt: "2026-08-17T03:20:00Z", endsAt: "2026-08-17T03:30:00Z" },
      status: "PAID" as const,
      pricing: { subtotalKrw: 8_000, couponDiscountKrw: 0, pointsAppliedKrw: 0, payableKrw: 8_000, currency: "KRW" as const },
      paymentKind: "ONE_TIME_EXTERNAL" as const,
    },
    lines: [
      {
        lineSequence: 0,
        menuName: "아이스 아메리카노",
        selectedQuantity,
        remainingQuantity: 2,
        grossAttributionKrw: selectedQuantity * 4_000,
        couponAttributionKrw: 0,
        pointsRestorationKrw: 0,
        cashRefundKrw: selectedQuantity * 4_000,
      },
    ],
    totals: {
      grossAttributionKrw: selectedQuantity * 4_000,
      couponAttributionKrw: 0,
      pointsRestorationKrw: 0,
      cashRefundKrw: selectedQuantity * 4_000,
      currency: "KRW",
    },
    previewVersion: "a".repeat(64),
  };
}

function renderRefundPage() {
  return render(
    <MemoryRouter initialEntries={[`/store/refunds/${storeId}/${orderReference}`]}>
      <Routes>
        <Route path="/store/refunds/:storeId/:orderReference" element={<StoreRefundPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  merchantSession.reset();
  document.cookie = "BEANFLOW_MERCHANT_XSRF=test-merchant-csrf; path=/";
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("merchant financial screens use no internal identifier", () => {
  const files = sourceFiles("src/features/merchant");

  it("never names a payment or order line identifier in a request", () => {
    const offenders = files.filter((file) => /paymentId|orderLineId/.test(readFileSync(file, "utf8")));
    expect(offenders).toEqual([]);
  });

  it("never imports the operator Bearer token store", () => {
    const offenders = files.filter((file) => /auth\/session"|consoleClient"/.test(readFileSync(file, "utf8")));
    expect(offenders).toEqual([]);
  });

  it("renders no identifier or token entry field on the refund screen", async () => {
    vi.spyOn(merchantApi, "POST").mockResolvedValue({
      data: preview(0),
      response: new Response(null, { status: 200 }),
    } as never);

    const view = renderRefundPage();
    await screen.findByText("아이스 아메리카노");

    for (const field of Array.from(view.container.querySelectorAll("input, textarea"))) {
      const description = [
        field.getAttribute("placeholder"),
        field.getAttribute("name"),
        field.getAttribute("id"),
        field.getAttribute("aria-label"),
      ].join(" ").toLowerCase();
      expect(description).not.toMatch(/uuid|token|토큰|결제 번호|주문 상품 번호/);
    }
  });
});

describe("merchant refund request carries no client-calculated money", () => {
  it("renders only the server supplied safe order context", async () => {
    vi.spyOn(merchantApi, "POST").mockResolvedValue({
      data: preview(0),
      response: new Response(null, { status: 200 }),
    } as never);

    renderRefundPage();

    expect(await screen.findByRole("heading", { name: "환불 대상 주문" })).toBeInTheDocument();
    expect(screen.getByText("일회성 결제")).toBeInTheDocument();
    expect(screen.getByText("결제 금액").nextElementSibling).toHaveTextContent("₩8,000");
    expect(screen.queryByText(/부가세|카드|Provider|paymentId|customerId/i)).not.toBeInTheDocument();
  });

  it("sends only line sequences, quantities, the preview version and a reason", async () => {
    const post = vi.spyOn(merchantApi, "POST").mockImplementation((async (path: string) => {
      if (path.endsWith("/refund-previews")) {
        return { data: preview(1), response: new Response(null, { status: 200 }) };
      }
      return {
        data: {
          orderReference,
          state: "SUCCEEDED",
          cashRefundRequestedKrw: 4_000,
          cashRefundedKrw: 4_000,
          pointsRestorationRequestedKrw: 0,
          pointsRestorationState: "NOT_REQUIRED",
          currency: "KRW",
          createdAt: "2026-08-17T03:00:00Z",
          updatedAt: "2026-08-17T03:00:01Z",
          correlationId: "REQ-TEST-1",
        },
        response: new Response(null, { status: 200 }),
      };
    }) as never);

    renderRefundPage();
    await screen.findByText("아이스 아메리카노");
    await userEvent.type(screen.getByLabelText("환불 사유"), "고객 요청");
    await userEvent.click(screen.getByRole("button", { name: /부분 환불 실행/ }));

    await waitFor(() => {
      const calls = post.mock.calls as unknown as Array<[string, { body: Record<string, unknown> }]>;
      const executeCall = calls.find(([path]) => path.endsWith("/refunds"));
      expect(executeCall).toBeDefined();
      const body = (executeCall as [string, { body: Record<string, unknown> }])[1].body;
      expect(Object.keys(body).sort()).toEqual(["lines", "previewVersion", "reason"]);
      expect(body.lines).toEqual([{ lineSequence: 0, quantity: 1 }]);
      expect(JSON.stringify(body)).not.toMatch(/Krw|amount/i);
    });
  });
});

describe("refund preview requests do not race", () => {
  it("keeps the latest quantity selection even when an older preview response arrives last", async () => {
    let requestCount = 0;
    const pending: Array<{ quantity: number; resolve: () => void }> = [];
    vi.spyOn(merchantApi, "POST").mockImplementation((async (
      path: string,
      options: { body?: { lines?: Array<{ quantity: number }> } },
    ) => {
      if (!path.endsWith("/refund-previews")) throw new Error(`unexpected POST ${path}`);
      requestCount += 1;
      if (requestCount === 1) {
        return { data: preview(0), response: new Response(null, { status: 200 }) };
      }
      const quantity = options.body?.lines?.[0]?.quantity ?? 0;
      return new Promise((resolve) => {
        pending.push({
          quantity,
          resolve: () => resolve({ data: preview(quantity), response: new Response(null, { status: 200 }) }),
        });
      });
    }) as never);

    renderRefundPage();
    const quantityInput = (await screen.findByLabelText("환불 수량")) as HTMLInputElement;

    fireEvent.change(quantityInput, { target: { value: "1" } });
    fireEvent.change(quantityInput, { target: { value: "2" } });

    await waitFor(() => expect(pending).toHaveLength(2));
    expect(pending.map((request) => request.quantity)).toEqual([1, 2]);

    // The newer "quantity 2" request settles first, the stale "quantity 1"
    // request settles after it. The stale answer must not win.
    pending[1]?.resolve();
    await waitFor(() => expect(quantityInput).toHaveValue(2));
    pending[0]?.resolve();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(quantityInput).toHaveValue(2);
  });
});

describe("RefundOutcome covers every contract state", () => {
  const base = {
    orderReference,
    cashRefundRequestedKrw: 4_000,
    pointsRestorationRequestedKrw: 0,
    pointsRestorationState: "NOT_REQUIRED" as const,
    currency: "KRW" as const,
    createdAt: "2026-08-17T03:00:00Z",
    updatedAt: "2026-08-17T03:00:01Z",
    correlationId: "REQ-TEST-1",
  };

  afterEach(cleanup);

  it("shows a definitive failure instead of a pending message", () => {
    render(<RefundOutcome result={{ ...base, state: "FAILED" }} />);
    expect(screen.getByText("환불에 실패했습니다")).toBeInTheDocument();
    expect(screen.queryByText(/아직 성공도 실패도 아닙니다/)).not.toBeInTheDocument();
  });

  it("shows a manual review notice instead of a pending message", () => {
    render(<RefundOutcome result={{ ...base, state: "MANUAL_REVIEW" }} />);
    expect(screen.getByText("운영팀 확인이 필요합니다")).toBeInTheDocument();
    expect(screen.queryByText(/아직 성공도 실패도 아닙니다/)).not.toBeInTheDocument();
  });

  it("still shows the pending message for an unresolved Provider outcome", () => {
    render(<RefundOutcome result={{ ...base, state: "UNKNOWN" }} />);
    expect(screen.getByText("환불 결과를 확인하고 있습니다")).toBeInTheDocument();
    expect(screen.getByText(/아직 성공도 실패도 아닙니다/)).toBeInTheDocument();
  });

  it("shows the confirmed success message", () => {
    render(<RefundOutcome result={{ ...base, state: "SUCCEEDED", cashRefundedKrw: 4_000 }} />);
    expect(screen.getByText("현금 환불이 확인되었습니다")).toBeInTheDocument();
  });
});
