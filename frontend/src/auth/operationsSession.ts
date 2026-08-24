import Keycloak, { type KeycloakConfig, type KeycloakInitOptions } from "keycloak-js";
import { useSyncExternalStore } from "react";
import type { components } from "../api/schema";
import { ApiRequestError } from "../api/client";

export type OperationsOidcConfiguration = components["schemas"]["OperationsOidcConfiguration"];

export type OperationsAuthState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "unauthenticated" }
  | { status: "authenticated"; expiresAt: number | null }
  | { status: "unavailable"; error: unknown };

type KeycloakAdapter = {
  authenticated?: boolean;
  token?: string;
  tokenParsed?: { exp?: number };
  onTokenExpired?: () => void;
  init(options: KeycloakInitOptions): Promise<boolean>;
  login(options?: { redirectUri?: string; scope?: string }): Promise<void>;
  logout(options?: { redirectUri?: string }): Promise<void>;
  clearToken(): void;
};

type OperationsAuthDependencies = {
  loadConfiguration: () => Promise<OperationsOidcConfiguration>;
  createKeycloak: (configuration: KeycloakConfig) => KeycloakAdapter;
};

const RETURN_PATH_KEY = "beanflow.operations.oidc.returnPath";

async function loadConfiguration(): Promise<OperationsOidcConfiguration> {
  const response = await globalThis.fetch(`${window.location.origin}/api/v1/auth/operations/config`, {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    let message = "운영자 로그인 설정을 불러오지 못했습니다.";
    let correlationId: string | undefined;
    try {
      const body = await response.json() as { message?: string; correlationId?: string };
      message = body.message ?? message;
      correlationId = body.correlationId;
    } catch {
      // Non-JSON failure stays explicit; there is no local configuration fallback.
    }
    throw new ApiRequestError(response.status, "OPERATIONS_OIDC_CONFIG_UNAVAILABLE", message, correlationId);
  }
  return response.json() as Promise<OperationsOidcConfiguration>;
}

function validateConfiguration(configuration: OperationsOidcConfiguration) {
  const authorizationServer = new URL(configuration.authorizationServerUrl);
  const issuer = new URL(configuration.issuerUri);
  const redirect = new URL(configuration.redirectUri);
  const postLogout = new URL(configuration.postLogoutRedirectUri);
  const expectedIssuer = `${authorizationServer.toString().replace(/\/$/, "")}/realms/${configuration.realm}`;
  if (issuer.toString().replace(/\/$/, "") !== expectedIssuer) {
    throw new Error("운영자 로그인 issuer 설정이 authorization server realm과 일치하지 않습니다.");
  }
  if (redirect.origin !== window.location.origin || postLogout.origin !== window.location.origin) {
    throw new Error("운영자 로그인 callback과 logout URI는 현재 origin과 정확히 일치해야 합니다.");
  }
  if (redirect.pathname !== "/ops/auth/callback" || redirect.search || redirect.hash) {
    throw new Error("운영자 로그인 callback URI가 허용된 경로와 일치하지 않습니다.");
  }
  if (!configuration.scopes.includes("openid") || configuration.scopes.includes("offline_access")) {
    throw new Error("운영자 로그인 scope 설정이 허용된 정책과 일치하지 않습니다.");
  }
}

function safeReturnPath(): string {
  const candidate = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  return candidate.startsWith("/ops") && !candidate.startsWith("/ops/auth/callback") && !candidate.startsWith("//")
    ? candidate
    : "/ops";
}

const tokenListeners = new Set<() => void>();
let memoryToken = "";

function emitToken() {
  tokenListeners.forEach((listener) => listener());
}

/** Token boundary for the Operations API client. There is intentionally no browser-storage implementation. */
export const authToken = {
  get: () => memoryToken,
  set(value: string) {
    memoryToken = value.trim().replace(/^Bearer\s+/i, "");
    emitToken();
  },
  clear() {
    memoryToken = "";
    emitToken();
  },
  subscribe(listener: () => void) {
    tokenListeners.add(listener);
    return () => tokenListeners.delete(listener);
  },
};

export function createOperationsAuthSession(overrides: Partial<OperationsAuthDependencies> = {}) {
  const dependencies: OperationsAuthDependencies = {
    loadConfiguration,
    createKeycloak: (configuration) => new Keycloak(configuration),
    ...overrides,
  };
  const listeners = new Set<() => void>();
  let state: OperationsAuthState = { status: "idle" };
  let configuration: OperationsOidcConfiguration | null = null;
  let keycloak: KeycloakAdapter | null = null;
  let initializePromise: Promise<OperationsAuthState> | null = null;
  let expiryTimer: number | null = null;

  function publish(next: OperationsAuthState) {
    state = next;
    listeners.forEach((listener) => listener());
  }

  function clearExpiryTimer() {
    if (expiryTimer !== null) window.clearTimeout(expiryTimer);
    expiryTimer = null;
  }

  function clear() {
    clearExpiryTimer();
    keycloak?.clearToken();
    authToken.clear();
    publish({ status: "unauthenticated" });
  }

  function acceptToken(adapter: KeycloakAdapter) {
    if (!adapter.authenticated || !adapter.token) {
      clear();
      return;
    }
    authToken.set(adapter.token);
    const expiresAt = adapter.tokenParsed?.exp ?? null;
    adapter.onTokenExpired = clear;
    clearExpiryTimer();
    if (expiresAt !== null) {
      const remainingMs = Math.max(0, expiresAt * 1000 - Date.now());
      expiryTimer = window.setTimeout(clear, remainingMs);
    }
    publish({ status: "authenticated", expiresAt });
  }

  async function initialize(): Promise<OperationsAuthState> {
    if (initializePromise) return initializePromise;
    if (state.status === "authenticated" || state.status === "unauthenticated") return state;
    publish({ status: "loading" });
    initializePromise = (async () => {
      try {
        configuration = await dependencies.loadConfiguration();
        validateConfiguration(configuration);
        keycloak = dependencies.createKeycloak({
          url: configuration.authorizationServerUrl,
          realm: configuration.realm,
          clientId: configuration.clientId,
        });
        await keycloak.init({
          onLoad: "check-sso",
          flow: "standard",
          pkceMethod: "S256",
          checkLoginIframe: false,
          redirectUri: configuration.redirectUri,
          scope: configuration.scopes.join(" "),
        });
        acceptToken(keycloak);
      } catch (error) {
        authToken.clear();
        publish({ status: "unavailable", error });
      } finally {
        initializePromise = null;
      }
      return state;
    })();
    return initializePromise;
  }

  return {
    get: () => state,
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    initialize,
    async retry() {
      clearExpiryTimer();
      keycloak = null;
      configuration = null;
      initializePromise = null;
      authToken.clear();
      publish({ status: "idle" });
      return initialize();
    },
    async logIn() {
      if (state.status === "idle" || state.status === "unavailable") await this.retry();
      if (!keycloak || !configuration) throw new Error("운영자 로그인 설정을 사용할 수 없습니다.");
      sessionStorage.setItem(RETURN_PATH_KEY, safeReturnPath());
      await keycloak.login({ redirectUri: configuration.redirectUri, scope: configuration.scopes.join(" ") });
    },
    async logOut() {
      if (!keycloak || !configuration) {
        clear();
        return;
      }
      const redirectUri = configuration.postLogoutRedirectUri;
      clear();
      sessionStorage.removeItem(RETURN_PATH_KEY);
      await keycloak.logout({ redirectUri });
    },
    clear,
    consumeReturnPath() {
      const candidate = sessionStorage.getItem(RETURN_PATH_KEY);
      sessionStorage.removeItem(RETURN_PATH_KEY);
      return candidate?.startsWith("/ops") && !candidate.startsWith("//") ? candidate : "/ops";
    },
  };
}

export const operationsAuth = createOperationsAuthSession();

export function useOperationsAuth() {
  return useSyncExternalStore(operationsAuth.subscribe, operationsAuth.get, operationsAuth.get);
}

export function useAuthToken() {
  return useSyncExternalStore(authToken.subscribe, authToken.get, () => "");
}
