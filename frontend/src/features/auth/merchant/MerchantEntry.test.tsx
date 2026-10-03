import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router";
import { merchantApi } from "../../../api/merchantClient";
import { MerchantLoginPage, MerchantPasswordChangePage } from "./MerchantAuthPages";
import { MerchantSessionGate } from "./MerchantSessionGate";
import { merchantSession } from "./merchantSession";

const actor = { actorType: "MERCHANT", merchantId: "merchant-test", displayName: "테스트 점주", accountState: "ACTIVE" };
const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });
beforeEach(() => {
  merchantSession.reset();
  document.cookie = "BEANFLOW_MERCHANT_XSRF=test-merchant-csrf; path=/";
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); merchantSession.reset(); });

it.each([false, true])("recovers forbidden entry through login (initial password: %s) with query/hash", async (initialPassword) => {
  const get = vi.spyOn(merchantApi, "GET").mockResolvedValue({ error: { code: "ACCESS_DENIED", message: "접근 불가" }, response: new Response(null, { status: 403 }) } as never);
  const post = vi.spyOn(merchantApi, "POST").mockResolvedValueOnce(ok({ ...actor, accountState: initialPassword ? "INITIAL_PASSWORD" : "ACTIVE" }) as never);
  const router = createMemoryRouter([
    { path: "/store/login", element: <MerchantLoginPage /> },
    { path: "/store/password", element: <MerchantPasswordChangePage /> },
    { element: <MerchantSessionGate />, children: [{ path: "/store/management", element: <h1>메뉴 업무</h1> }] },
  ], { initialEntries: ["/store/management?tab=menu#catalog"] });
  render(<RouterProvider router={router} />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("link", { name: "매장 계정으로 로그인" }));
  await user.type(await screen.findByLabelText("아이디"), "owner.test");
  await user.type(screen.getByLabelText("비밀번호"), "TestPasswordValue!");
  await user.click(screen.getByRole("button", { name: "로그인" }));
  if (initialPassword) {
    await user.type(await screen.findByLabelText("임시 비밀번호"), "TestPasswordValue!");
    await user.type(screen.getByLabelText("새 비밀번호"), "NewTestPasswordValue!");
    get.mockResolvedValue(ok(actor) as never);
    post.mockResolvedValue({ response: new Response(null, { status: 204 }) } as never);
    await user.click(screen.getByRole("button", { name: "비밀번호 변경" }));
  }
  await screen.findByRole("heading", { name: "메뉴 업무" });
  expect(router.state.location).toMatchObject({ pathname: "/store/management", search: "?tab=menu", hash: "#catalog" });
});
