import createClient, { type Middleware } from "openapi-fetch";
import type { paths } from "./schema";
import { apiBaseUrl } from "./client";
import { authToken } from "../auth/session";

/**
 * The operations console authenticates with an operator-supplied Bearer token.
 * It is a separate client from the customer and merchant Session clients so
 * that adding an endpoint can never route an operator token into a browser
 * Session request — the Operations Chain is the only chain that accepts one.
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

export const operationsApi = bearerClient();
