import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StrictMode } from "react";
import { BrowserRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { attemptStorage } from "./paymentAttempt";
import { resetConfirmationGuard } from "./usePaymentResolution";
import { PaymentFailPage, PaymentSuccessPage, clearPaymentSuccessQuery, failureMessage } from "./PaymentResultPages";

type ApprovalState = "READY" | "APPROVING" | "APPROVED" | "FAILED" | "UNKNOWN" | "RECONCILING" | "MANUAL_REVIEW";

const payment = (approvalState: ApprovalState) => ({
  paymentId: "payment-id",
  orderReference: "BF-7K3M-9Q2P",
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

function routes() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/app/payments/:paymentId/success" element={<PaymentSuccessPage />} />
        <Route path="/app/payments/:paymentId/fail" element={<PaymentFailPage />} />
      </Routes>
    </BrowserRouter>
  );
}

function renderAt(url: string) {
  window.history.replaceState(null, "", url);
  return render(routes());
}

/** `main.tsx` mounts the app inside `StrictMode`, which runs every effect twice. */
function renderAtUnderStrictMode(url: string) {
  window.history.replaceState(null, "", url);
  return render(<StrictMode>{routes()}</StrictMode>);
}

beforeEach(() => {
  resetConfirmationGuard();
  document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  document.cookie = "BEANFLOW_CUSTOMER_XSRF=; Max-Age=0; path=/";
});

describe("payment success callback sequencing", () => {
  it("clears paymentKey from the browser URL before a CSRF-protected confirmation POST", async () => {
    const sequence: string[] = [];
    const originalReplaceState = window.history.replaceState.bind(window.history);
    vi.spyOn(window.history, "replaceState").mockImplementation((...args) => {
      sequence.push("replace");
      originalReplaceState(...args);
    });
    const post = vi.spyOn(customerApi, "POST").mockImplementation(async () => {
      sequence.push(`post:${window.location.search}`);
      return response(payment("APPROVED")) as never;
    });

    renderAt("/app/payments/payment-id/success?paymentKey=provider-key&orderId=provider-order&amount=4500");

    await screen.findByText("결제가 완료됐어요");
    expect(sequence).toContain("post:");
    expect(sequence.indexOf("replace")).toBeLessThan(sequence.indexOf("post:"));
    expect(window.location.search).toBe("");
    expect(post.mock.calls[0]?.[1]).toMatchObject({
      params: { header: { "X-BEANFLOW-CSRF": "customer-csrf-token" } },
    });
  });

  it("uses status GET and never replays confirmation after a clean URL reload", async () => {
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("APPROVED")) as never);
    const post = vi.spyOn(customerApi, "POST");

    renderAt("/app/payments/payment-id/success");

    await screen.findByText("결제가 완료됐어요");
    expect(screen.getByText("BF-7K3M-9Q2P")).toBeInTheDocument();
    expect(screen.queryByText("order-id")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "주문 상태 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
    expect(get).toHaveBeenCalledTimes(1);
    expect(post).not.toHaveBeenCalled();
  });

  it("never reports an unapproved payment as completed on the success URL", async () => {
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("READY")) as never);

    renderAt("/app/payments/payment-id/success");

    expect(await screen.findByText("아직 결제가 끝나지 않았어요")).toBeInTheDocument();
    expect(screen.queryByText("결제가 완료됐어요")).not.toBeInTheDocument();
    expect(screen.getByText("결제 전")).toBeInTheDocument();
    expect(screen.queryByText("준비 완료")).not.toBeInTheDocument();
    expect(screen.queryByText("order-id")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "주문 상태 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
    expect(get).toHaveBeenCalled();
  });

  it("reports a declined payment on the success URL instead of a success mark", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("FAILED")) as never);

    renderAt("/app/payments/payment-id/success");

    expect(await screen.findByText("결제를 완료하지 못했어요")).toBeInTheDocument();
    expect(screen.queryByText("결제가 완료됐어요")).not.toBeInTheDocument();
  });

  it("rejects a callback that does not match the opened attempt", async () => {
    attemptStorage.save({
      paymentId: "payment-id",
      providerOrderId: "provider-order",
      customerKey: "customer-key",
      method: "CARD",
      amount: { value: 4_500, currency: "KRW" },
      orderName: "아메리카노",
      successUrl: "https://beanflow.test/success",
      failUrl: "https://beanflow.test/fail",
    } as never);
    const post = vi.spyOn(customerApi, "POST");

    renderAt("/app/payments/payment-id/success?paymentKey=provider-key&orderId=other-order&amount=4500");

    expect(await screen.findByText(/결제창에서 돌아온 정보가 주문과 일치하지 않습니다/)).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });
});

describe("payment confirmation recovery", () => {
  it("never sends a second confirmation after the first one is lost", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const post = vi.spyOn(customerApi, "POST").mockRejectedValue(new TypeError("network error"));
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("UNKNOWN")) as never);

    renderAt("/app/payments/payment-id/success?paymentKey=provider-key&orderId=provider-order&amount=4500");

    await screen.findByText("결제 결과를 확인하고 있어요");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });

    expect(post).toHaveBeenCalledTimes(1);
    expect(get.mock.calls.length).toBeGreaterThan(1);
    expect(get.mock.calls.every((call) => call[0] === "/payments/{paymentId}")).toBe(true);
  });

  it("keeps the confirmation retryable when the CSRF token cannot be prepared before it is sent", async () => {
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=; Max-Age=0; path=/";
    vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
      if (path !== "/auth/customer/csrf") throw new Error(`unexpected GET ${path}`);
      return {
        error: { code: "DEPENDENCY_UNAVAILABLE", message: "인증 의존성을 사용할 수 없습니다." },
        response: new Response(null, { status: 503 }),
      } as never;
    });
    const post = vi.spyOn(customerApi, "POST").mockResolvedValue(response(payment("APPROVED")) as never);

    renderAt("/app/payments/payment-id/success?paymentKey=provider-key&orderId=provider-order&amount=4500");

    expect(await screen.findByText("서비스 연결을 확인하고 있습니다")).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();

    // The CSRF cookie becomes available again (e.g. the dependency recovered);
    // retrying must still send the confirmation, not just re-read a status
    // that was never asked to change.
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
    await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));

    await screen.findByText("결제가 완료됐어요");
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0]?.[1]).toMatchObject({
      params: { header: { "X-BEANFLOW-CSRF": "customer-csrf-token" } },
    });
  });

  it("keeps one polling loop when the browser reports coming back online", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("RECONCILING")) as never);

    renderAt("/app/payments/payment-id/success");
    await screen.findByText("결제 결과를 확인하고 있어요");

    await act(async () => {
      window.dispatchEvent(new Event("online"));
      window.dispatchEvent(new Event("online"));
      window.dispatchEvent(new Event("online"));
      await vi.advanceTimersByTimeAsync(0);
    });
    const afterWake = get.mock.calls.length;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500);
    });

    expect(get.mock.calls.length).toBeLessThanOrEqual(afterWake + 1);
  });

  it("keeps reading the payment when the effect is remounted while a read is in flight", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("APPROVING")) as never);

    renderAtUnderStrictMode("/app/payments/payment-id/success");

    await screen.findByText("결제 결과를 확인하고 있어요");
    const afterMount = get.mock.calls.length;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });

    expect(get.mock.calls.length).toBeGreaterThan(afterMount);
  });

  it("stops polling once the payment is approved", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("APPROVED")) as never);

    renderAt("/app/payments/payment-id/success");
    await screen.findByText("결제가 완료됐어요");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });

    expect(get).toHaveBeenCalledTimes(1);
  });

  it("stops automatic confirmation and polling at manual review and offers only public tracking and help", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("MANUAL_REVIEW")) as never);
    const post = vi.spyOn(customerApi, "POST");

    renderAt("/app/payments/payment-id/success");

    await screen.findByText("결제 확인에 시간이 더 필요해요");
    expect(screen.getByText("BF-7K3M-9Q2P")).toBeInTheDocument();
    expect(screen.queryByText("order-id")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "주문 상태 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
    expect(screen.getByRole("link", { name: "도움이 필요해요" })).toHaveAttribute("href", "/app/help");
    expect(screen.queryByRole("link", { name: "주문서로 돌아가기" })).not.toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();

    await act(async () => {
      window.dispatchEvent(new Event("online"));
      await vi.advanceTimersByTimeAsync(30_000);
    });

    expect(get).toHaveBeenCalledTimes(1);
  });
});

describe("payment fail callback reconciliation", () => {
  it("queries server status and never posts confirmation", async () => {
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("READY")) as never);
    const post = vi.spyOn(customerApi, "POST");

    renderAt("/app/payments/payment-id/fail?code=PAY_PROCESS_CANCELED");

    await screen.findByText("결제를 완료하지 못했어요");
    expect(get).toHaveBeenCalledTimes(1);
    expect(post).not.toHaveBeenCalled();
    expect(screen.getByRole("link", { name: "주문 상태 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
  });

  it("does not offer a new payment while the server is reconciling", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("UNKNOWN")) as never);

    renderAt("/app/payments/payment-id/fail?code=PAY_PROCESS_ABORTED");

    await screen.findByText("결제 결과를 확인하고 있어요");
    expect(screen.queryByRole("link", { name: "주문서로 돌아가기" })).not.toBeInTheDocument();
  });

  it("keeps manual review terminal and exposes only public tracking and help", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const get = vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("MANUAL_REVIEW")) as never);

    renderAt("/app/payments/payment-id/fail?code=PAY_PROCESS_ABORTED");

    await screen.findByText("결제 확인에 시간이 더 필요해요");
    expect(screen.getByRole("link", { name: "주문 상태 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
    expect(screen.getByRole("link", { name: "도움이 필요해요" })).toHaveAttribute("href", "/app/help");
    expect(screen.queryByRole("link", { name: "주문서로 돌아가기" })).not.toBeInTheDocument();

    await act(async () => {
      window.dispatchEvent(new Event("online"));
      await vi.advanceTimersByTimeAsync(30_000);
    });

    expect(get).toHaveBeenCalledTimes(1);
  });

  it("offers public order tracking only on an explicit decline", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("FAILED")) as never);

    renderAt("/app/payments/payment-id/fail?code=REJECT_CARD_COMPANY");

    await screen.findByText("결제를 완료하지 못했어요");
    expect(screen.getByRole("link", { name: "주문 상태 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
  });

  it("redirects an already approved payment to the clean success status route", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(response(payment("APPROVED")) as never);

    renderAt("/app/payments/payment-id/fail?code=PAY_PROCESS_ABORTED");

    await waitFor(() => expect(window.location.pathname).toBe("/app/payments/payment-id/success"));
  });
});

describe("Toss payment failure copy", () => {
  it("maps known public SDK codes to customer copy", () => {
    expect(failureMessage("PAY_PROCESS_CANCELED")).toContain("취소");
    expect(failureMessage("REJECT_CARD_COMPANY")).toContain("카드사");
  });

  it("never renders an untrusted provider message", () => {
    const untrusted = "<script>alert('card')</script>";
    expect(failureMessage(untrusted)).not.toContain(untrusted);
    expect(failureMessage(untrusted)).toContain("안전하게 다시 시도");
  });
});

describe("Toss payment success callback URL", () => {
  it("removes the callback query while preserving the route and hash", () => {
    const calls: Array<[unknown, string, string | URL | null | undefined]> = [];
    const history = {
      state: { navigation: "state" },
      replaceState: (...args: [unknown, string, string | URL | null | undefined]) => calls.push(args),
    };

    clearPaymentSuccessQuery(
      {
        pathname: "/app/payments/payment-id/success",
        search: "?paymentKey=provider-key&orderId=provider-order&amount=4500",
        hash: "#receipt",
      },
      history,
    );

    expect(calls).toEqual([
      [{ navigation: "state" }, "", "/app/payments/payment-id/success#receipt"],
    ]);
  });

  it("does not rewrite a clean success URL", () => {
    const history = {
      state: null,
      replaceState: () => {
        throw new Error("replaceState should not be called");
      },
    };

    clearPaymentSuccessQuery(
      { pathname: "/app/payments/payment-id/success", search: "", hash: "" },
      history,
    );
  });
});
