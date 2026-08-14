import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { apiError, ids, paymentHandlers } from "../../../.storybook/fixtures";
import { PaymentSuccessPage } from "./CustomerPages";

const meta = {
  title: "Pages/Customer/PaymentSuccess",
  component: PaymentSuccessPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: { component: "clean callback URL에서 server payment status를 읽고 승인 또는 복구 상태를 표시합니다." },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/payments/:paymentId/success", initialEntry: `/app/payments/${ids.payment}/success` },
  },
} satisfies Meta<typeof PaymentSuccessPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Approved: Story = {
  parameters: { msw: { handlers: paymentHandlers("APPROVED") } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("결제가 완료됐어요")).toBeVisible();
  },
};

export const UnknownReconciliation: Story = {
  parameters: { msw: { handlers: paymentHandlers("UNKNOWN") } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("결제 결과를 확인하고 있어요")).toBeVisible();
  },
};

export const DependencyError: Story = {
  parameters: { msw: { handlers: [apiError("/api/v1/payments/:paymentId")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};
