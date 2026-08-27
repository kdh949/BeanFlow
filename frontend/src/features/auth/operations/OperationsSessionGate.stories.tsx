import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
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
    clear: fn(),
    consumeReturnPath: () => "/ops",
  };
}

const meta = {
  title: "Pages/Operations/Authentication",
  component: OperationsSessionGate,
  tags: ["autodocs"],
  parameters: {
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
    await expect(await canvas.findByText("운영자 로그인이 필요합니다")).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "Keycloak로 로그인" }));
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
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("운영 권한이 없습니다")).toBeVisible();
  },
};
