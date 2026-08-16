import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { customerIdentity, signedInHandlers } from "../../../../.storybook/fixtures";
import { CustomerMyPage } from "./MyPage";
import { customerSession } from "./customerSession";

const meta = {
  title: "Pages/Customer/My page",
  component: CustomerMyPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "로그인한 계정과 주문·포인트로 가는 입구입니다. 로그아웃은 이 브라우저의 고객 상태만 지우고 콘솔 token은 남깁니다.",
      },
      story: { inline: false, height: "560px" },
    },
    routing: { path: "/app/me", initialEntry: "/app/me" },
    msw: { handlers: signedInHandlers },
  },
  // In the app the gate resolves the session before this route renders.
  beforeEach: async () => {
    customerSession.reset();
    await customerSession.refresh();
  },
} satisfies Meta<typeof CustomerMyPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SignedIn: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(customerIdentity.displayName)).toBeVisible();
    await expect(canvas.getByRole("button", { name: "로그아웃" })).toBeEnabled();
  },
};
