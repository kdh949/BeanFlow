export type ApiErrorBody = {
  code?: string;
  message?: string;
  correlationId?: string;
  details?: Array<{ field?: string; reason: string; lineSequence?: number }>;
};

/**
 * `status` is the HTTP status when the server answered. A request the client
 * refused to send before reaching the network uses status 0 so that a blocked
 * request is never confused with a server decision.
 */
export class ApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly correlationId?: string,
    /** Server-owned per-field or per-item reasons. Never synthesized by the client. */
    readonly details?: ApiErrorBody["details"],
  ) {
    super(message);
    this.name = "ApiRequestError";
  }
}

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
    error.details,
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

export function cookieValue(name: string): string | null {
  const prefix = `${name}=`;
  const cookie = document.cookie.split(";").map((part) => part.trim()).find((part) => part.startsWith(prefix));
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : null;
}

export const CSRF_HEADER = "X-BEANFLOW-CSRF";

/**
 * Absolute same-origin base so that every request carries an explicit origin
 * instead of relying on an ambient document base.
 */
export function apiBaseUrl(): string {
  return `${window.location.origin}/api/v1`;
}

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export function isUnsafeMethod(method: string): boolean {
  return !SAFE_METHODS.has(method.toUpperCase());
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
