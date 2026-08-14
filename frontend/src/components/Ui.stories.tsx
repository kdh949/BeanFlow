import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { StatusBadge } from "./Ui";

const meta = {
  component: StatusBadge,
  tags: ["ai-generated"],
} satisfies Meta<typeof StatusBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Paid: Story = {
  args: { state: "PAID" },
};

export const Preparing: Story = {
  args: { state: "PREPARING" },
};

export const ManualReview: Story = {
  args: { state: "MANUAL_REVIEW" },
};

export const CssCheck: Story = {
  args: { state: "PAID" },
  play: async ({ canvas }) => {
    const badge = canvas.getByText("결제 완료");
    await expect(getComputedStyle(badge).backgroundColor).toBe("rgb(220, 245, 233)");
  },
};
