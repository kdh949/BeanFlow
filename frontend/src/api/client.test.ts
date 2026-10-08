import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError, SubmissionIntent, idempotencyKey, unwrap } from "./client";
import { customerApi, customerCsrfHeader, customerCsrfToken } from "./customerClient";
import { operationsApi } from "./consoleClient";
import { merchantApi, merchantCsrfHeader } from "./merchantClient";
import { authToken, operationsAuth } from "../auth/session";

function clearCsrfCookie() {
  document.cookie = "BEANFLOW_CUSTOMER_XSRF=; Max-Age=0; path=/";
  document.cookie = "BEANFLOW_MERCHANT_XSRF=; Max-Age=0; path=/";
}

afterEach(() => {
  vi.restoreAllMocks();
  authToken.clear();
  clearCsrfCookie();
});

describe("operations refresh boundary", () => {
  it("waits for refresh and sends the latest token to both operations and support", async () => {
    authToken.set("old-token");
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    vi.spyOn(operationsAuth, "refreshAccessToken").mockImplementation(async () => {
      await pending;
      authToken.set("refreshed-token");
    });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response("{}", { status: 200 }));
    const requests = [operationsApi.GET("/operations/me"), operationsApi.GET("/support/cases")];
    await Promise.resolve();
    expect(fetchSpy).not.toHaveBeenCalled();
    finish();
    await Promise.all(requests);
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    for (const [request] of fetchSpy.mock.calls) expect((request as Request).headers.get("Authorization")).toBe("Bearer refreshed-token");
  });

  it("does not send a protected request if refresh fails", async () => {
    authToken.set("expired-token");
    vi.spyOn(operationsAuth, "refreshAccessToken").mockRejectedValue(new ApiRequestError(503, "OPERATIONS_TOKEN_REFRESH_UNAVAILABLE", "Refresh unavailable"));
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    await expect(operationsApi.GET("/operations/me")).rejects.toMatchObject({ status: 503 });
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("ends the current credential on 401 without replaying the request", async () => {
    authToken.set("rejected-token");
    const rejected = vi.spyOn(operationsAuth, "rejectToken");
    const refresh = vi.spyOn(operationsAuth, "refreshAccessToken").mockResolvedValue(undefined);
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 401 }));
    const result = await operationsApi.POST("/operations/merchant-accounts/{merchantAccountId}/lock-releases", {
      params: { path: { merchantAccountId: "merchant-fixture" }, header: { "Idempotency-Key": "intent-fixture" } },
      body: { reason: "계정 잠금 해제 테스트" },
    });
    expect(result.response.status).toBe(401);
    expect(authToken.get()).toBe("");
    expect(rejected).toHaveBeenCalledWith("rejected-token");
    expect(fetchSpy).toHaveBeenCalledOnce();
    const sent = fetchSpy.mock.calls[0]![0] as Request;
    expect(sent.method).toBe("POST");
    expect(sent.headers.get("Idempotency-Key")).toBe("intent-fixture");
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("does not clear a new token when an older request returns 401", async () => {
    authToken.set("old-request-token");
    vi.spyOn(operationsAuth, "refreshAccessToken").mockResolvedValue(undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      authToken.set("new-login-token");
      return new Response("{}", { status: 401 });
    });
    const result = await operationsApi.GET("/operations/me");
    expect(result.response.status).toBe(401);
    expect(authToken.get()).toBe("new-login-token");
  });
});

describe("API client helpers", () => {
  it("returns successful generated-client data", () => {
    const response = new Response(null, { status: 200 });
    expect(unwrap({ data: { ok: true }, response })).toEqual({ ok: true });
  });

  it("preserves the server correlation ID on an error", () => {
    const response = new Response(null, { status: 409 });
    try {
      unwrap({ error: { code: "IDEMPOTENCY_KEY_REUSED", message: "요청 키가 재사용되었습니다.", correlationId: "corr-42" }, response });
      expect.fail("unwrap should throw");
    } catch (error) {
      expect(error).toBeInstanceOf(ApiRequestError);
      expect(error).toMatchObject({ status: 409, code: "IDEMPOTENCY_KEY_REUSED", correlationId: "corr-42" });
    }
  });

  it("keeps one idempotency key per browser session and command scope", () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValueOnce("00000000-0000-4000-8000-000000000001").mockReturnValueOnce("00000000-0000-4000-8000-000000000002");
    expect(idempotencyKey("payment.order-1")).toBe("00000000-0000-4000-8000-000000000001");
    expect(idempotencyKey("payment.order-1")).toBe("00000000-0000-4000-8000-000000000001");
    expect(idempotencyKey("payment.order-2")).toBe("00000000-0000-4000-8000-000000000002");
  });

  it("reuses an intent key while the same request is unresolved", () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000010");
    const intent = new SubmissionIntent();

    expect(intent.keyFor("same-draft")).toBe("00000000-0000-4000-8000-000000000010");
    expect(intent.keyFor("same-draft")).toBe("00000000-0000-4000-8000-000000000010");
    expect(crypto.randomUUID).toHaveBeenCalledTimes(1);
  });

  it("rotates after success and after an explicit draft change", () => {
    vi.spyOn(crypto, "randomUUID")
      .mockReturnValueOnce("00000000-0000-4000-8000-000000000011")
      .mockReturnValueOnce("00000000-0000-4000-8000-000000000012")
      .mockReturnValueOnce("00000000-0000-4000-8000-000000000013");
    const intent = new SubmissionIntent();

    expect(intent.keyFor("same-order")).toBe("00000000-0000-4000-8000-000000000011");
    intent.complete();
    expect(intent.keyFor("same-order")).toBe("00000000-0000-4000-8000-000000000012");
    intent.rotate();
    expect(intent.keyFor("changed-order")).toBe("00000000-0000-4000-8000-000000000013");
  });
});

describe("customer CSRF token", () => {
  it("issues the cookie once and reuses it for later commands", async () => {
    const get = vi.spyOn(customerApi, "GET").mockImplementation(async () => {
      document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
      return { response: new Response(null, { status: 204 }) } as never;
    });

    await expect(customerCsrfToken()).resolves.toBe("customer-csrf-token");
    expect(get).toHaveBeenCalledWith("/auth/customer/csrf");

    await expect(customerCsrfHeader()).resolves.toEqual({ "X-BEANFLOW-CSRF": "customer-csrf-token" });
    expect(get).toHaveBeenCalledTimes(1);
  });

  it("fails explicitly when the issue call leaves no cookie", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue({ response: new Response(null, { status: 204 }) } as never);

    await expect(customerCsrfToken()).rejects.toMatchObject({ status: 503, code: "CSRF_TOKEN_UNAVAILABLE" });
  });
});

describe("customer client credential boundary", () => {
  const ok = () => Promise.resolve(new Response("{}", { status: 200, headers: { "content-type": "application/json" } }));

  it("never attaches a Bearer token even when a console token is present", async () => {
    authToken.set("operator-access-token");
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(ok);

    await customerApi.GET("/me");

    const request = fetchSpy.mock.calls[0]?.[0] as Request;
    expect(request.headers.get("Authorization")).toBeNull();
    expect(request.headers.get("Accept")).toBe("application/json");
    expect(request.credentials).toBe("same-origin");
    authToken.clear();
  });

  it("refuses to send an unsafe request without the CSRF header", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(ok);

    await expect(
      customerApi.DELETE("/auth/customer/sessions/current", {
        params: { header: {} as never },
      }),
    ).rejects.toMatchObject({ status: 0, code: "CSRF_TOKEN_MISSING" });
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("sends the CSRF header only on unsafe requests", async () => {
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(ok);

    await customerApi.GET("/me");
    await customerApi.DELETE("/auth/customer/sessions/current", {
      params: { header: await customerCsrfHeader() },
    });

    const [safeRequest, unsafeRequest] = fetchSpy.mock.calls.map((call) => call[0] as Request) as [Request, Request];
    expect(safeRequest.headers.get("X-BEANFLOW-CSRF")).toBeNull();
    expect(unsafeRequest.headers.get("X-BEANFLOW-CSRF")).toBe("customer-csrf-token");
  });

  it("keeps the operations console on Bearer credentials", async () => {
    authToken.set("operator-access-token");
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(ok);

    await operationsApi.GET("/operations/me");

    for (const call of fetchSpy.mock.calls) {
      expect((call[0] as Request).headers.get("Authorization")).toBe("Bearer operator-access-token");
    }
    authToken.clear();
  });
});

describe("merchant client credential boundary", () => {
  const ok = () => Promise.resolve(new Response("{}", { status: 200, headers: { "content-type": "application/json" } }));

  it("never attaches a Bearer token, because the Merchant Chain rejects one", async () => {
    authToken.set("operator-access-token");
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(ok);

    await merchantApi.GET("/merchant/me");

    const request = fetchSpy.mock.calls[0]?.[0] as Request;
    expect(request.headers.get("Authorization")).toBeNull();
    expect(request.credentials).toBe("same-origin");
    authToken.clear();
  });

  it("refuses to send an unsafe merchant request without the CSRF header", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(ok);

    await expect(
      merchantApi.DELETE("/auth/merchant/sessions/current", { params: { header: {} as never } }),
    ).rejects.toMatchObject({ status: 0, code: "CSRF_TOKEN_MISSING" });
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("sends the merchant CSRF header only on unsafe requests", async () => {
    document.cookie = "BEANFLOW_MERCHANT_XSRF=merchant-csrf-token; path=/";
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(ok);

    await merchantApi.GET("/merchant/me");
    await merchantApi.DELETE("/auth/merchant/sessions/current", {
      params: { header: await merchantCsrfHeader() },
    });

    const [safeRequest, unsafeRequest] = fetchSpy.mock.calls.map((call) => call[0] as Request) as [Request, Request];
    expect(safeRequest.headers.get("X-BEANFLOW-CSRF")).toBeNull();
    expect(unsafeRequest.headers.get("X-BEANFLOW-CSRF")).toBe("merchant-csrf-token");
  });
});
