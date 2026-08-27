import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { operationsApi } from "../../api/consoleClient";
import { MerchantAccountsPage } from "./MerchantAccountsPage";

const accountId = "94000000-0000-4000-8000-000000000001";
const storeId = "10000000-0000-4000-8000-000000000001";

function response(data: unknown, status = 200) {
  return { data, response: new Response(null, { status }) } as never;
}

const account = {
  merchantAccountId: accountId,
  loginId: "merchant01",
  displayName: "성수점 점주",
  accountState: "ACTIVE",
  lockedUntil: "2026-08-24T09:00:00+09:00",
  memberships: [{ storeId, role: "OWNER" }],
};

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("MerchantAccountsPage", () => {
  it("uses an exact login query and audited header, then connects reset and unlock commands", async () => {
    const get = vi.spyOn(operationsApi, "GET").mockResolvedValue(response(account));
    const post = vi.spyOn(operationsApi, "POST").mockImplementation((async (path: string) => {
      if (path.endsWith("temporary-password-resets")) {
        return response({
          merchantAccountId: accountId,
          accountState: "INITIAL_PASSWORD",
          temporaryPassword: "TEMPORARY_PASSWORD_000000000001",
          temporaryPasswordExpiresAt: "2026-08-24T18:00:00+09:00",
        });
      }
      if (path.endsWith("lock-releases")) return response(undefined, 204);
      throw new Error(`unexpected POST ${path}`);
    }) as never);

    render(<MemoryRouter><MerchantAccountsPage /></MemoryRouter>);
    await userEvent.type(screen.getByLabelText("점주 로그인 ID"), "merchant01");
    await userEvent.selectOptions(screen.getByLabelText("조회 사유"), "MERCHANT_ACCOUNT_RECOVERY");
    await userEvent.click(screen.getByRole("button", { name: "계정 조회" }));

    await screen.findByText("성수점 점주");
    expect(get).toHaveBeenCalledWith("/operations/merchant-accounts", {
      params: {
        query: { loginId: "merchant01" },
        header: { "X-Access-Reason": "MERCHANT_ACCOUNT_RECOVERY" },
      },
    });

    await userEvent.type(screen.getByLabelText("임시 비밀번호 재발급 사유"), "본인 확인 완료");
    await userEvent.click(screen.getByRole("button", { name: "임시 비밀번호 재발급" }));
    expect(await screen.findByText("TEMPORARY_PASSWORD_000000000001")).toBeVisible();
    expect(JSON.stringify(localStorage)).not.toContain("TEMPORARY_PASSWORD_000000000001");
    expect(JSON.stringify(sessionStorage)).not.toContain("TEMPORARY_PASSWORD_000000000001");

    await userEvent.type(screen.getByLabelText("잠금 해제 사유"), "잠금 정책 확인 완료");
    await userEvent.click(screen.getByRole("button", { name: "로그인 잠금 해제" }));
    expect(await screen.findByText("로그인 잠금을 해제했습니다")).toBeVisible();

    const calls = post.mock.calls as unknown as Array<[string, { params: { header: Record<string, string> }; body: unknown }] >;
    expect(calls[0]?.[0]).toContain("temporary-password-resets");
    expect(calls[0]?.[1].body).toEqual({ reason: "본인 확인 완료" });
    expect(calls[0]?.[1].params.header["Idempotency-Key"]).toBeTruthy();
    expect(calls[1]?.[0]).toContain("lock-releases");
    expect(calls[1]?.[1].body).toEqual({ reason: "잠금 정책 확인 완료" });
  });

  it("creates account and membership atomically and does not persist the one-time password", async () => {
    const post = vi.spyOn(operationsApi, "POST").mockResolvedValue(response({
      merchantAccountId: accountId,
      loginId: "newmerchant",
      accountState: "INITIAL_PASSWORD",
      membership: { storeId, role: "OWNER" },
      temporaryPassword: "NEW_TEMPORARY_PASSWORD_00000001",
      temporaryPasswordExpiresAt: "2026-08-24T18:00:00+09:00",
    }, 201));

    render(<MemoryRouter><MerchantAccountsPage /></MemoryRouter>);
    await userEvent.click(screen.getByRole("tab", { name: "새 계정 발급" }));
    await userEvent.type(screen.getByLabelText("새 로그인 ID"), "newmerchant");
    await userEvent.type(screen.getByLabelText("표시 이름"), "신규 점주");
    await userEvent.type(screen.getByLabelText("첫 매장 ID"), storeId);
    await userEvent.type(screen.getByLabelText("발급 사유"), "신규 가맹 계약 승인");
    await userEvent.click(screen.getByRole("button", { name: "점주 계정 발급" }));

    expect(await screen.findByText("NEW_TEMPORARY_PASSWORD_00000001")).toBeVisible();
    await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
    const [, options] = post.mock.calls[0] as unknown as [string, { body: Record<string, unknown> }];
    expect(options.body).toEqual({
      loginId: "newmerchant",
      displayName: "신규 점주",
      storeId,
      membershipRole: "OWNER",
      reason: "신규 가맹 계약 승인",
    });
    expect(JSON.stringify(localStorage) + JSON.stringify(sessionStorage)).not.toContain("NEW_TEMPORARY_PASSWORD_00000001");
  });
});
