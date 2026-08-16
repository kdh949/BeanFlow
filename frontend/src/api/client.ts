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

function cookieValue(name: string): string | null {
  const prefix = `${name}=`;
  const cookie = document.cookie.split(";").map((part) => part.trim()).find((part) => part.startsWith(prefix));
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : null;
}

export async function merchantCsrfToken(): Promise<string> {
  const result = await api.GET("/auth/merchant/csrf");
  if (!result.response.ok) {
    unwrap(result);
  }
  const token = cookieValue("BEANFLOW_MERCHANT_XSRF");
  if (!token) {
    throw new ApiRequestError(503, "CSRF_TOKEN_UNAVAILABLE", "보안 토큰을 준비하지 못했습니다. 다시 시도해 주세요.");
  }
  return token;
}

export async function customerCsrfToken(): Promise<string> {
  const result = await api.GET("/auth/customer/csrf");
  if (!result.response.ok) {
    unwrap(result);
  }
  const token = cookieValue("BEANFLOW_CUSTOMER_XSRF");
  if (!token) {
    throw new ApiRequestError(503, "CSRF_TOKEN_UNAVAILABLE", "보안 토큰을 준비하지 못했습니다. 다시 시도해 주세요.");
  }
  return token;
}

/**
 * Keeps one key only for the lifetime of a single unresolved submit intent.
 * A network retry of the same payload reuses the key; success or an explicit
 * draft change rotates it so a later logical command cannot replay an old one.
 */
export class SubmissionIntent {
  private fingerprint: string | null = null;
  private key: string | null = null;

  keyFor(fingerprint: string): string {
    if (this.fingerprint !== fingerprint || this.key === null) {
      this.fingerprint = fingerprint;
      this.key = crypto.randomUUID();
    }
    return this.key;
  }

  rotate() {
    this.fingerprint = null;
    this.key = null;
  }

  complete() {
    this.rotate();
  }
}
