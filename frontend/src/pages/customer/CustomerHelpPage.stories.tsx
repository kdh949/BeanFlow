import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { CustomerHelpPage } from "./CustomerPages";

const meta = {
  title: "Pages/Customer/Help",
  component: CustomerHelpPage,
  tags: ["autodocs"],
  parameters: {
    docs: { description: { component: "결제·환불 문의에 필요한 안전한 정보와 다음 행동을 안내하는 route입니다." } },
    routing: { path: "/app/help", initialEntry: "/app/help" },
  },
} satisfies Meta<typeof CustomerHelpPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Guidance: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByText(/카드 번호나 인증 정보는 보내지 마세요/)).toBeVisible();
  },
};
