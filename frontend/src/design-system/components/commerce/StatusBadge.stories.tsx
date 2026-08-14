import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { StatusBadge } from "./StatusBadge";

const meta = {
  title: "Components/Commerce/StatusBadge",
  component: StatusBadge,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component: "서버 transaction state를 성공·진행·불확실·실패 의미를 잃지 않고 표시합니다.",
      },
    },
  },
} satisfies Meta<typeof StatusBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Paid: Story = { args: { state: "PAID" } };
export const Preparing: Story = { args: { state: "PREPARING" } };
export const ManualReview: Story = { args: { state: "MANUAL_REVIEW" } };
export const Failed: Story = { args: { state: "FAILED" } };

export const UnknownStatePreserved: Story = {
  args: { state: "PROVIDER_PENDING_REVIEW" },
  play: async ({ canvas }) => {
    await expect(canvas.getByText("PROVIDER_PENDING_REVIEW")).toBeVisible();
  },
};
