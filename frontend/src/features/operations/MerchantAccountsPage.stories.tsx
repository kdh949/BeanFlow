import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { ids } from "../../../.storybook/fixtures";
import { MerchantAccountsPage } from "./MerchantAccountsPage";

const accountId = "94000000-0000-4000-8000-000000000001";
const account = {
  merchantAccountId: accountId,
  loginId: "merchant01",
  displayName: "성수점 점주",
  accountState: "ACTIVE",
  memberships: [{ storeId: ids.store, role: "OWNER" }],
};

const lookupAccount = http.get("/api/v1/operations/merchant-accounts", () => HttpResponse.json(account));
const resetPassword = http.post("/api/v1/operations/merchant-accounts/:accountId/temporary-password-resets", () =>
  HttpResponse.json({
    merchantAccountId: accountId,
    accountState: "INITIAL_PASSWORD",
    temporaryPassword: "RESET_PASSWORD_DEMO_00000000001",
    temporaryPasswordExpiresAt: "2026-08-24T18:00:00+09:00",
  }));
const releaseLock = http.post("/api/v1/operations/merchant-accounts/:accountId/lock-releases", () =>
  new HttpResponse(null, { status: 204 }));
const createAccount = http.post("/api/v1/operations/merchant-accounts", () => HttpResponse.json({
  merchantAccountId: accountId,
  loginId: "newmerchant",
  accountState: "INITIAL_PASSWORD",
  membership: { storeId: ids.store, role: "OWNER" },
  temporaryPassword: "NEW_PASSWORD_DEMO_0000000000001",
  temporaryPasswordExpiresAt: "2026-08-24T18:00:00+09:00",
}, { status: 201 }));

const meta = {
  title: "Pages/Operations/Merchant accounts",
  component: MerchantAccountsPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "점주 계정을 exact 조회하고 계정+첫 매장 권한 발급, 임시 비밀번호 재발급, 잠금 해제를 수행합니다. 일회성 비밀번호는 route 메모리에서만 표시합니다.",
      },
      story: { inline: false, height: "900px" },
    },
    routing: { path: "/ops/merchant-accounts", initialEntry: "/ops/merchant-accounts" },
    msw: { handlers: [lookupAccount, resetPassword, releaseLock, createAccount] },
  },
} satisfies Meta<typeof MerchantAccountsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

async function lookup(canvas: Parameters<NonNullable<Story["play"]>>[0]["canvas"]) {
  await userEvent.type(canvas.getByLabelText("점주 로그인 ID"), "merchant01");
  await userEvent.selectOptions(canvas.getByLabelText("조회 사유"), "MERCHANT_ACCOUNT_RECOVERY");
  await userEvent.click(canvas.getByRole("button", { name: "계정 조회" }));
  await expect(await canvas.findByText("성수점 점주")).toBeVisible();
}

export const ExactAccount: Story = {
  play: async ({ canvas }) => {
    await lookup(canvas);
    await expect(canvas.getByText(ids.store)).toBeVisible();
  },
};

export const LockedAccountReleased: Story = {
  parameters: {
    msw: { handlers: [http.get("/api/v1/operations/merchant-accounts", () => HttpResponse.json({
      ...account,
      lockedUntil: "2026-08-24T09:00:00+09:00",
    })), resetPassword, releaseLock] },
  },
  play: async ({ canvas }) => {
    await lookup(canvas);
    await userEvent.type(canvas.getByLabelText("잠금 해제 사유"), "잠금 정책 확인 완료");
    await userEvent.click(canvas.getByRole("button", { name: "로그인 잠금 해제" }));
    await expect(await canvas.findByText("로그인 잠금을 해제했습니다")).toBeVisible();
  },
};

export const NewAccountOneTimePassword: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("tab", { name: "새 계정 발급" }));
    await userEvent.type(canvas.getByLabelText("새 로그인 ID"), "newmerchant");
    await userEvent.type(canvas.getByLabelText("표시 이름"), "신규 점주");
    await userEvent.type(canvas.getByLabelText("첫 매장 ID"), ids.store);
    await userEvent.type(canvas.getByLabelText("발급 사유"), "신규 가맹 계약 승인");
    await userEvent.click(canvas.getByRole("button", { name: "점주 계정 발급" }));
    await expect(await canvas.findByText("NEW_PASSWORD_DEMO_0000000000001")).toBeVisible();
    await expect(canvas.getByRole("heading", { name: "임시 비밀번호" })).toBeVisible();
    await expect(canvas.getByText(/^만료 /)).toBeVisible();
    await expect(canvas.queryByText(/티켓·로그|브라우저 저장소|지금 전달/)).not.toBeInTheDocument();
    await userEvent.click(canvas.getByRole("button", { name: "화면에서 지우기" }));
    await expect(canvas.queryByText("NEW_PASSWORD_DEMO_0000000000001")).not.toBeInTheDocument();
  },
};

export const ResetConflict: Story = {
  parameters: {
    msw: {
      handlers: [
        lookupAccount,
        http.post("/api/v1/operations/merchant-accounts/:accountId/temporary-password-resets", () => HttpResponse.json({
          code: "TEMPORARY_PASSWORD_NOT_REPLAYABLE",
          message: "이 응답의 임시 비밀번호는 다시 표시할 수 없습니다. 새 요청으로 재발급해 주세요.",
          correlationId: "REQ-MERCHANT-409",
        }, { status: 409 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await lookup(canvas);
    await userEvent.type(canvas.getByLabelText("임시 비밀번호 재발급 사유"), "본인 확인 완료");
    await userEvent.click(canvas.getByRole("button", { name: "임시 비밀번호 재발급" }));
    await expect(await canvas.findByText("임시 비밀번호를 다시 표시할 수 없습니다")).toBeVisible();
    await expect(canvas.queryByText("RESET_PASSWORD_DEMO_00000000001")).not.toBeInTheDocument();
  },
};

export const ExactAccountNotFound: Story = {
  parameters: {
    msw: { handlers: [http.get("/api/v1/operations/merchant-accounts", () => HttpResponse.json({
      code: "MERCHANT_ACCOUNT_NOT_FOUND",
      message: "점주 계정을 찾을 수 없습니다.",
      correlationId: "REQ-MERCHANT-404",
    }, { status: 404 }))] },
  },
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText("점주 로그인 ID"), "unknown01");
    await userEvent.selectOptions(canvas.getByLabelText("조회 사유"), "MERCHANT_ACCOUNT_EXISTENCE_CHECK");
    await userEvent.click(canvas.getByRole("button", { name: "계정 조회" }));
    await expect(await canvas.findByText("일치하는 점주 계정이 없습니다")).toBeVisible();
  },
};

export const QueryUnavailable: Story = {
  parameters: {
    msw: { handlers: [http.get("/api/v1/operations/merchant-accounts", () => HttpResponse.json({
      code: "DEPENDENCY_UNAVAILABLE",
      message: "점주 계정 저장소를 사용할 수 없습니다.",
      correlationId: "REQ-MERCHANT-503",
    }, { status: 503 }))] },
  },
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText("점주 로그인 ID"), "merchant01");
    await userEvent.selectOptions(canvas.getByLabelText("조회 사유"), "MERCHANT_ACCOUNT_STATUS_REVIEW");
    await userEvent.click(canvas.getByRole("button", { name: "계정 조회" }));
    await expect(await canvas.findByText("서비스 연결을 확인하고 있습니다")).toBeVisible();
  },
};
