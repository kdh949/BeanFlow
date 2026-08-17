import createClient, { type Middleware } from "openapi-fetch";
import type { paths } from "./schema";
import { ApiRequestError, CSRF_HEADER, apiBaseUrl, cookieValue, isUnsafeMethod, unwrap } from "./client";

const CSRF_COOKIE = "BEANFLOW_CUSTOMER_XSRF";

/**
 * Customer requests authenticate with the HttpOnly Session Cookie only. The
 * client never reads that cookie and never attaches an Authorization header, so
 * an operator or merchant token present in this browser cannot leak into a
 * customer request.
 */
export const customerApi = createClient<paths>({
  baseUrl: apiBaseUrl(),
  credentials: "same-origin",
  // Resolved per call so the running environment owns fetch, not module load order.
  fetch: (request) => globalThis.fetch(request),
});

const customerCredentials: Middleware = {
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

customerApi.use(customerCredentials);

/**
 * Issues the JS-readable customer CSRF cookie when it is absent and returns its
 * value. The cookie carries no authentication and is never persisted by the app.
 */
export async function customerCsrfToken(): Promise<string> {
  const existing = cookieValue(CSRF_COOKIE);
  if (existing) return existing;
  const result = await customerApi.GET("/auth/customer/csrf");
  if (!result.response.ok) {
    unwrap(result);
  }
  const token = cookieValue(CSRF_COOKIE);
  if (!token) {
    throw new ApiRequestError(503, "CSRF_TOKEN_UNAVAILABLE", "보안 토큰을 준비하지 못했습니다. 다시 시도해 주세요.");
  }
  return token;
}

export async function customerCsrfHeader(): Promise<{ "X-BEANFLOW-CSRF": string }> {
  return { [CSRF_HEADER]: await customerCsrfToken() } as { "X-BEANFLOW-CSRF": string };
}

/**
 * Discards the browser's stale CSRF value before asking the customer CSRF
 * endpoint for one new token. Callers must use this only after the server has
 * explicitly returned `CSRF_TOKEN_INVALID`, never as a generic retry path.
 */
export async function reissueCustomerCsrfHeader(): Promise<{ "X-BEANFLOW-CSRF": string }> {
  forgetCustomerCsrfToken();
  return customerCsrfHeader();
}

export function forgetCustomerCsrfToken() {
  document.cookie = `${CSRF_COOKIE}=; Max-Age=0; path=/`;
}
