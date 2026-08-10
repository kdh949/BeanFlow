import { describe, expect, it } from "vitest";
import { clearPaymentSuccessQuery, failureMessage, orderTimelineModel } from "./CustomerPages";
import type { components } from "../../api/schema";

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

describe("order timeline state mapping", () => {
  type OrderState = components["schemas"]["OrderState"];
  const expected: Record<OrderState, ReturnType<typeof orderTimelineModel>["kind"]> = {
    PENDING_PAYMENT: "pending",
    PAID: "progress",
    ACCEPTED: "progress",
    PREPARING: "progress",
    READY: "progress",
    COMPLETED: "progress",
    EXPIRED: "terminal",
    CANCELLED: "terminal",
    REJECTED: "terminal",
  };

  it("maps every OpenAPI OrderState without activating paid for pending or terminal orders", () => {
    for (const [state, kind] of Object.entries(expected) as Array<[OrderState, typeof expected[OrderState]]>) {
      const model = orderTimelineModel(state);
      expect(model.kind).toBe(kind);
      if (kind !== "progress") expect(model.activeIndex).toBeNull();
    }
  });
});
