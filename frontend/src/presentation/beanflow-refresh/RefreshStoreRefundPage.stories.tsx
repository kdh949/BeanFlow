import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { RefreshStoreRefundPage } from "./MerchantPages";

const storeId = "10000000-0000-4000-8000-000000000001";
const orderReference = "BF-7K3M-9Q2P";
const storeMembershipHandler = http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([{ storeId, storeName: "시청점", membershipRole: "OWNER" }]));
const merchantFrameHandlers = [...merchantSignedInHandlers, storeMembershipHandler];
function line(overrides: Record<string, unknown> = {}) { return { lineSequence: 0, menuName: "아이스 아메리카노", selectedQuantity: 0, remainingQuantity: 2, grossAttributionKrw: 0, couponAttributionKrw: 0, pointsRestorationKrw: 0, cashRefundKrw: 0, ...overrides }; }
function preview(overrides: Record<string, unknown> = {}) { return { orderReference, orderContext: { orderedAt: "2026-08-15T02:50:00Z", pickupWindow: { startsAt: "2026-08-15T03:20:00Z", endsAt: "2026-08-15T03:30:00Z" }, status: "PAID", pricing: { subtotalKrw: 12_800, couponDiscountKrw: 1_000, pointsAppliedKrw: 2_000, payableKrw: 9_800, currency: "KRW" }, paymentKind: "ONE_TIME_EXTERNAL" }, lines: [line(), line({ lineSequence: 1, menuName: "오트 라떼", remainingQuantity: 1 }), line({ lineSequence: 2, menuName: "베이컨 치즈 샌드위치", remainingQuantity: 1 }), line({ lineSequence: 3, menuName: "초코 케이크", remainingQuantity: 1 })], totals: { grossAttributionKrw: 0, couponAttributionKrw: 0, pointsRestorationKrw: 0, cashRefundKrw: 0, currency: "KRW" }, previewVersion: "a".repeat(64), ...overrides }; }
function previewHandler(body: Record<string, unknown> = preview()) { return http.post("/api/v1/stores/:storeId/orders/:orderReference/refund-previews", () => HttpResponse.json(body)); }
const selected = preview({ lines: [line({ selectedQuantity: 1, cashRefundKrw: 3_800, couponAttributionKrw: 200, grossAttributionKrw: 4_000 }), line({ lineSequence: 1, menuName: "오트 라떼", remainingQuantity: 1 })], totals: { grossAttributionKrw: 4_000, couponAttributionKrw: 200, pointsRestorationKrw: 0, cashRefundKrw: 3_800, currency: "KRW" } });
function outcome(state: "UNKNOWN" | "RECONCILING" | "MANUAL_REVIEW") { return http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", () => HttpResponse.json({ orderReference, state, cashRefundRequestedKrw: 3_800, pointsRestorationRequestedKrw: 0, pointsRestorationState: "NOT_REQUIRED", currency: "KRW", createdAt: "2026-08-17T03:00:00Z", updatedAt: "2026-08-17T03:00:05Z", correlationId: `REQ-REFUND-${state}` }, { status: 202 })); }

const meta = {
  title: "Pages/Refresh/Store/Item refund",
  component: RefreshStoreRefundPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen", docs: { story: { inline: false, height: "900px" } },
    routing: { path: "/store/refunds/:storeId/:orderReference", initialEntry: `/store/refunds/${storeId}/${orderReference}`, surface: "refresh-store" },
    msw: { handlers: [...merchantFrameHandlers, previewHandler()] },
  },
} satisfies Meta<typeof RefreshStoreRefundPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SelectableItems: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("아이스 아메리카노")).toBeVisible();
    await expect(canvas.getByRole("heading", { name: "환불 대상 주문" })).toBeVisible();
    await expect(canvas.queryByText(/고객 이름|전화번호|VAT|주문 채널/)).not.toBeInTheDocument();
    await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
  },
};

export const ServerCalculatedAmount: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected)] } },
  play: async ({ canvas }) => { await expect(await canvas.findAllByText("₩3,800")).toHaveLength(2); },
};

export const StalePreview: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected), http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", () => HttpResponse.json({ code: "REFUND_PREVIEW_STALE", message: "환불 상태가 변경되었습니다.", correlationId: "REQ-REFUND-409" }, { status: 409 }))] } },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByRole("alert")).toHaveTextContent(/새 금액을 확인/);
  },
};

export const UnknownOutcome: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected), outcome("UNKNOWN")] } },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByText("환불 결과를 확인하고 있습니다")).toBeVisible();
  },
};

export const ReconcilingOutcome: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected), outcome("RECONCILING")] } },
  play: UnknownOutcome.play,
};

export const ManualReviewOutcome: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected), outcome("MANUAL_REVIEW")] } },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByText("운영팀 확인이 필요합니다")).toBeVisible();
  },
};

export const NothingLeftToRefund: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(preview({ lines: [line({ remainingQuantity: 0 })] }))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/남은 환불 가능 수량이 없습니다/)).toBeVisible();
    await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
  },
};
