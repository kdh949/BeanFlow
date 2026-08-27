import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { customerApi } from "../api/customerClient";
import { CustomerLoginPage, CustomerSignupPage } from "./auth/customer/AuthPages";
import { customerSession } from "./auth/customer/customerSession";
import { CustomerOrdersPage } from "./ordering/CustomerOrdersPage";
import { pickupNumberNote } from "./ordering/orderPresentation";
import { CustomerPointsPage } from "./loyalty/PointsPage";
import { RefreshCartPage, RefreshStoreSearchPage } from "../presentation/beanflow-refresh";
import { PaymentRecovery } from "../presentation/beanflow-refresh/CustomerTransactionPages";

function sourceFiles(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) return sourceFiles(path);
    return /\.(ts|tsx)$/.test(entry) && !entry.endsWith(".test.tsx") && !entry.endsWith(".test.ts") ? [path] : [];
  });
}

function ok<T>(data: T) {
  return { data, response: new Response(null, { status: 200 }) };
}

function renderScreen(element: ReactElement) {
  return render(
    <MemoryRouter initialEntries={["/app"]}>
      <Routes>
        <Route path="/app" element={element} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  customerSession.reset();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("customer surface has no manual credential or identifier input", () => {
  const files = [
    "src/features/auth/customer",
    "src/features/customer",
    "src/features/discovery",
    "src/features/loyalty",
    "src/features/ordering",
    "src/features/payment",
  ].flatMap(sourceFiles);

  it("never imports the console Bearer token store", () => {
    const offenders = files.filter((file) => /auth\/session"|consoleClient"/.test(readFileSync(file, "utf8")));
    expect(offenders).toEqual([]);
  });

  it("never renders a token or UUID entry field", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue(ok({ items: [], page: {} }) as never);
    const screens: Array<[string, ReactElement]> = [
      ["login", <CustomerLoginPage key="login" />],
      ["signup", <CustomerSignupPage key="signup" />],
      ["store search", <RefreshStoreSearchPage key="search" />],
      ["cart", <RefreshCartPage key="cart" />],
      ["orders", <CustomerOrdersPage key="orders" />],
    ];

    for (const [name, element] of screens) {
      const view = renderScreen(element);
      const fields = Array.from(view.container.querySelectorAll("input, textarea"));
      for (const field of fields) {
        const description = [
          field.getAttribute("placeholder"),
          field.getAttribute("name"),
          field.getAttribute("id"),
          field.getAttribute("aria-label"),
        ].join(" ").toLowerCase();
        expect(description, `${name} field`).not.toMatch(/uuid|token|토큰|주문번호|계정 번호/);
      }
      view.unmount();
    }
  });
});

describe("customer screens never show a raw status code", () => {
  it("labels every refund recovery state in words", () => {
    for (const state of ["NOT_REQUIRED", "REQUESTED", "PROCESSING", "SUCCEEDED"] as const) {
      const view = render(
        <PaymentRecovery recovery={{ state, cancellationRequestedRefundAmountKrw: 0 } as never} />,
      );
      expect(view.container.textContent, state).not.toMatch(/[A-Z]{3,}_[A-Z_]+/);
      view.unmount();
    }
  });

  it("hides the pickup number until the order is actually going to be handed over", () => {
    expect(pickupNumberNote("PENDING_PAYMENT")).toBeNull();
    expect(pickupNumberNote("CANCELLED")).toBeNull();
    expect(pickupNumberNote("EXPIRED")).toBeNull();
    expect(pickupNumberNote("READY")).toMatch(/픽업대/);
    expect(pickupNumberNote("PREPARING")).not.toMatch(/픽업대/);
  });
});

describe("customer forms are usable at the 420px viewport", () => {
  it("labels every field and reaches the submit control by keyboard", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue({
      error: { code: "UNAUTHORIZED", message: "인증이 필요합니다." },
      response: new Response(null, { status: 401 }),
    } as never);

    renderScreen(<CustomerLoginPage />);
    const user = userEvent.setup();

    expect(screen.getByLabelText("아이디")).toBeInTheDocument();
    expect(screen.getByLabelText("비밀번호")).toBeInTheDocument();

    await user.tab();
    expect(screen.getByLabelText("아이디")).toHaveFocus();
    await user.tab();
    expect(screen.getByLabelText("비밀번호")).toHaveFocus();
  });

  it("announces a submit failure and points the field at its message", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue({
      error: { code: "UNAUTHORIZED", message: "인증이 필요합니다." },
      response: new Response(null, { status: 401 }),
    } as never);
    vi.spyOn(customerApi, "POST").mockResolvedValue({
      error: { code: "AUTHENTICATION_FAILED", message: "인증에 실패했습니다." },
      response: new Response(null, { status: 401 }),
    } as never);

    renderScreen(<CustomerLoginPage />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("아이디"), "customer01");
    await user.type(screen.getByLabelText("비밀번호"), "correct-horse-battery");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    const alert = await screen.findByRole("alert");
    expect(screen.getByLabelText("아이디")).toHaveAttribute("aria-describedby", alert.id);
    expect(screen.getByLabelText("아이디")).toHaveAttribute("aria-invalid", "true");
  });

  it("stops the pending spinner animation under reduced motion", () => {
    const styles = readFileSync("src/styles.css", "utf8");
    expect(styles).toMatch(/@media \(prefers-reduced-motion: reduce\)\s*{\s*\.spin\s*{\s*animation: none;/);
  });

  it("gives every loading and error state an announcement role", async () => {
    vi.spyOn(customerApi, "GET").mockResolvedValue({
      error: { code: "DEPENDENCY_UNAVAILABLE", message: "포인트를 조회하지 못했습니다." },
      response: new Response(null, { status: 503 }),
    } as never);

    renderScreen(<CustomerPointsPage />);

    expect(await screen.findByRole("alert")).toHaveTextContent("포인트를 조회하지 못했습니다.");
  });
});
