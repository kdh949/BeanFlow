import { StrictMode } from "react";
import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { operationsApi } from "../../../api/consoleClient";
import { authToken, createOperationsAuthSession } from "../../../auth/session";
import { OperationsSessionGate } from "./OperationsSessionGate";

afterEach(() => { cleanup(); vi.restoreAllMocks(); authToken.clear(); sessionStorage.clear(); window.history.replaceState(null, "", "/"); });

it("returns the verified callback to the support shell under StrictMode", async () => {
  window.history.replaceState(null, "", "/ops/auth/callback?code=synthetic");
  sessionStorage.setItem("beanflow.operations.oidc.returnPath", "/support/inquiries?status=OPEN#latest");
  const session = createOperationsAuthSession({
    loadConfiguration: async () => ({ issuerUri: "https://id.example/realms/work", authorizationServerUrl: "https://id.example", realm: "work", clientId: "work", redirectUri: `${window.location.origin}/ops/auth/callback`, postLogoutRedirectUri: `${window.location.origin}/ops`, scopes: ["openid"] }),
    createKeycloak: () => ({ authenticated: true, token: "synthetic", init: async () => true, login: async () => {}, logout: async () => {}, clearToken: () => {} }),
  });
  vi.spyOn(operationsApi, "GET").mockResolvedValue({ data: { actorType: "OPERATOR", operatorId: "test", roles: ["SUPPORT_AGENT"], display: { state: "AVAILABLE", loginName: "테스트 상담" } }, response: new Response(null, { status: 200 }) } as never);
  const router = createMemoryRouter([
    { path: "/ops/auth/callback", element: <OperationsSessionGate callback session={session} /> },
    { path: "/support", element: <OperationsSessionGate kind="support" session={session} />, children: [{ path: "inquiries", element: <h1>문의 접수함</h1> }] },
  ], { initialEntries: ["/ops/auth/callback"] });
  render(<StrictMode><RouterProvider router={router} /></StrictMode>);
  await screen.findByRole("heading", { name: "문의 접수함" });
  expect(router.state.location).toMatchObject({ pathname: "/support/inquiries", search: "?status=OPEN", hash: "#latest" });
  expect(screen.getByRole("navigation", { name: "고객지원 메뉴" })).toBeVisible();
  expect(screen.queryByRole("navigation", { name: "플랫폼 운영 메뉴" })).toBeNull();
  expect(sessionStorage.getItem("beanflow.operations.oidc.returnPath")).toBeNull();
  session.clear();
});
