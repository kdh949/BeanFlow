import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { Button } from "../../components/core/Button";
import { PageHeading } from "./PageHeading";

const meta = {
  title: "Patterns/Layout/PageHeading",
  component: PageHeading,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" } },
} satisfies Meta<typeof PageHeading>;

export default meta;
type Story = StoryObj<typeof meta>;
export const Default: Story = {
  args: { title: "주문 보드" },
  play: async ({ canvas, canvasElement }) => {
    await expect(canvas.getByRole("heading", { level: 1, name: "주문 보드" })).toBeVisible();
    await expect(canvasElement.querySelector(".bf-page-heading p")).toBeNull();
  },
};
export const WithAction: Story = {
  args: { title: "정산 내역", action: <Button variant="secondary">새로고침</Button> },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("button", { name: "새로고침" })).toBeVisible();
  },
};
export const LongKoreanTitle: Story = {
  args: { title: "고객과 매장의 거래 상태를 정확하게 확인하고 복구하기" },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent("고객과 매장의 거래 상태를 정확하게 확인하고 복구하기");
  },
};
