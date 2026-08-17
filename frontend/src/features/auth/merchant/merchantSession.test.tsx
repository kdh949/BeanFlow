import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { merchantApi } from "../../../api/merchantClient";
import { clearMerchantBrowserState, merchantSession } from "./merchantSession";

const actor = {
  actorType: "MERCHANT" as const,
  merchantId: "merchant-id",
  displayName: "김도현",
  accountState: "ACTIVE" as const,
};

function ok<T>(data: T, status = 200) {
  return { data, response: new Response(null, { status }) };
}

function failure(status: number, code: string, message = "요청을 완료하지 못했습니다.") {
  return { error: { code, message }, response: new Response(null, { status }) };
}

/** Lets a test control exactly when a mocked request "arrives". */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

beforeEach(() => {
  merchantSession.reset();
  document.cookie = "BEANFLOW_MERCHANT_XSRF=test-merchant-csrf; path=/";
});

afterEach(() => {
  vi.restoreAllMocks();
  sessionStorage.clear();
  document.cookie = "BEANFLOW_MERCHANT_XSRF=; Max-Age=0; path=/";
});

describe("merchant session request races", () => {
  it("a refresh that was already in flight does not undo a login that finished after it started", async () => {
    const gate = deferred<ReturnType<typeof failure>>();
    vi.spyOn(merchantApi, "GET").mockReturnValue(gate.promise as never);
    vi.spyOn(merchantApi, "POST").mockResolvedValue(ok(actor) as never);

    const refreshPromise = merchantSession.refresh();
    await merchantSession.logIn({ loginId: "owner01", password: "correct-horse-battery" });
    expect(merchantSession.get()).toEqual({ status: "authenticated", actor });

    gate.resolve(failure(401, "UNAUTHORIZED"));
    await refreshPromise;

    expect(merchantSession.get()).toEqual({ status: "authenticated", actor });
  });

  it("a refresh that was already in flight does not undo a logout that finished after it started", async () => {
    const gate = deferred<ReturnType<typeof ok<typeof actor>>>();
    vi.spyOn(merchantApi, "GET").mockReturnValue(gate.promise as never);
    vi.spyOn(merchantApi, "DELETE").mockResolvedValue({ response: new Response(null, { status: 204 }) } as never);

    const refreshPromise = merchantSession.refresh();
    await merchantSession.logOut();
    expect(merchantSession.get()).toEqual({ status: "unauthenticated" });

    gate.resolve(ok(actor));
    await refreshPromise;

    expect(merchantSession.get()).toEqual({ status: "unauthenticated" });
  });
});

describe("merchant logout failure", () => {
  it("keeps the session authenticated and browser state intact when the server call fails", async () => {
    vi.spyOn(merchantApi, "GET").mockResolvedValue(ok(actor) as never);
    await merchantSession.refresh();
    expect(merchantSession.get()).toEqual({ status: "authenticated", actor });
    sessionStorage.setItem("beanflow.merchant.draft", "unsent-form");

    vi.spyOn(merchantApi, "DELETE").mockResolvedValue(failure(503, "DEPENDENCY_UNAVAILABLE") as never);

    await expect(merchantSession.logOut()).rejects.toThrow();

    expect(merchantSession.get()).toEqual({ status: "authenticated", actor });
    expect(sessionStorage.getItem("beanflow.merchant.draft")).toBe("unsent-form");
    expect(document.cookie).toContain("BEANFLOW_MERCHANT_XSRF=test-merchant-csrf");
  });

  it("also stays authenticated when the request itself never reaches the server", async () => {
    vi.spyOn(merchantApi, "GET").mockResolvedValue(ok(actor) as never);
    await merchantSession.refresh();

    vi.spyOn(merchantApi, "DELETE").mockRejectedValue(new TypeError("network error"));

    await expect(merchantSession.logOut()).rejects.toThrow();

    expect(merchantSession.get()).toEqual({ status: "authenticated", actor });
  });
});

describe("merchant logout browser cleanup", () => {
  it("clears only merchant-prefixed storage, leaving another actor's idempotency keys untouched", async () => {
    sessionStorage.setItem("beanflow.merchant.draft", "unsent-form");
    sessionStorage.setItem("beanflow.idempotency.payment-attempt.order-1", "customer-submit-key");
    vi.spyOn(merchantApi, "DELETE").mockResolvedValue({ response: new Response(null, { status: 204 }) } as never);

    await merchantSession.logOut();

    expect(sessionStorage.getItem("beanflow.merchant.draft")).toBeNull();
    expect(sessionStorage.getItem("beanflow.idempotency.payment-attempt.order-1")).toBe("customer-submit-key");
  });

  it("clearMerchantBrowserState never removes a beanflow.idempotency key directly", () => {
    sessionStorage.setItem("beanflow.idempotency.payment-attempt.order-1", "customer-submit-key");

    clearMerchantBrowserState();

    expect(sessionStorage.getItem("beanflow.idempotency.payment-attempt.order-1")).toBe("customer-submit-key");
  });
});
