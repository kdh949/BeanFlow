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
