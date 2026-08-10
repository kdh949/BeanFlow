import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { BrowserRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../../api/client";
import { PaymentFailPage, PaymentSuccessPage } from "./CustomerPages";

const payment = (approvalState: "READY" | "APPROVING" | "APPROVED" | "FAILED" | "UNKNOWN" | "RECONCILING" | "MANUAL_REVIEW") => ({
  paymentId: "payment-id",
  orderId: "order-id",
  type: "EXTERNAL" as const,
  approvalState,
  approvedAmountKrw: approvalState === "APPROVED" ? 4_500 : undefined,
  currency: "KRW" as const,
  updatedAt: "2026-08-10T00:00:00Z",
  correlationId: "correlation-id",
});

function response<T>(data: T) {
  return { data, response: new Response(null, { status: 200 }) };
}

function renderAt(url: string) {
  window.history.replaceState(null, "", url);
  return render(
    <BrowserRouter>
      <Routes>
        <Route path="/app/payments/:paymentId/success" element={<PaymentSuccessPage />} />
        <Route path="/app/payments/:paymentId/fail" element={<PaymentFailPage />} />
      </Routes>
    </BrowserRouter>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("payment success callback sequencing", () => {
  it("clears paymentKey from the browser URL before confirmation POST", async () => {
    const sequence: string[] = [];
    const originalReplaceState = window.history.replaceState.bind(window.history);
    vi.spyOn(window.history, "replaceState").mockImplementation((...args) => {
      sequence.push("replace");
      originalReplaceState(...args);
    });
    vi.spyOn(api, "POST").mockImplementation(async () => {
      sequence.push(`post:${window.location.search}`);
      return response(payment("APPROVED")) as never;
    });

    renderAt("/app/payments/payment-id/success?paymentKey=provider-key&orderId=provider-order&amount=4500");

    await screen.findByText("결제가 완료됐어요");
    expect(sequence).toContain("post:");
    expect(sequence.indexOf("replace")).toBeLessThan(sequence.indexOf("post:"));
    expect(window.location.search).toBe("");
  });

  it("uses status GET and never replays confirmation after a clean URL reload", async () => {
    const get = vi.spyOn(api, "GET").mockResolvedValue(response(payment("APPROVED")) as never);
    const post = vi.spyOn(api, "POST");

    renderAt("/app/payments/payment-id/success");

    await screen.findByText("결제가 완료됐어요");
    expect(get).toHaveBeenCalledTimes(1);
    expect(post).not.toHaveBeenCalled();
  });
});

describe("payment fail callback reconciliation", () => {
  it("queries server status and never posts confirmation", async () => {
    const get = vi.spyOn(api, "GET").mockResolvedValue(response(payment("READY")) as never);
    const post = vi.spyOn(api, "POST");

    renderAt("/app/payments/payment-id/fail?code=PAY_PROCESS_CANCELED");

    await screen.findByText("결제를 완료하지 못했어요");
    expect(get).toHaveBeenCalledTimes(1);
    expect(post).not.toHaveBeenCalled();
    expect(screen.getByRole("link", { name: "주문서로 돌아가기" })).toHaveAttribute("href", "/app/checkout/order-id");
  });

  it("does not offer a new payment while the server is reconciling", async () => {
    vi.spyOn(api, "GET").mockResolvedValue(response(payment("UNKNOWN")) as never);

    renderAt("/app/payments/payment-id/fail?code=PAY_PROCESS_ABORTED");

    await screen.findByText("결제 결과를 확인하고 있어요");
    expect(screen.queryByRole("link", { name: "주문서로 돌아가기" })).not.toBeInTheDocument();
  });

  it("redirects an already approved payment to the clean success status route", async () => {
    vi.spyOn(api, "GET").mockResolvedValue(response(payment("APPROVED")) as never);

    renderAt("/app/payments/payment-id/fail?code=PAY_PROCESS_ABORTED");

    await waitFor(() => expect(window.location.pathname).toBe("/app/payments/payment-id/success"));
  });
});
