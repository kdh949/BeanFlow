import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { merchantApi } from "../../api/merchantClient";
import { merchantSession } from "../auth/merchant/merchantSession";
import { StoreRefundPage } from "./StoreRefundPage";

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
