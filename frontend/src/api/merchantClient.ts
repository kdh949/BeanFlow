import createClient, { type Middleware } from "openapi-fetch";
import type { paths } from "./schema";
import { ApiRequestError, CSRF_HEADER, apiBaseUrl, cookieValue, isUnsafeMethod, unwrap } from "./client";

const CSRF_COOKIE = "BEANFLOW_MERCHANT_XSRF";

/**
 * Merchant requests authenticate with the HttpOnly Session Cookie only. The
 * Merchant Chain rejects a request that also carries a Bearer token, so this
 * client never attaches one and an operator token in the same browser cannot
 * leak into a store request.
 */
export const merchantApi = createClient<paths>({
  baseUrl: apiBaseUrl(),
  credentials: "same-origin",
  // Resolved per call so the running environment owns fetch, not module load order.
  fetch: (request) => globalThis.fetch(request),
});

const merchantCredentials: Middleware = {
  async onRequest({ request }) {
    request.headers.delete("Authorization");
    request.headers.set("Accept", "application/json");
    if (isUnsafeMethod(request.method) && !request.headers.has(CSRF_HEADER)) {
      throw new ApiRequestError(
        0,
        "CSRF_TOKEN_MISSING",
        "보안 토큰 없이 요청을 보낼 수 없습니다. 다시 시도해 주세요.",
      );
    }
    return request;
  },
};

merchantApi.use(merchantCredentials);

/**
 * Issues the JS-readable merchant CSRF cookie when it is absent and returns its
 * value. The cookie carries no authentication and is never persisted by the app.
 */
export async function merchantCsrfToken(): Promise<string> {
  const existing = cookieValue(CSRF_COOKIE);
  if (existing) return existing;
  const result = await merchantApi.GET("/auth/merchant/csrf");
  if (!result.response.ok) {
    unwrap(result);
  }
  const token = cookieValue(CSRF_COOKIE);
  if (!token) {
    throw new ApiRequestError(503, "CSRF_TOKEN_UNAVAILABLE", "보안 토큰을 준비하지 못했습니다. 다시 시도해 주세요.");
  }
  return token;
}

export async function merchantCsrfHeader(): Promise<{ "X-BEANFLOW-CSRF": string }> {
  return { [CSRF_HEADER]: await merchantCsrfToken() } as { "X-BEANFLOW-CSRF": string };
}

export function forgetMerchantCsrfToken() {
  document.cookie = `${CSRF_COOKIE}=; Max-Age=0; path=/`;
}
