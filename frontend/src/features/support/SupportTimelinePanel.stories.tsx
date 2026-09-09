import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import type { components } from "../../api/schema";
import { SupportTimelinePanel } from "./SupportTimelinePanel";

type Timeline = components["schemas"]["SupportTimelinePage"];
type Type = components["schemas"]["SupportTimelineType"];
type State = components["schemas"]["SupportTimelineState"];

const facts: Array<[Type, State]> = [
  ["ORDER_STATE", "COMPLETED"], ["ORDER_STATE", "ACCEPTED"], ["NOTIFICATION_DELIVERY", "ACCEPTED"],
  ["REFUND_STATE", "UNKNOWN"], ["REFUND_STATE", "RECONCILING"], ["REFUND_STATE", "MANUAL_REVIEW"],
  ["CASE_STATE", "PENDING_CUSTOMER"], ["CASE_ASSIGNMENT", "ASSIGNED"], ["CASE_INTERACTION", "INBOUND"],
  ["CASE_NOTE", "RECORDED"], ["SUBJECT_LINK", "UNLINKED"], ["POINT_RESERVATION", "RELEASED"],
  ["COUPON_RESERVATION", "USED"], ["PICKUP_RESERVATION", "CONFIRMED"], ["PAYMENT_STATE", "APPROVED"],
  ["SETTLEMENT_ITEM", "ITEM_CREATED"], ["SETTLEMENT_ADJUSTMENT", "ADJUSTMENT_RECORDED"], ["OPERATION_AUDIT", "RECORDED"],
];
const timeline: Timeline = {
  items: facts.map(([type, state], index) => ({
    itemId: `a8000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
    source: type === "ORDER_STATE" ? "ORDERING" : type === "NOTIFICATION_DELIVERY" ? "NOTIFICATION" : type === "REFUND_STATE" || type === "PAYMENT_STATE" ? "PAYMENT" : type === "POINT_RESERVATION" ? "LOYALTY" : type === "COUPON_RESERVATION" ? "PROMOTION" : type === "PICKUP_RESERVATION" ? "FULFILLMENT" : type.startsWith("SETTLEMENT_") ? "SETTLEMENT" : type === "OPERATION_AUDIT" ? "OPERATIONS" : "SUPPORT",
    type, state, summary: `${type}:${state}`, amountKrw: type === "ORDER_STATE" ? 7500 : null,
    occurredAt: "2026-08-23T09:30:00Z",
  })),
  nextCursor: null,
};

const meta = {
  title: "Patterns/Support/Timeline",
  component: SupportTimelinePanel,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" }, docs: { description: { component: "서버가 반환하는 type/state와 machine summary로 업무별 표시 문구를 검증합니다. summary를 사용자 문구로 가정하지 않습니다." } } },
  args: { timeline },
} satisfies Meta<typeof SupportTimelinePanel>;
export default meta;
type Story = StoryObj<typeof meta>;

export const ServerFacts: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByText("주문 픽업 완료")).toBeVisible();
    await expect(canvas.getByText("주문 접수")).toBeVisible();
    await expect(canvas.getByText("알림 발송 접수")).toBeVisible();
    await expect(canvas.getByText("환불 결과 확인 중")).toBeVisible();
    await expect(canvas.getByText("환불 결과 재확인 중")).toBeVisible();
    await expect(canvas.getByText("환불 운영팀 확인 필요")).toBeVisible();
    for (const item of timeline.items) await expect(canvas.queryByText(item.summary)).not.toBeInTheDocument();
  },
};

export const UnsupportedServerState: Story = {
  args: { timeline: { items: [{ ...timeline.items[0]!, state: "FUTURE_STATE" as State, summary: "ORDER_STATE:FUTURE_STATE" }], nextCursor: null } },
  play: async ({ canvas }) => {
    await expect(canvas.getByText("주문 상태 확인 필요")).toBeVisible();
    await expect(canvas.queryByText(/FUTURE_STATE/)).not.toBeInTheDocument();
    await expect(canvas.queryByText("주문 픽업 완료")).not.toBeInTheDocument();
  },
};
