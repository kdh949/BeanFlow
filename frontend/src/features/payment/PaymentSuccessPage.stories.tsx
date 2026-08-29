import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { apiError, ids, paymentHandlers } from "../../../.storybook/fixtures";
import { PaymentSuccessPage } from "./PaymentResultPages";

const meta = {
  title: "Pages/Customer/PaymentSuccess",
  component: PaymentSuccessPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: { component: "clean callback URL에서 server payment status를 읽고 공개 주문 번호로 안전하게 추적합니다." },
      story: { inline: false, height: "720px" },
    },
    routing: { surface: "customer", path: "/app/payments/:paymentId/success", initialEntry: `/app/payments/${ids.payment}/success` },
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

export const ManualReview: Story = {
  parameters: { msw: { handlers: paymentHandlers("MANUAL_REVIEW") } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("결제 확인에 시간이 더 필요해요")).toBeVisible();
    await expect(canvas.getByRole("link", { name: "주문 상태 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
    await expect(canvas.getByRole("link", { name: "도움이 필요해요" })).toBeVisible();
  },
};

/** Returning to the success URL does not make an unpaid payment approved. */
export const NotPaidYet: Story = {
  parameters: { msw: { handlers: paymentHandlers("READY") } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("아직 결제가 끝나지 않았어요")).toBeVisible();
    await expect(canvas.queryByText("결제가 완료됐어요")).not.toBeInTheDocument();
  },
};

export const Declined: Story = {
  parameters: { msw: { handlers: paymentHandlers("FAILED") } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("결제를 완료하지 못했어요")).toBeVisible();
  },
};

export const DependencyError: Story = {
  parameters: { msw: { handlers: [apiError("/api/v1/payments/:paymentId")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};
