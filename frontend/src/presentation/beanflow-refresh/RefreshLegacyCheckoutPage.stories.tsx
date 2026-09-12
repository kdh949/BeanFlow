import type { Meta, StoryObj } from "@storybook/react-vite";
import { Route, Routes } from "react-router";
import MockDate from "mockdate";
import { expect } from "storybook/test";
import { checkoutHandlers, ids, signedInHandlers } from "../../../.storybook/fixtures";
import { RefreshCheckoutPage, RefreshLegacyCheckoutPage } from "./CustomerTransactionPages";

const meta = {
  title: "Pages/Customer/Legacy checkout redirect",
  component: RefreshLegacyCheckoutPage,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" }, layout: "fullscreen", docs: { description: { component: "이전에 저장된 결제 주소를 소유 주문의 공개 주문번호 기반 결제 화면으로 전환합니다." }, story: { inline: false, height: "844px" } }, routing: { path: "*", initialEntry: `/app/checkout/${ids.order}`, surface: "refresh-customer" }, msw: { handlers: [...signedInHandlers, ...checkoutHandlers] } },
  beforeEach: () => { MockDate.set("2026-08-15T03:00:00Z"); return () => MockDate.reset(); },
  render: () => <Routes><Route path="/app/checkout/:orderId" element={<RefreshLegacyCheckoutPage />} /><Route path="/app/orders/:orderReference/checkout" element={<RefreshCheckoutPage />} /></Routes>,
} satisfies Meta<typeof RefreshLegacyCheckoutPage>;
export default meta;
type Story = StoryObj<typeof meta>;

export const OwnedOrder: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("button", { name: /12,800.*결제하기/ })).toBeEnabled();
    await expect(canvas.queryByText(ids.order)).not.toBeInTheDocument();
  },
};
