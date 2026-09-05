import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { CustomerCouponClaimsPage } from "./CustomerCouponClaimsPage";

const coupon = {
  campaignId: "campaign-demo-01",
  "title": "성수 웰컴 쿠폰",
  benefitLabel: "3,000원 할인",
  minimumOrderKrw: 12_000,
  claimEndsAt: "2026-09-10T23:59:59+09:00",
  expiresAt: "2026-09-30T23:59:59+09:00",
  remainingLabel: "오늘 84장 남음",
} as const;

const meta = {
  title: "Pages/Customer/Coupon claims",
  component: CustomerCouponClaimsPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "고객이 받을 수 있는 쿠폰을 확인하는 독립 화면입니다. 환불은 주문 내역과 주문 상세에서 확인합니다.",
      },
      story: { inline: false, height: "760px" },
    },
    routing: { path: "/app/coupon-claims", initialEntry: "/app/coupon-claims" },
  },
} satisfies Meta<typeof CustomerCouponClaimsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const CouponClaim: Story = {
  args: {
    scenario: "ready",
    couponOffers: [coupon],
    onClaimCoupon: async () => undefined,
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "쿠폰 받기" })).toBeVisible();
    await expect(canvas.queryByText(/환불/)).not.toBeInTheDocument();
    const claim = await canvas.findByRole("button", { name: "성수 웰컴 쿠폰 받기" });
    await userEvent.click(claim);
    await expect(canvas.getByRole("status")).toHaveTextContent("쿠폰을 받았어요");
  },
};

export const Empty: Story = {
  args: { scenario: "empty" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("지금 받을 수 있는 쿠폰이 없어요")).toBeVisible();
    await expect(canvas.queryByText(/환불/)).not.toBeInTheDocument();
  },
};

export const ContractPending: Story = {
  args: { scenario: "contract-pending" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toHaveTextContent("쿠폰 받기를 준비하고 있어요");
    await expect(canvas.queryByRole("button", { name: /쿠폰 받기/ })).not.toBeInTheDocument();
  },
};
