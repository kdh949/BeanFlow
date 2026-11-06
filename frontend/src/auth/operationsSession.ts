import Keycloak, { type KeycloakConfig, type KeycloakInitOptions } from "keycloak-js";
import { useSyncExternalStore } from "react";
import type { components } from "../api/schema";
import { ApiRequestError } from "../api/client";

export type OperationsOidcConfiguration = components["schemas"]["OperationsOidcConfiguration"];

export type OperationsAuthState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "unauthenticated" }
  | { status: "authenticated"; expiresAt: number | null; displayName?: string }
  | { status: "unavailable"; error: unknown };

type KeycloakAdapter = {
  authenticated?: boolean;
  token?: string;
  tokenParsed?: { exp?: number; preferred_username?: string; name?: string };
  timeSkew?: number | null;
  onTokenExpired?: () => void;
  init(options: KeycloakInitOptions): Promise<boolean>;
  login(options?: { redirectUri?: string; scope?: string }): Promise<void>;
  logout(options?: { redirectUri?: string }): Promise<void>;
  clearToken(): void;
  updateToken(minValidity: number): Promise<boolean>;
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
  let generation = 0;
  let refreshPromise: Promise<void> | null = null;

  function publish(next: OperationsAuthState) {
    state = next;
    listeners.forEach((listener) => listener());
  }

  function clearExpiryTimer() {
    if (expiryTimer !== null) window.clearTimeout(expiryTimer);
    expiryTimer = null;
  }

  function clear() {
    generation += 1;
    refreshPromise = null;
    clearExpiryTimer();
    if (keycloak) keycloak.onTokenExpired = undefined;
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
    const expiresAt = adapter.tokenParsed?.exp === undefined
      ? null
      : adapter.tokenParsed.exp + (adapter.timeSkew ?? 0);
    const tokenGeneration = generation;
    adapter.onTokenExpired = () => void refreshAfterExpiry(adapter, tokenGeneration);
    clearExpiryTimer();
    if (expiresAt !== null) {
      const remainingMs = Math.max(0, expiresAt * 1000 - Date.now());
      expiryTimer = window.setTimeout(() => void refreshAfterExpiry(adapter, tokenGeneration), remainingMs);
    }
    const displayName = adapter.tokenParsed?.preferred_username ?? adapter.tokenParsed?.name;
    publish({ status: "authenticated", expiresAt, ...(displayName ? { displayName } : {}) });
  }

  async function refreshAfterExpiry(adapter: KeycloakAdapter, tokenGeneration: number) {
    if (adapter !== keycloak || tokenGeneration !== generation || state.status !== "authenticated") return;
    if (adapter.tokenParsed?.exp !== undefined
      && (adapter.tokenParsed.exp + (adapter.timeSkew ?? 0)) * 1000 > Date.now()) return;
    try {
      await refreshAccessToken(-1);
    } catch {
      // refreshAccessToken already removed credentials and published the explicit failure state.
    }
  }

  function refreshAccessToken(minValidity = 30): Promise<void> {
    if (refreshPromise) return refreshPromise;
    if (state.status === "unavailable") return Promise.reject(state.error);
    if (!keycloak || state.status !== "authenticated") return Promise.resolve();
    const adapter = keycloak;
    const refreshGeneration = generation;
    const pending = Promise.resolve().then(async () => {
      try {
        await adapter.updateToken(minValidity);
        if (adapter !== keycloak || refreshGeneration !== generation) {
          adapter.clearToken();
          throw new ApiRequestError(401, "UNAUTHORIZED", "로그인 상태가 변경되었습니다. 다시 로그인해 주세요.");
        }
        if (!adapter.authenticated || !adapter.token || (adapter.tokenParsed?.exp !== undefined
          && (adapter.tokenParsed.exp + (adapter.timeSkew ?? 0)) * 1000 <= Date.now())) {
          throw new ApiRequestError(401, "UNAUTHORIZED", "로그인이 만료되었습니다. 다시 로그인해 주세요.");
        }
        acceptToken(adapter);
      } catch (failure) {
        if (adapter !== keycloak || refreshGeneration !== generation) throw failure;
        const signedOut = !adapter.authenticated || (failure instanceof ApiRequestError && failure.status === 401);
        const error = signedOut
          ? new ApiRequestError(401, "UNAUTHORIZED", "로그인이 만료되었습니다. 다시 로그인해 주세요.")
          : new ApiRequestError(503, "OPERATIONS_TOKEN_REFRESH_UNAVAILABLE", "조직 로그인 연결을 확인할 수 없습니다.");
        clear();
        if (!signedOut) publish({ status: "unavailable", error });
        throw error;
      } finally {
        if (refreshPromise === pending) refreshPromise = null;
      }
    });
    refreshPromise = pending;
    return pending;
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
      clear();
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
    refreshAccessToken,
    rejectToken(token: string | null) {
      if (token && token === authToken.get()) clear();
    },
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
