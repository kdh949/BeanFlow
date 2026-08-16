import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
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
