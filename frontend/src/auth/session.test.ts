import { afterEach, describe, expect, it, vi } from "vitest";
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
  };
}

afterEach(() => {
  authToken.clear();
  localStorage.clear();
  sessionStorage.clear();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
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
});


const returnPathKey = "beanflow.operations.oidc.returnPath";

describe("organization login return paths", () => {
  it.each(["/support", "/support/inquiries?status=OPEN#latest", "/ops/orders?query=example#result"])("preserves %s before automatic and manual login", async (path) => {
    window.history.replaceState(null, "", path);
    const keycloak = adapter(false);
    keycloak.init.mockImplementation(async () => {
      expect(sessionStorage.getItem(returnPathKey)).toBe(path);
      return false;
    });
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    await session.logIn();
    expect(sessionStorage.getItem(returnPathKey)).toBe(path);
    expect(keycloak.login).toHaveBeenCalledWith({ redirectUri: configuration.redirectUri, scope: "openid profile" });
  });

  it("consumes callback storage immediately and keeps the destination stable for repeated renders", async () => {
    sessionStorage.setItem(returnPathKey, "/support/inquiries?status=OPEN#latest");
    window.history.replaceState(null, "", "/ops/auth/callback?code=synthetic&state=synthetic");
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => adapter() });
    await session.initialize();
    expect(sessionStorage.getItem(returnPathKey)).toBeNull();
    expect(session.consumeReturnPath()).toBe("/support/inquiries?status=OPEN#latest");
    expect(session.consumeReturnPath()).toBe("/support/inquiries?status=OPEN#latest");
    session.clear();
  });

  it("removes return storage when callback verification fails", async () => {
    sessionStorage.setItem(returnPathKey, "/support/cases");
    window.history.replaceState(null, "", "/ops/auth/callback?error=access_denied");
    const keycloak = adapter();
    keycloak.init.mockRejectedValue(new Error("callback rejected"));
    const session = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => keycloak });
    await session.initialize();
    expect(session.get()).toMatchObject({ status: "unavailable" });
    expect(sessionStorage.getItem(returnPathKey)).toBeNull();
    expect(authToken.get()).toBe("");
  });

  it.each(["https://attacker.example/support", "//attacker.example/ops", "/ops-other", "/support-other", "/ops/auth/callback?code=secret", "/ops/%61uth/callback", "/support/../app", "/support/%5cevil", "/support/%ZZ"])("rejects unsafe stored path %s", (path) => {
    sessionStorage.setItem(returnPathKey, path);
    const session = createOperationsAuthSession();
    expect(session.consumeReturnPath()).toBe("/ops");
    expect(sessionStorage.getItem(returnPathKey)).toBeNull();
  });
});


it("preserves the destination through an unauthenticated check-sso callback and a fresh manual-login callback", async () => {
  sessionStorage.setItem(returnPathKey, "/support/inquiries?status=OPEN#latest");
  window.history.replaceState(null, "", "/ops/auth/callback");
  const first = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => adapter(false) });
  await first.initialize();
  expect(sessionStorage.getItem(returnPathKey)).toBeNull();
  await first.logIn();
  expect(sessionStorage.getItem(returnPathKey)).toBe("/support/inquiries?status=OPEN#latest");
  const second = createOperationsAuthSession({ loadConfiguration: async () => configuration, createKeycloak: () => adapter() });
  await second.initialize();
  expect(second.consumeReturnPath()).toBe("/support/inquiries?status=OPEN#latest");
  expect(sessionStorage.getItem(returnPathKey)).toBeNull();
  second.clear();
});
