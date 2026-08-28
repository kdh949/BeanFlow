import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { OpsDashboardPage } from "./ConsolePages";

const meta = {
  title: "Pages/Operations/Dashboard",
  component: OpsDashboardPage,
  tags: ["autodocs"],
  parameters: {
    docs: { description: { component: "현재 운영 route들의 server-owned 상태와 감사 요구를 확인하는 workspace입니다." } },
    routing: { path: "/ops", initialEntry: "/ops" },
  },
} satisfies Meta<typeof OpsDashboardPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Dashboard: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByText("확인 필요")).toBeVisible();
  },
};
