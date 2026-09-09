import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, fn } from "storybook/test";
import { HttpResponse, http } from "msw";
import { catalogHandlers, orderDetail, orderDetailHandlers, pointsHandlers, signedInHandlers } from "../../../.storybook/fixtures";
import { RefreshCustomerOrderDetailPage } from "./CustomerTransactionPages";

const reorderRequest = fn();

const meta = {
  title: "Pages/Refresh/Customer/Order detail",
  component: RefreshCustomerOrderDetailPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen", docs: { story: { inline: false, height: "960px" } },
    routing: { path: "/app/orders/:orderReference", initialEntry: `/app/orders/${orderDetail.orderReference}`, surface: "refresh-customer" },
    msw: { handlers: [...signedInHandlers, ...orderDetailHandlers()] },
  },
} satisfies Meta<typeof RefreshCustomerOrderDetailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ReadyForPickup: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("픽업할 준비가 끝났어요")).toBeVisible();
    await expect(await canvas.findAllByText("A-142")).toHaveLength(1);
    await expect(await canvas.findByRole("heading", { name: "거래 요약" })).toBeVisible();
    await expect(canvas.queryByText(/QR/)).not.toBeInTheDocument();
    await expect(canvas.queryByRole("button", { name: "주문 취소" })).not.toBeInTheDocument();
  },
};

export const CancellableBeforeAcceptance: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, ...orderDetailHandlers({ status: "PAID", allowedActions: ["CANCEL"], cancellationPreview: { estimate: true, cashRefundAmountKrw: 12_800, restoredPoints: 1_000 } })] } },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "주문 취소" }));
    await expect(await canvas.findByRole("region", { name: "주문 취소" })).toBeVisible();
    await expect(canvas.getByText("예상 현금 환불")).toBeVisible();
  },
};

export const Cancelled: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, ...orderDetailHandlers({ status: "CANCELLED", allowedActions: [] })] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("취소된 주문이에요")).toBeVisible();
    await expect(canvas.queryByText("픽업 번호")).not.toBeInTheDocument();
  },
};

export const RefundDelayed: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, ...orderDetailHandlers({ status: "CANCELLED", allowedActions: ["VIEW_REFUND"], paymentRecovery: { state: "PROCESSING", noticeCode: "REFUND_DELAYED", cancellationRequestedRefundAmountKrw: 12_800 } })] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "환불 내역" })).toBeVisible();
    await expect(await canvas.findByText("환불 확인이 지연되고 있어요")).toBeVisible();
    await expect(canvas.getByText("요청 금액 ₩12,800")).toBeVisible();
    await expect(canvas.getByText(/같은 요청을 다시 보내지 않아도/)).toBeVisible();
  },
};

export const RefundCompleted: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, ...orderDetailHandlers({ status: "CANCELLED", allowedActions: ["VIEW_REFUND"], paymentRecovery: { state: "SUCCEEDED", cancellationRequestedRefundAmountKrw: 12_800, remainingRefundableAmountKrw: 0, lastUpdatedAt: "2026-08-15T03:12:00Z" } })] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "환불 내역" })).toBeVisible();
    await expect(canvas.getByText("환불이 완료됐어요")).toBeVisible();
    await expect(canvas.getByText("요청 금액 ₩12,800")).toBeVisible();
  },
};

export const PermissionFailure: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, http.get("/api/v1/me/orders/:orderReference", () => HttpResponse.json({ code: "ORDER_ACCESS_DENIED", message: "이 주문을 볼 수 없습니다.", correlationId: "REQ-ORDER-403" }, { status: 403 }))] } },
  play: async ({ canvas }) => { await expect(await canvas.findByRole("alert")).toBeVisible(); },
};

export const ReorderFromHistory: Story = {
  tags: ["!autodocs"],
  beforeEach: () => { reorderRequest.mockClear(); },
  parameters: {
    routing: { path: "/app/orders/:orderReference", initialEntry: `/app/orders/${orderDetail.orderReference}?reorder=1`, surface: "refresh-customer" },
    msw: { handlers: [...signedInHandlers, ...pointsHandlers, ...catalogHandlers, ...orderDetailHandlers({ status: "COMPLETED", allowedActions: ["REORDER"] }), http.post("/api/v1/me/orders/:orderReference/reorders", async ({ request }) => { const body = await request.json() as { pointsToUseKrw: number }; reorderRequest(body); return HttpResponse.json({ code: "POINT_BALANCE_INSUFFICIENT", message: `요청한 ${body.pointsToUseKrw}P를 현재 사용할 수 없어요.` }, { status: 409 }); })] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("region", { name: "다시 주문" })).toBeVisible();
    await userEvent.click(await canvas.findByRole("radio", { name: /7잔 가능/ }));
    await expect(await canvas.findByText("1,500P")).toBeVisible();
    await userEvent.type(canvas.getByRole("textbox", { name: "사용할 포인트" }), "500");
    await userEvent.click(canvas.getByRole("button", { name: "이 시간으로 주문" }));
    await expect(await canvas.findByRole("alert")).toHaveTextContent("포인트");
    await expect(reorderRequest).toHaveBeenCalledWith(expect.objectContaining({ pointsToUseKrw: 500 }));
  },
};
