import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { OpsDashboardPage } from "./ConsolePages";

const meta = {
  title: "Pages/Operations/Dashboard",
  component: OpsDashboardPage,
  tags: ["autodocs"],
  parameters: {
    docs: { description: { component: "운영팀이 지금 확인하거나 처리할 업무를 한눈에 보는 화면입니다." } },
    routing: { path: "/ops", initialEntry: "/ops" },
  },
} satisfies Meta<typeof OpsDashboardPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Dashboard: Story = {
  args: {
    scenario: "ready",
    summary: {
      failureAttention: 11,
      settlementMismatch: 1,
      auditAccessToday: 42,
      refundApprovals: 3,
      campaignInProgress: 2,
      payoutReady: 1,
    },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText("11건")).toBeVisible();
    await expect(canvas.getByRole("link", { name: /문제와 정산 확인/ })).toBeVisible();
  },
};

export const ContractPending: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toHaveTextContent("운영 요약을 준비하고 있습니다");
  },
};
