import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { delay, HttpResponse, http } from "msw";
import { catalogHandlers, ids, signedInHandlers, storeIdentityHandlers } from "../../../.storybook/fixtures";
import { CART_STORAGE_KEY, cart } from "./cart";
import { CartPage } from "./CartPage";

const line = {
  menuId: ids.menu,
  optionIds: [],
  quantity: 2,
  display: { menuName: "오트 라떼", optionNames: ["ICE"], unitPriceKrw: 6_400 },
};

const fingerprint = "a".repeat(64);
const quote = (payableKrw = 12_800, quoteFingerprint = fingerprint) => ({
  quotedAt: "2026-08-15T03:10:00Z",
  quoteFingerprint,
  store: { storeId: ids.store, name: "시청점" },
  pickupWindow: { startsAt: "2026-08-15T03:20:00Z", endsAt: "2026-08-15T03:30:00Z" },
  lines: [{ menuId: ids.menu, menuName: "오트 라떼", quantity: 2, optionNames: ["ICE"], lineTotalKrw: payableKrw }],
  pricing: { subtotalKrw: payableKrw, couponDiscountKrw: 0, pointsAppliedKrw: 0, payableKrw, currency: "KRW" },
  guarantee: "NONE",
});

const quoteHandler = http.post("/api/v1/me/order-quotes", () => HttpResponse.json(quote()));

const meta = {
  title: "Pages/Customer/Cart",
  component: CartPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: {
        component:
          "한 브라우저의 한 매장 장바구니입니다. 선택이 바뀔 때마다 서버 비예약 견적을 다시 받고, 확인한 fingerprint가 일치할 때만 주문합니다.",
      },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/cart", initialEntry: "/app/cart" },
    msw: { handlers: [...signedInHandlers, ...storeIdentityHandlers, ...catalogHandlers, quoteHandler] },
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
    await userEvent.click(await canvas.findByRole("button", { name: /7잔 가능/ }));
    await expect(await canvas.findByRole("button", { name: /12,800.*주문하기/ })).toBeEnabled();
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
          quoteHandler,
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
      await userEvent.click(await canvas.findByRole("button", { name: /12,800.*주문하기/ }));
      await expect(await canvas.findByText(expected)).toBeVisible();
    },
  };
}

export const QuoteLoading: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        ...storeIdentityHandlers,
        ...catalogHandlers,
        http.post("/api/v1/me/order-quotes", async () => {
          await delay("infinite");
          return HttpResponse.json(quote());
        }),
      ],
    },
  },
  beforeEach: () => {
    cart.clear();
    cart.add({ storeId: ids.store, storeName: "시청점" }, line);
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: /7잔 가능/ }));
    await expect(await canvas.findByText("현재 주문 금액을 확인하는 중")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "견적 확인 후 주문하기" })).toBeDisabled();
    await expect(canvas.queryByText("12,800원")).not.toBeInTheDocument();
  },
};

export const QuoteUnavailable: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        ...storeIdentityHandlers,
        ...catalogHandlers,
        http.post("/api/v1/me/order-quotes", () =>
          HttpResponse.json(
            { code: "DEPENDENCY_UNAVAILABLE", message: "견적 저장소를 사용할 수 없습니다.", correlationId: "REQ-QUOTE-42" },
            { status: 503 },
          )),
      ],
    },
  },
  beforeEach: () => {
    cart.clear();
    cart.add({ storeId: ids.store, storeName: "시청점" }, line);
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: /7잔 가능/ }));
    await expect(await canvas.findByText("견적 저장소를 사용할 수 없습니다.")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "견적 확인 후 주문하기" })).toBeDisabled();
  },
};

export const StaleQuoteReconfirmation: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        ...storeIdentityHandlers,
        ...catalogHandlers,
        quoteHandler,
        http.post("/api/v1/orders", () =>
          HttpResponse.json(
            {
              code: "ORDER_QUOTE_STALE",
              message: "Order quote changed",
              correlationId: "REQ-STALE-42",
              currentQuote: quote(13_000, "b".repeat(64)),
            },
            { status: 409 },
          )),
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
    await userEvent.click(await canvas.findByRole("button", { name: /12,800.*주문하기/ }));
    await expect(await canvas.findByText("주문 금액과 조건이 변경됐어요")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "견적 확인 후 주문하기" })).toBeDisabled();
    await userEvent.click(canvas.getByRole("button", { name: "변경 내용 확인" }));
    await expect(canvas.getByRole("button", { name: /13,000.*주문하기/ })).toBeEnabled();
  },
};

export const BenefitOnlyQuote: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        ...storeIdentityHandlers,
        ...catalogHandlers,
        http.post("/api/v1/me/order-quotes", () => HttpResponse.json({
          ...quote(12_800, "c".repeat(64)),
          pricing: { subtotalKrw: 12_800, couponDiscountKrw: 12_800, pointsAppliedKrw: 0, payableKrw: 0, currency: "KRW" },
        })),
      ],
    },
  },
  beforeEach: () => {
    cart.clear();
    cart.add({ storeId: ids.store, storeName: "시청점" }, line);
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: /7잔 가능/ }));
    await expect(await canvas.findByRole("button", { name: /₩0 주문하기/ })).toBeEnabled();
  },
};

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
