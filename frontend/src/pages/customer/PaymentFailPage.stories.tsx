import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { ids, paymentHandlers } from "../../../.storybook/fixtures";
import { PaymentFailPage } from "./CustomerPages";

const meta = {
  title: "Pages/Customer/PaymentFailure",
  component: PaymentFailPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: { component: "provider callback 문구보다 server state를 우선해 재결제 가능 여부를 결정하는 route입니다." },
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
    await expect(await canvas.findByRole("link", { name: "주문서로 돌아가기" })).toBeVisible();
  },
};

export const ManualReview: Story = {
  parameters: { msw: { handlers: paymentHandlers("MANUAL_REVIEW") } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("결제 결과를 확인하고 있어요")).toBeVisible();
    await expect(canvas.queryByRole("link", { name: "주문서로 돌아가기" })).not.toBeInTheDocument();
  },
};
