import createClient, { type Middleware } from "openapi-fetch";
import type { paths } from "./schema";
import { authToken } from "../auth/session";

export type ApiErrorBody = {
  code?: string;
  message?: string;
  correlationId?: string;
  details?: Array<{ field?: string; reason: string }>;
};

export class ApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly correlationId?: string,
  ) {
    super(message);
    this.name = "ApiRequestError";
  }
}

export const api = createClient<paths>({ baseUrl: "/api/v1" });

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

api.use(authentication);

export function unwrap<T>(result: {
  data?: T;
  error?: unknown;
  response: Response;
}): T {
  if (result.data !== undefined) {
    return result.data;
  }
  const error = (result.error ?? {}) as ApiErrorBody;
  throw new ApiRequestError(
    result.response.status,
    error.code ?? "UNEXPECTED_RESPONSE",
    error.message ?? "요청을 완료하지 못했습니다.",
    error.correlationId,
  );
}

export function idempotencyKey(scope: string): string {
  const storageKey = `beanflow.idempotency.${scope}`;
  const stored = sessionStorage.getItem(storageKey);
  if (stored) return stored;
  const created = crypto.randomUUID();
  sessionStorage.setItem(storageKey, created);
  return created;
}
