import { useSyncExternalStore } from "react";
import type { components } from "../../../api/schema";
import { ApiRequestError, unwrap } from "../../../api/client";
import { customerApi, customerCsrfHeader, forgetCustomerCsrfToken } from "../../../api/customerClient";

export type CustomerActor = components["schemas"]["CustomerActor"];

/**
 * `/me` answers four distinct things and the app must not collapse them into a
 * single "logged out" screen: 401 means sign in, 403 means the browser holds a
 * different actor, and 503 means the Session dependency itself is down.
 */
export type CustomerSessionState =
  | { status: "loading" }
  | { status: "authenticated"; actor: CustomerActor }
  | { status: "unauthenticated" }
  | { status: "forbidden"; error: ApiRequestError }
  | { status: "unavailable"; error: unknown };

const listeners = new Set<() => void>();
let state: CustomerSessionState = { status: "loading" };
let inFlight: Promise<CustomerSessionState> | null = null;

function publish(next: CustomerSessionState) {
  state = next;
  listeners.forEach((listener) => listener());
}

function classify(failure: unknown): CustomerSessionState {
  if (failure instanceof ApiRequestError) {
    if (failure.status === 401) return { status: "unauthenticated" };
    if (failure.status === 403) return { status: "forbidden", error: failure };
  }
  return { status: "unavailable", error: failure };
}

/**
 * Removes only customer-owned browser state. Operator OIDC state and any
 * merchant Session cookie belong to other actors and stay untouched.
 */
export function clearCustomerBrowserState() {
  const localPrefixes = ["beanflow.customer."];
  const sessionPrefixes = ["beanflow.customer.", "beanflow.idempotency.", "beanflow.payment-attempt."];
  for (const [storage, prefixes] of [
    [localStorage, localPrefixes],
    [sessionStorage, sessionPrefixes],
  ] as const) {
    const removable = Array.from({ length: storage.length }, (_, index) => storage.key(index))
      .filter((key): key is string => key !== null && prefixes.some((prefix) => key.startsWith(prefix)));
    removable.forEach((key) => storage.removeItem(key));
  }
  forgetCustomerCsrfToken();
}

export const customerSession = {
  get: (): CustomerSessionState => state,

  subscribe: (listener: () => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },

  /** Resolves the actor from the server. Concurrent callers share one request. */
  async refresh(): Promise<CustomerSessionState> {
    if (inFlight) return inFlight;
    inFlight = (async () => {
      try {
        const actor = unwrap(await customerApi.GET("/me"));
        publish({ status: "authenticated", actor });
      } catch (failure) {
        publish(classify(failure));
      } finally {
        inFlight = null;
      }
      return state;
    })();
    return inFlight;
  },

  async register(input: { loginId: string; password: string; displayName: string }): Promise<string> {
    const result = await customerApi.POST("/auth/customer/registrations", {
      params: { header: await customerCsrfHeader() },
      body: { loginId: input.loginId, password: input.password, displayName: input.displayName },
    });
    return unwrap(result).loginId;
  },

  async logIn(input: { loginId: string; password: string }): Promise<CustomerActor> {
    const result = await customerApi.POST("/auth/customer/sessions", {
      params: { header: await customerCsrfHeader() },
      body: { loginId: input.loginId, password: input.password },
    });
    const actor = unwrap(result);
    publish({ status: "authenticated", actor });
    return actor;
  },

  /**
   * Clears local-only customer state (cart, idempotency keys, CSRF token) even
   * when the server call fails, but only publishes "unauthenticated" once the
   * server has confirmed the Session cookie is gone (204, or 401 meaning there
   * was nothing to delete). Customer auth is an HttpOnly Session Cookie the
   * browser cannot clear itself: a network error, a 503, or a rejected CSRF
   * token (403) means the server-side session may still be live, so the caller
   * must see the failure and be able to retry rather than have the screen show
   * "logged out" while the cookie is still valid.
   */
  async logOut(): Promise<void> {
    try {
      const result = await customerApi.DELETE("/auth/customer/sessions/current", {
        params: { header: await customerCsrfHeader() },
      });
      if (!result.response.ok && result.response.status !== 401) unwrap(result);
    } finally {
      clearCustomerBrowserState();
    }
    publish({ status: "unauthenticated" });
  },

  /** Test seam only. */
  reset() {
    inFlight = null;
    publish({ status: "loading" });
  },
};

export function useCustomerSession(): CustomerSessionState {
  return useSyncExternalStore(customerSession.subscribe, customerSession.get, () => state);
}

/**
 * Only a same-origin path inside the customer app survives. A protocol-relative
 * or absolute URL from the query string is discarded rather than sanitized.
 */
export function sanitizeReturnPath(value: string | null): string {
  if (!value || !value.startsWith("/app") || value.startsWith("//")) return "/app";
  return value;
}
