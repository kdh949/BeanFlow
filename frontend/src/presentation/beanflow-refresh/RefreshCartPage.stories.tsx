import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, http } from "msw";
import { catalogHandlers, ids, pointsHandlers, signedInHandlers, storeIdentityHandlers } from "../../../.storybook/fixtures";
import { CART_STORAGE_KEY, cart } from "../../features/ordering/cart";
import { RefreshCartPage } from "./CustomerCommercePages";

const line = { menuId: ids.menu, optionIds: [], quantity: 2, display: { menuName: "오트 라떼", optionNames: [], unitPriceKrw: 6_400, imageUrl: "/demo/catalog/cafe-latte.webp" } };
const secondLine = { menuId: "20000000-0000-4000-8000-000000000002", optionIds: [], quantity: 1, display: { menuName: "카페라떼", optionNames: [], unitPriceKrw: 5_000, imageUrl: "/demo/catalog/caramel-macchiato.webp" } };
const quote = { quotedAt: "2026-08-15T03:10:00Z", quoteFingerprint: "a".repeat(64), store: { storeId: ids.store, name: "시청점" }, pickupWindow: { startsAt: "2026-08-15T03:20:00Z", endsAt: "2026-08-15T03:30:00Z" }, lines: [{ menuId: ids.menu, menuName: "오트 라떼", quantity: 2, optionNames: [], lineTotalKrw: 12_800 }, { menuId: secondLine.menuId, menuName: "카페라떼", quantity: 1, optionNames: [], lineTotalKrw: 5_000 }], pricing: { subtotalKrw: 17_800, couponDiscountKrw: 0, pointsAppliedKrw: 0, payableKrw: 17_800, currency: "KRW" }, guarantee: "NONE" };

const meta = {
  title: "Pages/Refresh/Customer/Cart",
  component: RefreshCartPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen", docs: { story: { inline: false, height: "844px" } },
    routing: { path: "/app/cart", initialEntry: "/app/cart", surface: "refresh-customer" },
    msw: { handlers: [...signedInHandlers, ...pointsHandlers, ...storeIdentityHandlers, ...catalogHandlers, http.post("/api/v1/me/order-quotes", async ({ request }) => {
      const body = await request.json() as { pointsToUseKrw: number; lines: Array<{ menuId: string; quantity: number; optionIds: string[] }> };
      const lines = body.lines.map((item) => { const menu = item.menuId === line.menuId ? line : secondLine; const added = item.optionIds.includes("extra-shot") ? 500 : 0; return { menuId: item.menuId, menuName: menu.display.menuName, quantity: item.quantity, optionNames: added ? ["샷 추가"] : [], lineTotalKrw: (menu.display.unitPriceKrw + added) * item.quantity }; });
      const subtotalKrw = lines.reduce((total, item) => total + item.lineTotalKrw, 0);
      return HttpResponse.json({ ...quote, lines, pricing: { ...quote.pricing, subtotalKrw, pointsAppliedKrw: body.pointsToUseKrw, payableKrw: subtotalKrw - body.pointsToUseKrw } });
    })] },
  },
  beforeEach: () => { cart.clear(); cart.add({ storeId: ids.store, storeName: "시청점" }, line); cart.add({ storeId: ids.store, storeName: "시청점" }, secondLine); },
} satisfies Meta<typeof RefreshCartPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithItems: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("시청점")).toBeVisible();
    await userEvent.click(await canvas.findByRole("radio", { name: /7잔 가능/ }));
    await expect(await canvas.findByRole("button", { name: /17,800.*주문하기/ })).toBeEnabled();
    await expect(canvas.getByText("결제 금액")).toBeVisible();
  },
};

export const Empty: Story = {
  tags: ["!autodocs"],
  beforeEach: () => cart.clear(),
  play: async ({ canvas }) => { await expect(await canvas.findByText("담은 메뉴가 없어요")).toBeVisible(); },
};

export const Corrupt: Story = {
  tags: ["!autodocs"],
  beforeEach: () => { cart.clear(); localStorage.setItem(CART_STORAGE_KEY, "{not json"); },
  play: async ({ canvas }) => { await expect(await canvas.findByText("장바구니 정보를 읽지 못했어요")).toBeVisible(); },
};

export const PointsAndRemoval: Story = {
  tags: ["!autodocs"],
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("radio", { name: /7잔 가능/ }));
    await expect(await canvas.findByRole("button", { name: /17,800.*주문하기/ })).toBeEnabled();
    await userEvent.click(await canvas.findByRole("button", { name: "전액 사용" }));
    await expect(await canvas.findByRole("button", { name: /16,300.*주문하기/ })).toBeEnabled();
    await expect(canvas.getByRole("textbox", { name: "사용할 포인트" })).toHaveValue("1500");
    await userEvent.clear(canvas.getByRole("textbox", { name: "사용할 포인트" }));
    await userEvent.type(canvas.getByRole("textbox", { name: "사용할 포인트" }), "1501");
    await expect(await canvas.findByText("보유 포인트를 초과했어요.")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "견적 확인 후 주문하기" })).toBeDisabled();
    await userEvent.click(canvas.getByRole("button", { name: "사용 안 함" }));
    await expect(await canvas.findByRole("button", { name: /17,800.*주문하기/ })).toBeEnabled();
    await userEvent.click(canvas.getByRole("button", { name: "카페라떼 삭제" }));
    await waitFor(() => expect(cart.read()).toMatchObject({ status: "ready", cart: { lines: [line] } }));
    await expect(await canvas.findByRole("button", { name: /12,800.*주문하기/ })).toBeEnabled();
    await expect(canvas.getByRole("link", { name: "메뉴 더 담기" })).toHaveAttribute("href", `/app/stores/${ids.store}`);
  },
};

export const PointsUnavailable: Story = {
  tags: ["!autodocs"],
  parameters: { msw: { handlers: [http.get("/api/v1/me/points", () => HttpResponse.json({ code: "POINT_ACCOUNT_INTEGRITY_FAILURE", message: "포인트를 확인할 수 없습니다." }, { status: 503 })), ...meta.parameters.msw.handlers] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("region", { name: "포인트 사용" })).toHaveTextContent("포인트");
    await expect(await canvas.findByRole("button", { name: "다시 시도" })).toBeVisible();
    await expect(canvas.getByRole("textbox", { name: "사용할 포인트" })).toBeDisabled();
    await expect(canvas.queryByText("사용 가능 0P")).not.toBeInTheDocument();
  },
};

export const EditOptions: Story = {
  tags: ["!autodocs"],
  parameters: { msw: { handlers: [http.get("/api/v1/stores/:storeId/menus", () => HttpResponse.json({ items: [{ menuId: ids.menu, name: "오트 라떼", basePriceKrw: 6400, available: true, options: [{ optionId: "extra-shot", name: "샷 추가", additionalPriceKrw: 500, available: true }] }] })), ...meta.parameters.msw.handlers] } },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("radio", { name: /7잔 가능/ }));
    await expect(await canvas.findByRole("button", { name: /17,800.*주문하기/ })).toBeEnabled();
    await userEvent.click(await canvas.findByRole("button", { name: "오트 라떼 옵션 변경" }));
    await userEvent.click(await canvas.findByRole("checkbox", { name: /샷 추가/ }));
    await userEvent.click(canvas.getByRole("button", { name: "옵션 적용" }));
    await expect(await canvas.findByText("샷 추가")).toBeVisible();
    await expect(await canvas.findByRole("button", { name: /18,800.*주문하기/ })).toBeEnabled();
    await expect(cart.read()).toMatchObject({ status: "ready", cart: { lines: [expect.objectContaining({ optionIds: ["extra-shot"], quantity: 2, display: expect.objectContaining({ unitPriceKrw: 6900 }) }), secondLine] } });
  },
};
