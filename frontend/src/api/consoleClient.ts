import createClient, { type Middleware } from "openapi-fetch";
import type { paths } from "./schema";
import { ApiRequestError, CSRF_HEADER, apiBaseUrl, cookieValue, unwrap } from "./client";
import { authToken } from "../auth/session";

/**
 * Merchant and operations consoles still authenticate with an operator-supplied
 * Bearer token. They are separate clients from the customer one so that adding a
 * new customer endpoint can never route a token into a Session request by
 * accident.
 */
function bearerClient() {
  const client = createClient<paths>({
    baseUrl: apiBaseUrl(),
    credentials: "same-origin",
    fetch: (request) => globalThis.fetch(request),
  });
  const authentication: Middleware = {
    async onRequest({ request }) {
      const token = authToken.get();
      if (token) {
        request.headers.set("Authorization", `Bearer ${token}`);
      }
      request.headers.set("Accept", "application/json");
      return request;
    },
  };
  client.use(authentication);
  return client;
}

export const merchantApi = bearerClient();
export const operationsApi = bearerClient();

export async function merchantCsrfToken(): Promise<string> {
  const result = await merchantApi.GET("/auth/merchant/csrf");
  if (!result.response.ok) {
    unwrap(result);
  }
  const token = cookieValue("BEANFLOW_MERCHANT_XSRF");
  if (!token) {
    throw new ApiRequestError(503, "CSRF_TOKEN_UNAVAILABLE", "보안 토큰을 준비하지 못했습니다. 다시 시도해 주세요.");
  }
  return token;
}

export async function merchantCsrfHeader(): Promise<{ "X-BEANFLOW-CSRF": string }> {
  return { [CSRF_HEADER]: await merchantCsrfToken() } as { "X-BEANFLOW-CSRF": string };
}
