import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { http, HttpResponse } from "msw";
import { OperationsRecoveryPage } from "./OperationsRecoveryPage";
const meta = { title: "Pages/Operations/Recovery", component: OperationsRecoveryPage, tags: ["autodocs"], parameters: { a11y: { test: "error" }, routing: { path: "/ops/recovery", initialEntry: "/ops/recovery" }, msw: { handlers: [http.get("/api/v1/operations/payment-setup-recovery-cases", () => HttpResponse.json({ items: [], nextCursor: null })), http.get("/api/v1/operations/reprocessing-repair-proposals", () => HttpResponse.json({ items: [], nextCursor: null })), http.get("/api/v1/operations/notification-delivery-recoveries", () => HttpResponse.json({ items: [], nextCursor: null })), http.get("/api/v1/operations/event-publication-recoveries", () => HttpResponse.json({ items: [], nextCursor: null }))] }, docs: { description: { component: "알림과 이벤트 전달의 수동 복구를 구분합니다. 예시 수치나 미연결 정산/감사 탭을 표시하지 않습니다." }, story: { inline: false, height: "850px" } } } } satisfies Meta<typeof OperationsRecoveryPage>;
export default meta; type Story = StoryObj<typeof meta>;
export const RecoveryTabs: Story = { play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "알림 전달 복구" })).toBeVisible(); await userEvent.click(canvas.getByRole("tab", { name: "이벤트 전달" })); await expect(await canvas.findByRole("heading", { name: "이벤트 전달 복구" })).toBeVisible(); await expect(await canvas.findByText("복구 내역이 없습니다")).toBeVisible(); } };

export const OrderAndRepairTabs: Story = { play: async ({ canvas }) => { await userEvent.click(canvas.getByRole("tab", { name: "주문 후속 처리" })); await expect(await canvas.findByLabelText("후속 처리 주문 번호")).toBeVisible(); await userEvent.click(canvas.getByRole("tab", { name: "환불 복구 승인" })); await expect(await canvas.findByRole("heading", { name: "복구 제안 목록" })).toBeVisible(); } };

export const PointInvestigation: Story = { play: async ({ canvas }) => { await userEvent.click(canvas.getByRole("tab", { name: "포인트 조사" })); await expect(await canvas.findByLabelText("고객 로그인 아이디")).toBeVisible(); } };

export const RefundTab: Story = { play: async ({ canvas }) => { await userEvent.click(canvas.getByRole("tab", { name: "품목 환불" })); await expect(await canvas.findByLabelText("환불 대상 주문 번호")).toBeVisible(); } };

export const LostOrderRequestKeepsTab: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/operations/order-compensations/:reference", () => HttpResponse.json({ order: { orderId: "97000000-0000-4000-8000-000000000001", publicReference: "BF-7K9M-2P4R", storeId: "97000000-0000-4000-8000-000000000003", storeName: "성수점", state: "CANCELLED", createdAt: "2026-09-11T00:00:00Z" }, followUp: { compensation: { caseId: "97000000-0000-4000-8000-000000000002", trigger: "CUSTOMER_CANCELLATION", state: "MANUAL_REVIEW", updatedAt: "2026-09-11T00:00:00Z", benefitPolicies: [], steps: [{ type: "PAYMENT", state: "MANUAL_REVIEW", attemptCount: 4 }] } } })), http.post("/api/v1/operations/orders/:id/customer-cancellation-refund-reconciliations", () => HttpResponse.error()), http.get("/api/v1/operations/notification-delivery-recoveries", () => HttpResponse.json({ items: [], nextCursor: null }))] } },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("tab", { name: "주문 후속 처리" }));
    await userEvent.type(await canvas.findByLabelText("후속 처리 주문 번호"), "BF-7K9M-2P4R");
    await userEvent.selectOptions(canvas.getByLabelText("주문 후속 처리 조회 사유"), "ORDER_RECOVERY_REVIEW");
    await userEvent.click(canvas.getByRole("button", { name: "주문 후속 처리 조회" }));
    await userEvent.type(await canvas.findByLabelText("환불 결과 재확인 사유"), "기존 환불 확인");
    await userEvent.click(canvas.getByRole("button", { name: "기존 환불 결과 조회 예약" }));
    await expect(await canvas.findByRole("alert")).toBeVisible();
    await userEvent.click(canvas.getByRole("tab", { name: "환불 복구 승인" }));
    await expect(canvas.getByRole("tab", { name: "주문 후속 처리" })).toHaveAttribute("aria-selected", "true");
    await expect(canvas.getByRole("button", { name: "같은 환불 조회 예약 결과 확인" })).toBeVisible();
  },
};
