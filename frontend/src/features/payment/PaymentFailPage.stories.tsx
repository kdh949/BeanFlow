import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { ids, paymentHandlers } from "../../../.storybook/fixtures";
import { PaymentFailPage } from "./PaymentResultPages";

const meta = {
  title: "Pages/Customer/PaymentFailure",
  component: PaymentFailPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: { component: "provider callback 문구보다 server state를 우선하며, manual review에서는 공개 주문 추적과 도움말만 제공합니다." },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/payments/:paymentId/fail", initialEntry: `/app/payments/${ids.payment}/fail?code=PAY_PROCESS_CANCELED` },
  },
} satisfies Meta<typeof PaymentFailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const RetryableFailure: Story = {
  parameters: { msw: { handlers: paymentHandlers("READY") } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("link", { name: "주문 상태 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
  },
};

export const ManualReview: Story = {
  parameters: { msw: { handlers: paymentHandlers("MANUAL_REVIEW") } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("결제 확인에 시간이 더 필요해요")).toBeVisible();
    await expect(canvas.getByRole("link", { name: "주문 상태 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
    await expect(canvas.getByRole("link", { name: "도움이 필요해요" })).toBeVisible();
    await expect(canvas.queryByRole("link", { name: "주문서로 돌아가기" })).not.toBeInTheDocument();
  },
};
