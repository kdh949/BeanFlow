import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { HttpResponse, http } from "msw";
import { checkoutHandlers, checkoutOrder, ids, signedInHandlers } from "../../../.storybook/fixtures";
import { RefreshCheckoutPage } from "./CustomerTransactionPages";

const meta = {
  title: "Pages/Refresh/Customer/Checkout",
  component: RefreshCheckoutPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen", docs: { story: { inline: false, height: "844px" } },
    routing: { path: "/app/checkout/:orderId", initialEntry: `/app/checkout/${ids.order}`, surface: "refresh-customer" },
    msw: { handlers: [...signedInHandlers, ...checkoutHandlers] },
  },
} satisfies Meta<typeof RefreshCheckoutPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const PendingPayment: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("다음 결제창에서 카드·간편결제를 선택해 주세요.")).toBeVisible();
    await expect(await canvas.findByRole("button", { name: /12,800.*결제하기/ })).toBeEnabled();
    await expect(canvas.queryByText(/저장된 카드/)).not.toBeInTheDocument();
    await expect(canvas.getByText(/까지 결제해 주세요/)).toBeVisible();
    await expect(canvas.queryByText("예약 만료까지")).not.toBeInTheDocument();
  },
};

export const ExpiredOrder: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, http.get("/api/v1/orders/:orderId", () => HttpResponse.json({ ...checkoutOrder, state: "EXPIRED" }))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toHaveTextContent("결제 시간이 만료됐어요");
    await expect(await canvas.findByRole("button", { name: /결제하기/ })).toBeDisabled();
  },
};
