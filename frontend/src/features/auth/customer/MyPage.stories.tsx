import type { Meta, StoryObj } from "@storybook/react-vite";
import { HttpResponse, http } from "msw";
import { cart } from "../../ordering/cart";
import { expect } from "storybook/test";
import { apiError, customerIdentity, ids, pointsHandlers, signedInHandlers } from "../../../../.storybook/fixtures";
import { CustomerMyPage } from "./MyPage";
import { customerSession } from "./customerSession";

const meta = {
  title: "Pages/Customer/My page",
  component: CustomerMyPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen",
    docs: {
      description: {
        component:
          "로그인한 계정과 주문·포인트로 가는 입구입니다. 로그아웃은 이 브라우저의 고객 상태만 지우고 콘솔 token은 남깁니다.",
      },
      story: { inline: false, height: "844px" },
    },
    routing: { path: "/app/me", initialEntry: "/app/me", surface: "customer" },
    msw: { handlers: [...signedInHandlers, ...pointsHandlers] },
  },
  // In the app the gate resolves the session before this route renders.
  beforeEach: async () => {
    cart.clear();
    customerSession.reset();
    await customerSession.refresh();
  },
} satisfies Meta<typeof CustomerMyPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SignedIn: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(customerIdentity.displayName)).toBeVisible();
    await expect(canvas.getByRole("link", { name: "주문 내역" })).toHaveAttribute("href", "/app/orders");
    await expect(canvas.getByRole("link", { name: "쿠폰 받기" })).toHaveAttribute("href", "/app/coupon-claims");
    await expect(canvas.queryByRole("link", { name: "환불 내역" })).not.toBeInTheDocument();
    await expect(canvas.getByRole("button", { name: "로그아웃" })).toBeEnabled();
    await expect(await canvas.findByText("1,500P")).toBeVisible();
    await expect(canvas.getByText("매장을 고르면 쿠폰을 확인할 수 있어요.")).toBeVisible();
  },
};

export const StoreCouponSummary: Story = {
  tags: ["!autodocs"],
  beforeEach: () => { cart.add({ storeId: ids.store, storeName: "시청점" }, { menuId: ids.menu, optionIds: [], quantity: 1, display: { menuName: "오트 라떼", optionNames: [], unitPriceKrw: 6400 } }); },
  parameters: { msw: { handlers: [http.get("/api/v1/me/coupons", () => HttpResponse.json({ items: [{ couponIssuanceId: "coupon-1", applicable: true }], page: {} })), ...meta.parameters.msw.handlers] } },
  play: async ({ canvas }) => { await expect(await canvas.findByText("시청점 쿠폰")).toBeVisible(); await expect(await canvas.findByText("1개")).toBeVisible(); },
};
export const BalanceUnavailable: Story = {
  parameters: { msw: { handlers: [apiError("/api/v1/me/points"), ...signedInHandlers] } },
  play: async ({ canvas }) => { await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByText("0P")).not.toBeInTheDocument(); },
};
