import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { catalogHandlers, ids, signedInHandlers, storeIdentityHandlers } from "../../../.storybook/fixtures";
import { CART_STORAGE_KEY, cart } from "../../features/ordering/cart";
import { RefreshCartPage } from "./CustomerCommercePages";

const line = { menuId: ids.menu, optionIds: [], quantity: 2, display: { menuName: "오트 라떼", optionNames: ["ICE"], unitPriceKrw: 6_400, imageUrl: "/demo/catalog/cafe-latte.webp" } };
const secondLine = { menuId: "20000000-0000-4000-8000-000000000002", optionIds: [], quantity: 1, display: { menuName: "아이스 아메리카노", optionNames: ["ICE"], unitPriceKrw: 3_800, imageUrl: "/demo/catalog/americano.webp" } };
const quote = { quotedAt: "2026-08-15T03:10:00Z", quoteFingerprint: "a".repeat(64), store: { storeId: ids.store, name: "시청점" }, pickupWindow: { startsAt: "2026-08-15T03:20:00Z", endsAt: "2026-08-15T03:30:00Z" }, lines: [{ menuId: ids.menu, menuName: "오트 라떼", quantity: 2, optionNames: ["ICE"], lineTotalKrw: 12_800 }, { menuId: secondLine.menuId, menuName: "아이스 아메리카노", quantity: 1, optionNames: ["ICE"], lineTotalKrw: 3_800 }], pricing: { subtotalKrw: 16_600, couponDiscountKrw: 0, pointsAppliedKrw: 0, payableKrw: 16_600, currency: "KRW" }, guarantee: "NONE" };

const meta = {
  title: "Pages/Refresh/Customer/Cart",
  component: RefreshCartPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen", docs: { story: { inline: false, height: "844px" } },
    routing: { path: "/app/cart", initialEntry: "/app/cart", surface: "refresh-customer" },
    msw: { handlers: [...signedInHandlers, ...storeIdentityHandlers, ...catalogHandlers, http.post("/api/v1/me/order-quotes", () => HttpResponse.json(quote))] },
  },
  beforeEach: () => { cart.clear(); cart.add({ storeId: ids.store, storeName: "시청점" }, line); cart.add({ storeId: ids.store, storeName: "시청점" }, secondLine); },
} satisfies Meta<typeof RefreshCartPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithItems: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("시청점에서 픽업합니다.")).toBeVisible();
    await userEvent.click(await canvas.findByRole("button", { name: /7잔 가능/ }));
    await expect(await canvas.findByRole("button", { name: /16,600.*주문하기/ })).toBeEnabled();
    await expect(canvas.getByText("결제 금액")).toBeVisible();
  },
};

export const Empty: Story = {
  beforeEach: () => cart.clear(),
  play: async ({ canvas }) => { await expect(await canvas.findByText("담은 메뉴가 없어요")).toBeVisible(); },
};

export const Corrupt: Story = {
  beforeEach: () => { cart.clear(); localStorage.setItem(CART_STORAGE_KEY, "{not json"); },
  play: async ({ canvas }) => { await expect(await canvas.findByText("장바구니 정보를 읽지 못했어요")).toBeVisible(); },
};
