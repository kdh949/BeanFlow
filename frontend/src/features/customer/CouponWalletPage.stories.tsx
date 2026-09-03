import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, ids, pending, storeIdentityHandlers } from "../../../.storybook/fixtures";
import { couponSelection } from "./couponSelection";
import { CouponWalletPage } from "./CouponWalletPage";

const couponItems = [
  {
    couponIssuanceId: "a0000000-0000-4000-8000-000000000001",
    benefit: { discountType: "FIXED_KRW", fixedAmountKrw: 1_000 },
    minimumOrderKrw: 5_000,
    couponExpiresAt: "2026-09-01T00:00:00Z",
    applicable: true,
  },
  {
    couponIssuanceId: "a0000000-0000-4000-8000-000000000002",
    benefit: { discountType: "RATE_BPS", rateBps: 1_000, maximumDiscountKrw: 2_000 },
    minimumOrderKrw: 10_000,
    couponExpiresAt: "2026-09-03T00:00:00Z",
    applicable: false,
    reasonCode: "STORE_NOT_APPLICABLE",
  },
] as const;

const meta = {
  title: "Pages/Customer/Coupon wallet",
  component: CouponWalletPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component: "매장 문맥으로 활성 쿠폰을 조회하고, 서버 재검증 전까지 하나를 메모리에서 선택하는 고객 화면입니다.",
      },
      story: { inline: false, height: "720px" },
    },
    routing: { surface: "customer", path: "/app/coupons", initialEntry: `/app/coupons?storeId=${ids.store}` },
  },
  beforeEach: () => couponSelection.clear(),
} satisfies Meta<typeof CouponWalletPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ApplicableAndUnavailable: Story = {
  parameters: {
    msw: {
      handlers: [
        ...storeIdentityHandlers,
        http.get("/api/v1/me/coupons", () => HttpResponse.json({ items: couponItems, page: {} })),
      ],
    },
  },
  play: async ({ canvas }) => {
    const select = await canvas.findByRole("button", { name: /₩1,000 할인 쿠폰 선택/ });
    await expect(canvas.getByRole("button", { name: /이 매장에서는 사용할 수 없음/ })).toBeDisabled();
    await userEvent.click(select);
    await expect(canvas.getByRole("button", { name: /₩1,000 할인 선택됨/ })).toHaveAttribute("aria-pressed", "true");
    await expect(canvas.getByRole("status")).toHaveTextContent("주문에 적용할 예정");
  },
};

export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [
        ...storeIdentityHandlers,
        http.get("/api/v1/me/coupons", () => HttpResponse.json({ items: [], page: {} })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("사용할 수 있는 쿠폰이 없어요")).toBeVisible();
  },
};

export const Unavailable: Story = {
  parameters: { msw: { handlers: [...storeIdentityHandlers, apiError("/api/v1/me/coupons", 503, "COUPON_TERMS_INTEGRITY_FAILURE", "쿠폰 조건을 확인하지 못했습니다.")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toHaveTextContent("쿠폰 조건을 확인하지 못했습니다");
  },
};

export const Loading: Story = {
  parameters: { msw: { handlers: [...storeIdentityHandlers, pending("/api/v1/me/coupons")] } },
};

export const StoreSelectionRequired: Story = {
  parameters: {
    routing: { surface: "customer", path: "/app/coupons", initialEntry: "/app/coupons" },
    msw: { handlers: [] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("쿠폰을 사용할 매장을 골라주세요")).toBeVisible();
  },
};
