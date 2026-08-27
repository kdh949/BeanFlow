import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { BrandLockup } from "./BrandLockup";

const meta = {
  title: "Components/Brand/BrandLockup",
  component: BrandLockup,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" } },
} satisfies Meta<typeof BrandLockup>;

export default meta;
type Story = StoryObj<typeof meta>;
export const Static: Story = {};
export const HomeLink: Story = {
  args: { to: "/app" },
  play: async ({ canvas }) => { await expect(canvas.getByRole("link", { name: "BeanFlow 홈" })).toBeVisible(); },
};
