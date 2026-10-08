import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { authToken, createOperationsAuthSession } from "./session";

const configuration = {
  issuerUri: "https://id.beanflow.example/realms/operations",
  authorizationServerUrl: "https://id.beanflow.example",
  realm: "operations",
  clientId: "beanflow-operations-web",
  redirectUri: `${window.location.origin}/ops/auth/callback`,
  postLogoutRedirectUri: `${window.location.origin}/ops`,
  scopes: ["openid", "profile"],
};

function adapter(authenticated = true) {
  return {
    authenticated,
    token: authenticated ? "operator-access-token" : undefined,
    tokenParsed: authenticated ? { exp: Math.floor(Date.now() / 1000) + 300 } : undefined,
    onTokenExpired: undefined as (() => void) | undefined,
    init: vi.fn().mockResolvedValue(authenticated),
    login: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined),
    clearToken: vi.fn(),
    updateToken: vi.fn().mockResolvedValue(false),
  };
}

beforeEach(() => vi.useFakeTimers());

afterEach(() => {
  authToken.clear();
  localStorage.clear();
  sessionStorage.clear();
  vi.restoreAllMocks();
  vi.clearAllTimers();
  vi.useRealTimers();
});

describe("operations OIDC session", () => {
  it("initializes the official adapter with standard flow and PKCE S256", async () => {
    const keycloak = adapter();
    const createKeycloak = vi.fn(() => keycloak);
    const session = createOperationsAuthSession({
      loadConfiguration: vi.fn().mockResolvedValue(configuration),
      createKeycloak,
    });

    await session.initialize();

    expect(createKeycloak).toHaveBeenCalledWith({
      url: configuration.authorizationServerUrl,
      realm: configuration.realm,
      clientId: configuration.clientId,
    });
    expect(keycloak.init).toHaveBeenCalledWith(expect.objectContaining({
      flow: "standard",
      pkceMethod: "S256",
      checkLoginIframe: false,
      redirectUri: configuration.redirectUri,
      scope: "openid profile",
    }));
    expect(session.get()).toMatchObject({ status: "authenticated" });
    expect(authToken.get()).toBe("operator-access-token");
  });

  it("shows the current account and removes its label when the session clears", async () => {
    const keycloak = { ...adapter(), tokenParsed: { exp: Math.floor(Date.now() / 1000) + 300, preferred_username: "operator@example.test" } };
    const session = createOperationsAuthSession({ loadConfiguration: vi.fn().mockResolvedValue(configuration), createKeycloak: () => keycloak });
    await session.initialize();
    expect(session.get()).toMatchObject({ status: "authenticated", displayName: "operator@example.test" });
    session.clear();
    expect(session.get()).toEqual({ status: "unauthenticated" });
  });

  it("keeps the access token only in memory", async () => {
    const keycloak = adapter();
    const session = createOperationsAuthSession({
      loadConfiguration: vi.fn().mockResolvedValue(configuration),
      createKeycloak: () => keycloak,
    });

    await session.initialize();

    expect(Object.values(localStorage)).not.toContain("operator-access-token");
    expect(Object.values(sessionStorage)).not.toContain("operator-access-token");
    session.clear();
    expect(authToken.get()).toBe("");
    expect(keycloak.clearToken).toHaveBeenCalled();
  });

  it("fails closed when the callback is not same-origin", async () => {
    const createKeycloak = vi.fn(() => adapter());
    const session = createOperationsAuthSession({
      loadConfiguration: vi.fn().mockResolvedValue({
        ...configuration,
        redirectUri: "https://attacker.example/ops/auth/callback",
      }),
      createKeycloak,
    });

    await session.initialize();

    expect(session.get()).toMatchObject({ status: "unavailable" });
    expect(createKeycloak).not.toHaveBeenCalled();
    expect(authToken.get()).toBe("");
  });

  it("clears memory before redirecting to the validated logout endpoint", async () => {
    const keycloak = adapter();
    const session = createOperationsAuthSession({
      loadConfiguration: vi.fn().mockResolvedValue(configuration),
      createKeycloak: () => keycloak,
    });
    await session.initialize();

    await session.logOut();

    expect(authToken.get()).toBe("");
    expect(keycloak.logout).toHaveBeenCalledWith({ redirectUri: configuration.postLogoutRedirectUri });
  });

  it("refreshes at expiry and keeps the operator signed in with the new token", async () => {
    const keycloak = adapter();
    keycloak.updateToken.mockImplementation(async () => {
      keycloak.token = "renewed-access-token";
      keycloak.tokenParsed = { exp: Math.floor(Date.now() / 1000) + 300 };
      return true;
    });
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    await vi.advanceTimersByTimeAsync(300_000);
    expect(keycloak.updateToken).toHaveBeenCalledOnce();
    expect(keycloak.updateToken).toHaveBeenCalledWith(-1);
    expect(session.get()).toMatchObject({ status: "authenticated" });
    expect(authToken.get()).toBe("renewed-access-token");
    expect(keycloak.clearToken).not.toHaveBeenCalled();
    expect(Object.values(localStorage)).not.toContain("renewed-access-token");
    expect(Object.values(sessionStorage)).not.toContain("renewed-access-token");
    session.clear();
  });

  it("checks 30 seconds of validity before requests and accepts a refreshed token", async () => {
    const keycloak = adapter();
    keycloak.updateToken.mockImplementation(async () => {
      keycloak.token = "request-access-token";
      keycloak.tokenParsed = { exp: Math.floor(Date.now() / 1000) + 600 };
      return true;
    });
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    await session.refreshAccessToken();
    expect(keycloak.updateToken).toHaveBeenCalledWith(30);
    expect(authToken.get()).toBe("request-access-token");
    expect(session.get()).toMatchObject({ expiresAt: keycloak.tokenParsed?.exp });
    session.clear();
  });

  it("shares a pending refresh between API requests and the expiry callback", async () => {
    let finish!: (value: boolean) => void;
    const keycloak = adapter();
    keycloak.updateToken.mockImplementation(() => new Promise<boolean>((resolve) => { finish = resolve; }));
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    const first = session.refreshAccessToken();
    const second = session.refreshAccessToken();
    keycloak.onTokenExpired?.();
    await Promise.resolve();
    expect(keycloak.updateToken).toHaveBeenCalledOnce();
    expect(first).toBe(second);
    finish(false);
    await Promise.all([first, second]);
    expect(session.get()).toMatchObject({ status: "authenticated" });
    session.clear();
  });

  it("uses adapter clock skew instead of exp alone for the expiry timer", async () => {
    const keycloak = { ...adapter(), timeSkew: 120 };
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    expect(session.get()).toMatchObject({ expiresAt: keycloak.tokenParsed!.exp + 120 });
    await vi.advanceTimersByTimeAsync(300_000);
    expect(keycloak.updateToken).not.toHaveBeenCalled();
    keycloak.updateToken.mockImplementation(async () => {
      keycloak.tokenParsed = { exp: Math.floor(Date.now() / 1000) + 300 };
      return true;
    });
    await vi.advanceTimersByTimeAsync(120_000);
    expect(keycloak.updateToken).toHaveBeenCalledOnce();
    expect(session.get()).toMatchObject({ status: "authenticated" });
    session.clear();
  });

  it("requires reauthentication when Keycloak rejects an ended SSO session", async () => {
    const keycloak = adapter();
    keycloak.updateToken.mockImplementation(async () => {
      keycloak.authenticated = false;
      throw new Error("invalid_grant");
    });
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    await expect(session.refreshAccessToken()).rejects.toMatchObject({ status: 401 });
    expect(session.get()).toEqual({ status: "unauthenticated" });
    expect(authToken.get()).toBe("");
    expect(keycloak.clearToken).toHaveBeenCalled();
  });

  it("reports a refresh dependency failure instead of pretending the SSO session ended", async () => {
    const keycloak = adapter();
    keycloak.updateToken.mockRejectedValue(new TypeError("Network unavailable"));
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    await expect(session.refreshAccessToken()).rejects.toMatchObject({ status: 503, code: "OPERATIONS_TOKEN_REFRESH_UNAVAILABLE" });
    expect(session.get()).toMatchObject({ status: "unavailable" });
    expect(authToken.get()).toBe("");
    await vi.advanceTimersByTimeAsync(600_000);
    expect(keycloak.updateToken).toHaveBeenCalledOnce();
    await expect(session.refreshAccessToken()).rejects.toMatchObject({ status: 503 });
  });

  it("publishes background expiry failure without retrying forever", async () => {
    const keycloak = adapter();
    keycloak.updateToken.mockRejectedValue(new TypeError("Network unavailable"));
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    await vi.advanceTimersByTimeAsync(300_000);
    expect(session.get()).toMatchObject({ status: "unavailable" });
    expect(authToken.get()).toBe("");
    await vi.advanceTimersByTimeAsync(300_000);
    expect(keycloak.updateToken).toHaveBeenCalledOnce();
  });

  it("does not accept a still-expired token after the adapter reports success", async () => {
    const keycloak = adapter();
    keycloak.updateToken.mockImplementation(async () => {
      keycloak.tokenParsed = { exp: Math.floor(Date.now() / 1000) };
      return true;
    });
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    await expect(session.refreshAccessToken()).rejects.toMatchObject({ status: 401 });
    expect(session.get()).toEqual({ status: "unauthenticated" });
    expect(authToken.get()).toBe("");
  });

  it.each(["clear", "logOut"] as const)("discards a refresh that finishes after %s", async (action) => {
    let finish!: (value: boolean) => void;
    const keycloak = adapter();
    keycloak.updateToken.mockImplementation(() => new Promise<boolean>((resolve) => { finish = resolve; }));
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    const refresh = session.refreshAccessToken();
    await Promise.resolve();
    await session[action]();
    keycloak.token = "late-token";
    finish(true);
    await expect(refresh).rejects.toMatchObject({ status: 401 });
    expect(session.get()).toEqual({ status: "unauthenticated" });
    expect(authToken.get()).toBe("");
    expect(keycloak.clearToken).toHaveBeenCalledTimes(2);
  });

  it.each(["success", "failure"] as const)("preserves a retried session after a late refresh %s", async (outcome) => {
    let finish!: (value: boolean) => void;
    let fail!: (reason: Error) => void;
    const previous = adapter();
    previous.updateToken.mockImplementation(() => new Promise<boolean>((resolve, reject) => { finish = resolve; fail = reject; }));
    const current = { ...adapter(), token: "new-login-token" };
    const createKeycloak = vi.fn().mockReturnValueOnce(previous).mockReturnValueOnce(current);
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak });
    await session.initialize();
    const refresh = session.refreshAccessToken();
    await Promise.resolve();
    await session.retry();
    if (outcome === "success") finish(true);
    else fail(new Error("late failure"));
    await expect(refresh).rejects.toBeDefined();
    expect(session.get()).toMatchObject({ status: "authenticated" });
    expect(authToken.get()).toBe("new-login-token");
    expect(current.clearToken).not.toHaveBeenCalled();
    session.clear();
  });

  it("ignores 401 for an older token while rejecting the current credential", async () => {
    const keycloak = adapter();
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    session.rejectToken("previous-access-token");
    expect(session.get()).toMatchObject({ status: "authenticated" });
    session.rejectToken("operator-access-token");
    expect(session.get()).toEqual({ status: "unauthenticated" });
    expect(authToken.get()).toBe("");
  });
});
