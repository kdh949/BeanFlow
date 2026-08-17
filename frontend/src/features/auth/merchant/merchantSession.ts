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
/**
 * Bumped by every login/logout so a `refresh()` that was already in flight
 * cannot publish a stale answer over a newer, explicit session change.
 */
let sessionGeneration = 0;

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
 * Removes only merchant-owned browser state. Customer Session cookies, the
 * customer payment flow's `beanflow.idempotency.*` submit keys, and operator
 * OIDC state belong to other actors and stay untouched: the merchant console
 * never writes that prefix itself, so clearing it here could drop another
 * actor's unresolved submit intent in the same browser tab.
 */
export function clearMerchantBrowserState() {
  const prefix = "beanflow.merchant.";
  const removable = Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
    .filter((key): key is string => key !== null && key.startsWith(prefix));
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

  /**
   * Resolves the actor from the server. Concurrent callers share one request.
   * A response that arrives after a login, logout or password change moved the
   * session to a newer generation is discarded instead of publishing a stale
   * answer over it.
   */
  async refresh(): Promise<MerchantSessionState> {
    if (inFlight) return inFlight;
    const generation = sessionGeneration;
    inFlight = (async () => {
      try {
        const next = resolved(unwrap(await merchantApi.GET("/merchant/me")));
        if (generation === sessionGeneration) publish(next);
      } catch (failure) {
        if (generation === sessionGeneration) publish(classify(failure));
      } finally {
        inFlight = null;
      }
      return state;
    })();
    return inFlight;
  },

  async logIn(input: { loginId: string; password: string }): Promise<MerchantActor> {
    sessionGeneration += 1;
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
    sessionGeneration += 1;
    forgetMerchantCsrfToken();
    publish({ status: "loading" });
    return this.refresh();
  },

  /**
   * Clears merchant credential state only once the server confirms the Session
   * cookie is gone. The cookie is HttpOnly, so the browser cannot clear it
   * itself: publishing "unauthenticated" ahead of that confirmation would show
   * a logged-out console while the server session, and the actor's access to
   * it, is still live.
   */
  async logOut(): Promise<void> {
    sessionGeneration += 1;
    const result = await merchantApi.DELETE("/auth/merchant/sessions/current", {
      params: { header: await merchantCsrfHeader() },
    });
    if (!result.response.ok) unwrap(result);
    clearMerchantBrowserState();
    publish({ status: "unauthenticated" });
  },

  /** Test seam only. */
  reset() {
    inFlight = null;
    sessionGeneration += 1;
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
