import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { apiError, checkoutHandlers, ids, pending } from "../../../.storybook/fixtures";
import { CheckoutPage } from "./CheckoutPage";

const meta = {
  title: "Pages/Customer/Checkout",
  component: CheckoutPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: { component: "서버가 계산한 주문 snapshot과 payable amount를 확인한 뒤 외부 결제를 시작하는 route입니다." },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/checkout/:orderId", initialEntry: `/app/checkout/${ids.order}` },
    msw: { handlers: checkoutHandlers },
  },
} satisfies Meta<typeof CheckoutPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const PendingPayment: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("button", { name: "₩12,800 결제하기" })).toBeEnabled();
  },
};

export const RecoverableError: Story = {
  parameters: { msw: { handlers: [apiError("/api/v1/orders/:orderId")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};

export const Loading: Story = {
  parameters: { msw: { handlers: [pending("/api/v1/orders/:orderId")] } },
};
