import { storeSelectionHandler } from "../../../.storybook/storeSelectionFixtures";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { http, HttpResponse } from "msw";
import { OperationsRefundWorkspace } from "./OperationsRefundWorkspace";
const orderReference = "BF-7K3M-9Q2P";
const path = "/api/v1/operations/stores/:storeId/orders/:orderReference";
const preview = http.post(`${path}/refund-previews`, async ({ request }) => {
  const body = await request.json() as { lines?: { lineSequence: number; quantity: number }[] };
  const quantity = body.lines?.[0]?.quantity ?? 0;
  return HttpResponse.json({ orderReference, orderContext: { orderedAt: "2026-09-10T02:50:00Z", pickupWindow: { startsAt: "2026-09-10T03:20:00Z", endsAt: "2026-09-10T03:30:00Z" }, status: "COMPLETED", pricing: { subtotalKrw: 8000, couponDiscountKrw: 400, pointsAppliedKrw: 0, payableKrw: 7600, currency: "KRW" }, paymentKind: "ONE_TIME_EXTERNAL" }, lines: [{ lineSequence: 0, menuName: "아이스 아메리카노", selectedQuantity: quantity, remainingQuantity: 2, grossAttributionKrw: 4000 * quantity, couponAttributionKrw: 200 * quantity, pointsRestorationKrw: 0, cashRefundKrw: 3800 * quantity }], totals: { grossAttributionKrw: 4000 * quantity, couponAttributionKrw: 200 * quantity, pointsRestorationKrw: 0, cashRefundKrw: 3800 * quantity, currency: "KRW" }, previewVersion: "a".repeat(64) });
});
const result = { orderReference, state: "SUCCEEDED", cashRefundRequestedKrw: 3800, cashRefundedKrw: 3800, pointsRestorationRequestedKrw: 0, pointsRestorationState: "NOT_REQUIRED", currency: "KRW", createdAt: "2026-09-10T03:00:00Z", updatedAt: "2026-09-10T03:00:05Z", correlationId: "REFUND-REVIEW" };
const meta = { title: "Patterns/Operations/Refund", component: OperationsRefundWorkspace, tags: ["autodocs"], parameters: { a11y: { test: "error" }, msw: { handlers: [storeSelectionHandler, preview] }, docs: { story: { inline: false, height: "900px" }, description: { component: "운영 권한으로 같은 서버 미리보기·품목 선택·멱등성 환불 화면을 사용합니다." } } } } satisfies Meta<typeof OperationsRefundWorkspace>;
export default meta; type Story = StoryObj<typeof meta>;
const lookup: NonNullable<Story["play"]> = async ({ canvas }) => {
  await userEvent.click(canvas.getByRole("button", { name: "매장 찾기" })); await userEvent.click(await canvas.findByRole("button", { name: "빈플로우 성수점 선택" }));
  await userEvent.type(canvas.getByLabelText("환불 대상 주문 번호"), orderReference);
  await userEvent.click(canvas.getByRole("button", { name: "환불 대상 확인" }));
  await expect(await canvas.findByText("아이스 아메리카노")).toBeVisible();
};
const prepare: NonNullable<Story["play"]> = async context => {
  await lookup(context);
  await userEvent.click(context.canvas.getByRole("button", { name: "아이스 아메리카노 환불 수량 늘리기" }));
  await expect(await context.canvas.findAllByText("₩3,800")).toHaveLength(2);
  await userEvent.type(context.canvas.getByLabelText("환불 사유"), "고객 문의 확인 후 품목 환불");
};
export const Refund: Story = { parameters: { msw: { handlers: [storeSelectionHandler, preview, http.post(`${path}/refunds`, async ({ request }) => { expect(await request.json()).toEqual({ lines: [{ lineSequence: 0, quantity: 1 }], previewVersion: "a".repeat(64), reason: "고객 문의 확인 후 품목 환불" }); expect(request.headers.get("Idempotency-Key")).toBeTruthy(); return HttpResponse.json(result); })] } }, play: async context => { await prepare(context); await userEvent.click(context.canvas.getByRole("button", { name: /부분 환불 실행/ })); await expect(await context.canvas.findByText("현금 환불이 확인되었습니다")).toBeVisible(); await expect(context.canvas.queryByRole("link", { name: "주문 관리로" })).not.toBeInTheDocument(); } };
export const Unknown: Story = { parameters: { msw: { handlers: [storeSelectionHandler, preview, http.post(`${path}/refunds`, () => HttpResponse.json({ ...result, state: "UNKNOWN", cashRefundedKrw: undefined }, { status: 202 }))] } }, play: async context => { await prepare(context); await userEvent.click(context.canvas.getByRole("button", { name: /부분 환불 실행/ })); await expect(await context.canvas.findByText("환불 결과를 확인하고 있습니다")).toBeVisible(); await expect(context.canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled(); } };
export const Stale: Story = { parameters: { msw: { handlers: [storeSelectionHandler, preview, http.post(`${path}/refunds`, () => HttpResponse.json({ code: "REFUND_PREVIEW_STALE", correlationId: "STALE-REFUND" }, { status: 409 }))] } }, play: async context => { await prepare(context); await userEvent.click(context.canvas.getByRole("button", { name: /부분 환불 실행/ })); await expect(await context.canvas.findByText(/새 금액을 확인한 뒤/)).toBeVisible(); } };
export const LostResponse: Story = { play: async context => { await prepare(context); let first: unknown; context.msw.use(http.post(`${path}/refunds`, async ({ request }) => { first = { key: request.headers.get("Idempotency-Key"), body: await request.json() }; return HttpResponse.error(); })); await userEvent.click(context.canvas.getByRole("button", { name: /부분 환불 실행/ })); await expect(await context.canvas.findByRole("button", { name: "같은 요청 결과 확인" })).toBeVisible(); context.msw.use(http.post(`${path}/refunds`, async ({ request }) => { expect({ key: request.headers.get("Idempotency-Key"), body: await request.json() }).toEqual(first); return HttpResponse.json(result); })); await userEvent.click(context.canvas.getByRole("button", { name: "같은 요청 결과 확인" })); await expect(await context.canvas.findByText("현금 환불이 확인되었습니다")).toBeVisible(); } };
export const Forbidden: Story = { parameters: { msw: { handlers: [storeSelectionHandler, http.post(`${path}/refund-previews`, () => HttpResponse.json({ code: "ACCESS_DENIED", correlationId: "REFUND-DENIED" }, { status: 403 }))] } }, play: async ({ canvas }) => { await userEvent.click(canvas.getByRole("button", { name: "매장 찾기" })); await userEvent.click(await canvas.findByRole("button", { name: "빈플로우 성수점 선택" })); await userEvent.type(canvas.getByLabelText("환불 대상 주문 번호"), orderReference); await userEvent.click(canvas.getByRole("button", { name: "환불 대상 확인" })); await expect(await canvas.findByText("이 작업을 진행할 수 없습니다")).toBeVisible(); await expect(canvas.queryByRole("button", { name: /부분 환불 실행/ })).not.toBeInTheDocument(); } };
