import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { HttpResponse, delay, http } from "msw";
import { ApiRequestError } from "../../../api/client";
import type { OperationsAuthState } from "../../../auth/session";
import { OperationsSessionGate } from "./OperationsSessionGate";

function session(state: OperationsAuthState) {
  return {
    get: () => state,
    subscribe: () => () => undefined,
    initialize: fn().mockResolvedValue(state),
    retry: fn().mockResolvedValue(state),
    logIn: fn().mockResolvedValue(undefined),
    logOut: fn().mockResolvedValue(undefined),
    clear: fn(),
    consumeReturnPath: () => "/ops",
  };
}

const meta = {
  title: "Pages/Operations/Authentication",
  component: OperationsSessionGate,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: {
        component:
          "공개 설정과 Keycloak PKCE S256 인증 뒤 `/operations/me`가 확인된 경우에만 운영 라우트를 엽니다. 수동 token 입력이나 저장소 fallback은 없습니다.",
      },
      story: { inline: false, height: "560px" },
    },
    routing: { path: "*", initialEntry: "/ops" },
  },
} satisfies Meta<typeof OperationsSessionGate>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SignInRequired: Story = {
  args: { session: session({ status: "unauthenticated" }) },
  play: async ({ canvas, args }) => {
    await expect(await canvas.findByText("조직 계정 로그인")).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "조직 계정으로 로그인" }));
    await expect(args.session?.logIn).toHaveBeenCalled();
  },
};

export const ConfigurationUnavailable: Story = {
  args: {
    session: session({
      status: "unavailable",
      error: new ApiRequestError(
        503,
        "OPERATIONS_OIDC_CONFIG_UNAVAILABLE",
        "OIDC 공개 설정을 확인할 수 없습니다.",
      ),
    }),
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("운영자 로그인 설정을 확인할 수 없습니다")).toBeVisible();
    await expect(canvas.getByRole("button", { name: /다시 시도/ })).toBeVisible();
  },
};

export const PermissionDenied: Story = {
  args: { session: session({ status: "authenticated", expiresAt: Date.now() / 1000 + 300 }) },
  parameters: {
    msw: {
      handlers: [
        http.get("/api/v1/operations/me", () => HttpResponse.json({
          code: "ACCESS_DENIED",
          message: "운영 역할이 없습니다.",
          correlationId: "REQ-OPS-403",
        }, { status: 403 })),
      ],
    },
  },
  play: async ({ canvas, args }) => {
    await expect(await canvas.findByText("업무 접근 권한이 없습니다")).toBeVisible();
    await expect(canvas.queryByRole("navigation", { name: "플랫폼 운영 메뉴" })).toBeNull();
    await userEvent.click(canvas.getByRole("button", { name: "로그아웃" }));
    await expect(args.session?.logOut).toHaveBeenCalled();
  },
};


export const CheckingSupportPermission: Story = {
  args: { kind: "support", session: session({ status: "authenticated", expiresAt: null }) },
  parameters: { routing: { path: "*", initialEntry: "/support/inquiries" }, msw: { handlers: [
    http.get("/api/v1/operations/me", async () => { await delay("infinite"); return HttpResponse.json({}); }),
  ] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/로그인을 확인하는 중/)).toBeVisible();
    await expect(canvas.queryByRole("navigation", { name: "고객지원 메뉴" })).toBeNull();
  },
};

export const VerifiedSupport: Story = {
  args: { kind: "support", session: session({ status: "authenticated", expiresAt: null }) },
  parameters: { routing: { path: "*", initialEntry: "/support/inquiries" }, msw: { handlers: [
    http.get("/api/v1/operations/me", () => HttpResponse.json({ actorType: "OPERATOR", operatorId: "support-test", roles: ["SUPPORT_AGENT"], display: { state: "AVAILABLE", loginName: "테스트 상담 담당자" } })),
  ] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("navigation", { name: "고객지원 메뉴" })).toBeVisible();
    await expect(canvas.getByRole("link", { name: "고객 문의" })).toHaveAttribute("href", "/support/inquiries");
    await expect(canvas.queryByRole("navigation", { name: "플랫폼 운영 메뉴" })).toBeNull();
  },
};
