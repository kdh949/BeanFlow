import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { catalogHandlers, customerStore, favoriteHandlers, ids, signedInHandlers, storeIdentityHandlers } from "../../../.storybook/fixtures";
import { cart } from "../../features/ordering/cart";
import { RefreshStoreDetailPage } from "./CustomerCommercePages";

let saved = false;
const meta = {
  title: "Pages/Refresh/Customer/Store detail",
  component: RefreshStoreDetailPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen", docs: { story: { inline: false, height: "844px" } },
    routing: { path: "/app/stores/:storeId", initialEntry: `/app/stores/${ids.store}`, surface: "refresh-customer" },
    msw: { handlers: [...signedInHandlers, ...favoriteHandlers, ...storeIdentityHandlers, ...catalogHandlers] },
  },
  beforeEach: () => cart.clear(),
} satisfies Meta<typeof RefreshStoreDetailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Orderable: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "시청점" })).toBeVisible();
    await expect(await canvas.findByRole("button", { name: "시청점 즐겨찾기 추가" })).toBeVisible();
    await expect(canvas.getByText("장바구니에서 시간을 선택해 주세요.")).toBeVisible();
    await userEvent.click(await canvas.findByRole("button", { name: /오트 라떼/ }));
    await userEvent.click(canvas.getByRole("button", { name: /6,400.*담기/ }));
    await expect(await canvas.findByText("장바구니에 담았어요.")).toBeVisible();
    await expect(await canvas.findByRole("link", { name: /장바구니 1개 보기/ })).toBeVisible();
  },
};

export const PickupUnavailable: Story = {
  parameters: {
    msw: { handlers: [...signedInHandlers, ...favoriteHandlers, ...storeIdentityHandlers, http.get("/api/v1/stores/:storeId/menus", () => HttpResponse.json({ items: [] })), http.get("/api/v1/stores/:storeId/pickup-slots", () => HttpResponse.json({ items: [] }))] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("지금은 픽업 시간이 모두 마감됐어요.")).toBeVisible();
    await expect(await canvas.findByText("판매 중인 메뉴가 없어요")).toBeVisible();
  },
};

export const SaveFavorite: Story = {
  beforeEach: () => { saved = false; },
  parameters: { msw: { handlers: [http.get("/api/v1/me/favorite-stores", () => HttpResponse.json({ items: saved ? [customerStore] : [] })), http.put("/api/v1/me/favorite-stores/:storeId", () => { saved = true; return new HttpResponse(null, { status: 204 }); }), http.delete("/api/v1/me/favorite-stores/:storeId", () => { saved = false; return new HttpResponse(null, { status: 204 }); }), ...meta.parameters.msw.handlers] } },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "시청점 즐겨찾기 추가" }));
    await expect(await canvas.findByRole("button", { name: "시청점 즐겨찾기 해제" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(canvas.getByRole("button", { name: "시청점 즐겨찾기 해제" }));
    await expect(await canvas.findByRole("button", { name: "시청점 즐겨찾기 추가" })).toHaveAttribute("aria-pressed", "false");
  },
};
