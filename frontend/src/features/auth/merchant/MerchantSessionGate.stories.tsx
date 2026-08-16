import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { HttpResponse, http } from "msw";
import { merchantIdentity, pending } from "../../../../.storybook/fixtures";
import { MerchantSessionGate } from "./MerchantSessionGate";
import { merchantSession } from "./merchantSession";

function merchantMeResponds(status: number, body: Record<string, unknown>) {
  return [http.get("/api/v1/merchant/me", () => HttpResponse.json(body, { status }))];
}

const meta = {
  title: "Patterns/Store/Session gate",
  component: MerchantSessionGate,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "매장 콘솔 route의 경계입니다. `GET /merchant/me`의 결과를 각각 다른 화면으로 그려서, 인증 저장소 장애를 로그아웃으로 읽지 않고 임시 비밀번호 계정을 주문보드로 보내지 않습니다.",
      },
      story: { inline: false, height: "420px" },
    },
    routing: { path: "/store", initialEntry: "/store" },
  },
  beforeEach: () => {
    merchantSession.reset();
  },
} satisfies Meta<typeof MerchantSessionGate>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Checking: Story = {
  parameters: { msw: { handlers: [pending("/api/v1/merchant/me")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("로그인 상태를 확인하는 중")).toBeVisible();
  },
};

/** 403 is a different actor, not a signed-out operator, so it never redirects to login. */
export const WrongActor: Story = {
  parameters: {
    msw: { handlers: merchantMeResponds(403, { code: "ACCESS_DENIED", message: "매장 권한이 없습니다." }) },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/다른 역할로 로그인되어 있는지 확인해 주세요/)).toBeVisible();
  },
};

/** 503 means the session store is unreachable. Telling the operator they are signed out would be a lie. */
export const SessionStoreUnavailable: Story = {
  parameters: {
    msw: {
      handlers: merchantMeResponds(503, { code: "DEPENDENCY_UNAVAILABLE", message: "인증 저장소를 사용할 수 없습니다." }),
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/로그아웃된 것이 아니므로 다시 시도해 주세요/)).toBeVisible();
  },
};

export const SignedIn: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/merchant/me", () => HttpResponse.json(merchantIdentity))] } },
};
