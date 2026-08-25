import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { catalogHandlers, ids, signedInHandlers, storeIdentityHandlers } from "../../../.storybook/fixtures";
import { CART_STORAGE_KEY, cart } from "./cart";
import { CartPage } from "./CartPage";

const line = {
  menuId: ids.menu,
  optionIds: [],
  quantity: 2,
  display: { menuName: "오트 라떼", optionNames: ["ICE"], unitPriceKrw: 6_400 },
};

const meta = {
  title: "Pages/Customer/Cart",
  component: CartPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: {
        component:
          "한 브라우저의 한 매장 장바구니입니다. 금액은 표시용이고 최종 금액·재고·픽업 가능 여부는 주문할 때 서버가 다시 정합니다.",
      },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/cart", initialEntry: "/app/cart" },
    msw: { handlers: [...signedInHandlers, ...storeIdentityHandlers, ...catalogHandlers] },
  },
} satisfies Meta<typeof CartPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithItems: Story = {
  beforeEach: () => {
    cart.clear();
    cart.add({ storeId: ids.store, storeName: "시청점" }, line);
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("시청점에서 픽업합니다.")).toBeVisible();
    await expect(await canvas.findByText("영업 중")).toBeVisible();
    await expect(await canvas.findByText(/가장 빠른 픽업/)).toBeVisible();
    await expect(canvas.getByRole("button", { name: /주문하기/ })).toBeDisabled();
  },
};

export const Empty: Story = {
  tags: ["!autodocs"],
  beforeEach: () => {
    cart.clear();
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("담은 메뉴가 없어요")).toBeVisible();
  },
};

/** A cart this browser cannot decode is reported, never silently replaced with an empty one. */
export const Corrupt: Story = {
  tags: ["!autodocs"],
  beforeEach: () => {
    cart.clear();
    localStorage.setItem(CART_STORAGE_KEY, "{not json");
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("장바구니 정보를 읽지 못했어요")).toBeVisible();
  },
};

function conflictStory(code: string, message: string, expected: string): Story {
  return {
    tags: ["!autodocs"],
    parameters: {
      msw: {
        handlers: [
          ...signedInHandlers,
          ...storeIdentityHandlers,
          ...catalogHandlers,
          http.post("/api/v1/orders", () => HttpResponse.json({ code, message }, { status: 409 })),
        ],
      },
    },
    beforeEach: () => {
      cart.clear();
      cart.add({ storeId: ids.store, storeName: "시청점" }, line);
      document.cookie = "BEANFLOW_CUSTOMER_XSRF=storybook-customer-csrf; path=/";
    },
    play: async ({ canvas }) => {
      await userEvent.click(await canvas.findByRole("button", { name: /7잔 가능/ }));
      await userEvent.click(canvas.getByRole("button", { name: /주문하기/ }));
      await expect(await canvas.findByText(expected)).toBeVisible();
    },
  };
}

export const PriceConfigurationChanged = conflictStory(
  "MENU_CONFIGURATION_NOT_AVAILABLE",
  "메뉴 가격 또는 옵션 구성이 변경되었습니다.",
  "지금 주문할 수 없는 메뉴 구성이에요",
);

export const StockChanged = conflictStory(
  "STOCK_NOT_AVAILABLE",
  "오트 라떼 재고가 부족합니다.",
  "재고가 부족한 메뉴가 있어요",
);
