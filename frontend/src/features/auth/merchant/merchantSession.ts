import { useSyncExternalStore } from "react";
import type { components } from "../../../api/schema";
import { ApiRequestError, unwrap } from "../../../api/client";
import { forgetMerchantCsrfToken, merchantApi, merchantCsrfHeader } from "../../../api/merchantClient";

export type MerchantActor = components["schemas"]["MerchantActor"];
export type MerchantStore = components["schemas"]["MerchantStore"];

/**
 * `/merchant/me` answers five distinct things and the console must not collapse
 * them into one "logged out" screen: 401 means sign in, 403 means the browser
 * holds a different actor, 503 means the Session dependency is down, and an
 * `INITIAL_PASSWORD` account is authenticated but may not use store screens yet.
 */
export type MerchantSessionState =
  | { status: "loading" }
  | { status: "authenticated"; actor: MerchantActor }
  | { status: "initialPassword"; actor: MerchantActor }
  | { status: "unauthenticated" }
  | { status: "forbidden"; error: ApiRequestError }
  | { status: "unavailable"; error: unknown };

const listeners = new Set<() => void>();
let state: MerchantSessionState = { status: "loading" };
let inFlight: Promise<MerchantSessionState> | null = null;

function publish(next: MerchantSessionState) {
  state = next;
  listeners.forEach((listener) => listener());
}

function resolved(actor: MerchantActor): MerchantSessionState {
  return actor.accountState === "INITIAL_PASSWORD"
    ? { status: "initialPassword", actor }
    : { status: "authenticated", actor };
}

function classify(failure: unknown): MerchantSessionState {
  if (failure instanceof ApiRequestError) {
    if (failure.status === 401) return { status: "unauthenticated" };
    // The initial-password gate answers 403 on every store path, so an
    // authenticated first-login actor must not be shown as a wrong actor.
    if (failure.status === 403 && failure.code === "INITIAL_PASSWORD_CHANGE_REQUIRED") {
      return { status: "unauthenticated" };
    }
    if (failure.status === 403) return { status: "forbidden", error: failure };
  }
  return { status: "unavailable", error: failure };
}

/**
 * Removes only merchant-owned browser state. Customer Session cookies and
 * operator OIDC state belong to other actors and stay untouched.
 */
export function clearMerchantBrowserState() {
  const prefixes = ["beanflow.merchant.", "beanflow.idempotency."];
  const removable = Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
    .filter((key): key is string => key !== null && prefixes.some((prefix) => key.startsWith(prefix)));
  removable.forEach((key) => sessionStorage.removeItem(key));
  forgetMerchantCsrfToken();
}

export const merchantSession = {
  get: (): MerchantSessionState => state,

  subscribe: (listener: () => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },

  /** Resolves the actor from the server. Concurrent callers share one request. */
  async refresh(): Promise<MerchantSessionState> {
    if (inFlight) return inFlight;
    inFlight = (async () => {
      try {
        publish(resolved(unwrap(await merchantApi.GET("/merchant/me"))));
      } catch (failure) {
        publish(classify(failure));
      } finally {
        inFlight = null;
      }
      return state;
    })();
    return inFlight;
  },

  async logIn(input: { loginId: string; password: string }): Promise<MerchantActor> {
    const result = await merchantApi.POST("/auth/merchant/sessions", {
      params: { header: await merchantCsrfHeader() },
      body: { loginId: input.loginId, password: input.password },
    });
    const actor = unwrap(result);
    publish(resolved(actor));
    return actor;
  },

  /**
   * Changes the initial password. The server rotates the Session, so the actor
   * is re-read rather than assumed to be `ACTIVE`.
   */
  async changePassword(input: { currentPassword: string; newPassword: string }): Promise<MerchantSessionState> {
    const result = await merchantApi.POST("/auth/merchant/password-changes", {
      params: { header: await merchantCsrfHeader() },
      body: { currentPassword: input.currentPassword, newPassword: input.newPassword },
    });
    if (!result.response.ok) unwrap(result);
    forgetMerchantCsrfToken();
    publish({ status: "loading" });
    return this.refresh();
  },

  /**
   * Clears merchant credential state even when the server call fails: a console
   * that already showed "logged out" must never keep an unresolved submit intent
   * from the previous operator.
   */
  async logOut(): Promise<void> {
    try {
      const result = await merchantApi.DELETE("/auth/merchant/sessions/current", {
        params: { header: await merchantCsrfHeader() },
      });
      if (!result.response.ok) unwrap(result);
    } finally {
      clearMerchantBrowserState();
      publish({ status: "unauthenticated" });
    }
  },

  /** Test seam only. */
  reset() {
    inFlight = null;
    publish({ status: "loading" });
  },
};

export function useMerchantSession(): MerchantSessionState {
  return useSyncExternalStore(merchantSession.subscribe, merchantSession.get, () => state);
}

export async function requestMerchantStores(): Promise<MerchantStore[]> {
  return unwrap(await merchantApi.GET("/merchant/me/stores"));
}

/**
 * Only a same-origin path inside the store console survives. A protocol-relative
 * or absolute URL from the query string is discarded rather than sanitized.
 */
export function sanitizeStoreReturnPath(value: string | null): string {
  if (!value || !value.startsWith("/store") || value.startsWith("//")) return "/store";
  return value;
}
