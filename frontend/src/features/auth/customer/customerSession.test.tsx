import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { customerApi } from "../../../api/customerClient";
import { authToken } from "../../../auth/session";
import { CustomerSessionGate } from "./CustomerSessionGate";
import { CustomerLoginPage, CustomerSignupPage } from "./AuthPages";
import { CustomerMyPage } from "./MyPage";
import { customerSession, sanitizeReturnPath } from "./customerSession";

const actor = { actorType: "CUSTOMER" as const, customerId: "customer-id", displayName: "김도현" };

function ok<T>(data: T, status = 200) {
  return { data, response: new Response(null, { status }) };
}

function failure(status: number, code: string, message = "요청을 완료하지 못했습니다.") {
  return { error: { code, message }, response: new Response(null, { status }) };
}

function renderApp(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/app/login" element={<CustomerLoginPage />} />
        <Route path="/app/signup" element={<CustomerSignupPage />} />
        <Route element={<CustomerSessionGate />}>
          <Route path="/app/orders" element={<h1>주문 목록</h1>} />
          <Route path="/app/me" element={<CustomerMyPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
  customerSession.reset();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  document.cookie = "BEANFLOW_CUSTOMER_XSRF=; Max-Age=0; path=/";
});

describe("customer session route boundary", () => {
  it("renders the protected route for an authenticated actor", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(ok(actor) as never);

    renderApp("/app/orders");

    expect(await screen.findByRole("heading", { name: "주문 목록" })).toBeInTheDocument();
  });

  it("sends 401 to login with a sanitized same-origin return path", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(failure(401, "UNAUTHORIZED") as never);

    renderApp("/app/orders?status=PAST");

    expect(await screen.findByRole("heading", { name: "로그인" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute(
      "href",
      "/app/signup?next=%2Fapp%2Forders%3Fstatus%3DPAST",
    );
  });

  it("shows 403 as an actor mismatch instead of a login prompt", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(failure(403, "ACCESS_DENIED", "고객 권한이 필요합니다.") as never);

    renderApp("/app/orders");

    expect(await screen.findByText("이 브라우저의 인증 정보는 고객 화면을 사용할 수 없습니다. 다른 역할로 로그인되어 있는지 확인해 주세요.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "로그인" })).not.toBeInTheDocument();
  });

  it("shows 503 as a dependency failure that is not a logout", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(failure(503, "DEPENDENCY_UNAVAILABLE", "인증 의존성을 사용할 수 없습니다.") as never);

    renderApp("/app/orders");

    expect(await screen.findByText("로그인 상태를 확인하지 못했습니다. 로그아웃된 것이 아니므로 다시 시도해 주세요.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "로그인" })).not.toBeInTheDocument();
  });

  it("keeps only same-origin customer paths as a return target", () => {
    expect(sanitizeReturnPath("/app/orders")).toBe("/app/orders");
    expect(sanitizeReturnPath("//evil.example/app")).toBe("/app");
    expect(sanitizeReturnPath("https://evil.example/app")).toBe("/app");
    expect(sanitizeReturnPath(null)).toBe("/app");
  });
});

describe("customer login and signup states", () => {
  it("separates invalid credentials from a rate limit", async () => {
    const post = vi.spyOn(customerApi, "POST")
      .mockResolvedValueOnce(failure(401, "AUTHENTICATION_FAILED") as never)
      .mockResolvedValueOnce(failure(429, "AUTHENTICATION_RATE_LIMITED", "로그인 시도가 너무 많습니다. 15분 뒤 다시 시도해 주세요.") as never);
    vi.spyOn(customerApi, "GET").mockResolvedValue(failure(401, "UNAUTHORIZED") as never);

    renderApp("/app/login");
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("아이디"), "customer01");
    await user.type(screen.getByLabelText("비밀번호"), "correct-horse-battery");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("아이디 또는 비밀번호를 확인해 주세요.");

    await user.click(screen.getByRole("button", { name: "로그인" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("15분 뒤 다시 시도해 주세요.");
    expect(post).toHaveBeenCalledTimes(2);
  });

  it("shows a duplicate login ID as a correctable signup field", async () => {
    vi.spyOn(customerApi, "POST").mockResolvedValue(failure(409, "LOGIN_ID_UNAVAILABLE") as never);

    renderApp("/app/signup");
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("아이디"), "customer01");
    await user.type(screen.getByLabelText("표시 이름"), "도현");
    await user.type(screen.getByLabelText("비밀번호"), "correct-horse-battery");
    await user.click(screen.getByRole("button", { name: "가입하고 시작하기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 사용 중인 아이디입니다.");
    expect(screen.getByLabelText("아이디")).toHaveAttribute("aria-invalid", "true");
  });

  it("keeps the newly created account visible when the automatic login after signup fails", async () => {
    const post = vi.spyOn(customerApi, "POST")
      .mockResolvedValueOnce(ok({ loginId: "customer01" }) as never)
      .mockResolvedValueOnce(failure(503, "DEPENDENCY_UNAVAILABLE", "인증 의존성을 사용할 수 없습니다.") as never);
    vi.spyOn(customerApi, "GET").mockResolvedValue(failure(401, "UNAUTHORIZED") as never);

    renderApp("/app/signup");
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("아이디"), "customer01");
    await user.type(screen.getByLabelText("표시 이름"), "도현");
    await user.type(screen.getByLabelText("비밀번호"), "correct-horse-battery");
    await user.click(screen.getByRole("button", { name: "가입하고 시작하기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("가입은 완료됐지만 로그인하지 못했습니다.");
    expect(screen.getByText(/회원가입은 완료됐어요/)).toBeInTheDocument();
    expect(screen.getByLabelText("아이디")).toHaveValue("customer01");
    expect(screen.getByLabelText("아이디")).toHaveAttribute("readonly");

    post.mockResolvedValueOnce(ok(actor) as never);
    await user.click(screen.getByRole("button", { name: "다시 로그인" }));

    await waitFor(() => expect(post).toHaveBeenCalledTimes(3));
    // The retry must log in, never register again with the already-taken ID.
    expect(post.mock.calls[2]?.[0]).toBe("/auth/customer/sessions");
    expect(customerSession.get().status).toBe("authenticated");
  });

  it("sends the CSRF header on the session command", async () => {
    const post = vi.spyOn(customerApi, "POST").mockResolvedValue(ok(actor) as never);
    vi.spyOn(customerApi, "GET").mockResolvedValue(failure(401, "UNAUTHORIZED") as never);

    renderApp("/app/login");
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("아이디"), "customer01");
    await user.type(screen.getByLabelText("비밀번호"), "correct-horse-battery");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(post).toHaveBeenCalled());
    expect(post.mock.calls[0]?.[1]).toMatchObject({
      params: { header: { "X-BEANFLOW-CSRF": "customer-csrf-token" } },
      body: { loginId: "customer01" },
    });
  });
});

describe("customer logout", () => {
  it("clears customer cart and submit intents but keeps operator token state", async () => {
    localStorage.setItem("beanflow.customer.cart.v1", '{"version":1}');
    sessionStorage.setItem("beanflow.idempotency.payment.order-1", "key");
    sessionStorage.setItem("beanflow.payment-attempt.payment-1", "{}");
    authToken.set("operator-access-token");
    vi.spyOn(customerApi, "GET").mockResolvedValue(ok(actor) as never);
    const remove = vi.spyOn(customerApi, "DELETE").mockResolvedValue({ response: new Response(null, { status: 204 }) } as never);

    renderApp("/app/me");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "로그아웃" }));

    await waitFor(() => expect(remove).toHaveBeenCalled());
    expect(localStorage.getItem("beanflow.customer.cart.v1")).toBeNull();
    expect(sessionStorage.getItem("beanflow.idempotency.payment.order-1")).toBeNull();
    expect(sessionStorage.getItem("beanflow.payment-attempt.payment-1")).toBeNull();
    expect(authToken.get()).toBe("operator-access-token");
    authToken.clear();
  });

  it("blocks a protected route again after logout", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(ok(actor) as never);
    vi.spyOn(customerApi, "DELETE").mockResolvedValue({ response: new Response(null, { status: 204 }) } as never);
    await customerSession.refresh();

    await customerSession.logOut();

    renderApp("/app/orders");
    expect(await screen.findByRole("heading", { name: "로그인" })).toBeInTheDocument();
  });

  it("keeps the browser authenticated when the server logout fails, so the customer can retry", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(ok(actor) as never);
    const remove = vi.spyOn(customerApi, "DELETE")
      .mockResolvedValue(failure(503, "DEPENDENCY_UNAVAILABLE", "인증 의존성을 사용할 수 없습니다.") as never);

    renderApp("/app/me");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "로그아웃" }));

    await waitFor(() => expect(remove).toHaveBeenCalled());
    expect(await screen.findByText("요청을 완료하지 못했습니다")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "내 정보" })).toBeInTheDocument();
    expect(customerSession.get().status).toBe("authenticated");
  });

  it("treats a 401 on session delete as already logged out server-side", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(ok(actor) as never);
    vi.spyOn(customerApi, "DELETE").mockResolvedValue(failure(401, "UNAUTHORIZED") as never);

    renderApp("/app/me");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "로그아웃" }));

    expect(await screen.findByRole("heading", { name: "로그인" })).toBeInTheDocument();
  });
});
