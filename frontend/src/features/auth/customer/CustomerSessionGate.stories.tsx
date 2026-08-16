import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { HttpResponse, http } from "msw";
import { customerIdentity, pending } from "../../../../.storybook/fixtures";
import { CustomerSessionGate } from "./CustomerSessionGate";
import { customerSession } from "./customerSession";

function meResponds(status: number, body: Record<string, unknown>) {
  return [http.get("/api/v1/me", () => HttpResponse.json(body, { status }))];
}

const meta = {
  title: "Patterns/Customer/Session gate",
  component: CustomerSessionGate,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "보호 route의 경계입니다. `GET /me`의 네 결과를 각각 다른 화면으로 그려서, 인증 저장소 장애를 로그아웃으로 읽지 않습니다.",
      },
      story: { inline: false, height: "420px" },
    },
    routing: { path: "/app/orders", initialEntry: "/app/orders" },
  },
  beforeEach: () => {
    customerSession.reset();
  },
} satisfies Meta<typeof CustomerSessionGate>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Checking: Story = {
  parameters: { msw: { handlers: [pending("/api/v1/me")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("로그인 상태를 확인하는 중")).toBeVisible();
  },
};

/** 403 is a different actor, not a signed-out customer, so it never redirects to login. */
export const WrongActor: Story = {
  parameters: {
    msw: { handlers: meResponds(403, { code: "ACCESS_DENIED", message: "고객 권한이 없습니다." }) },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/다른 역할로 로그인되어 있는지 확인해 주세요/)).toBeVisible();
  },
};

/** 503 means the session store is unreachable. Telling the customer they are signed out would be a lie. */
export const SessionStoreUnavailable: Story = {
  parameters: {
    msw: { handlers: meResponds(503, { code: "DEPENDENCY_UNAVAILABLE", message: "인증 저장소를 사용할 수 없습니다." }) },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/로그아웃된 것이 아니므로 다시 시도해 주세요/)).toBeVisible();
  },
};

export const SignedIn: Story = {
  parameters: { msw: { handlers: meResponds(200, customerIdentity) } },
};
