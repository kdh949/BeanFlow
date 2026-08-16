import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiRequestError, customerCsrfToken, SubmissionIntent, idempotencyKey, unwrap } from "./client";

afterEach(() => {
  vi.restoreAllMocks();
  document.cookie = "BEANFLOW_CUSTOMER_XSRF=; Max-Age=0; path=/";
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

  it("issues and reads the customer CSRF token without persisting it", async () => {
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
    const get = vi.spyOn(api, "GET").mockResolvedValue({ response: new Response(null, { status: 204 }) } as never);

    await expect(customerCsrfToken()).resolves.toBe("customer-csrf-token");
    expect(get).toHaveBeenCalledWith("/auth/customer/csrf");
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
