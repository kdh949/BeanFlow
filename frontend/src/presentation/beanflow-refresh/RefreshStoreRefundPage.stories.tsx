import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, http, delay } from "msw";
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
function outcome(state: "UNKNOWN" | "RECONCILING" | "MANUAL_REVIEW" | "SUCCEEDED" | "FAILED") { return http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", () => HttpResponse.json({ orderReference, state, cashRefundRequestedKrw: 3_800, pointsRestorationRequestedKrw: 0, pointsRestorationState: "NOT_REQUIRED", currency: "KRW", createdAt: "2026-08-17T03:00:00Z", updatedAt: "2026-08-17T03:00:05Z", correlationId: `REQ-REFUND-${state}` }, { status: 202 })); }

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
  play: async ({ canvas, canvasElement }) => {
    await expect(await canvas.findByText("아이스 아메리카노")).toBeVisible();
    await expect(canvas.getByRole("heading", { name: "환불 대상 주문" })).toBeVisible();
    await expect(canvas.getByText("주문 번호")).toBeVisible();
    await expect(canvas.getByText(orderReference)).toBeVisible();
    await expect(canvasElement.querySelector(".bfr-refund-context .context-label")).toBeNull();
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
    await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
    await expect(canvas.queryByText(/다시 보내도 새 환불/)).not.toBeInTheDocument();
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

export const FailedOutcome: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected), outcome("FAILED")] } },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByText("환불에 실패했습니다")).toBeVisible();
    await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
  },
};
export const RecalculationUnavailable: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, http.post("/api/v1/stores/:storeId/orders/:orderReference/refund-previews", async ({ request }) => {
    const body = await request.json() as { lines?: unknown[] };
    if (!body.lines?.length) return HttpResponse.json(selected);
    await delay(200);
    return HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", message: "금액을 계산하지 못했습니다." }, { status: 503 });
  })] } },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    await userEvent.click(canvas.getByRole("button", { name: "아이스 아메리카노 환불 수량 늘리기" }));
    await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
    await expect(await canvas.findByRole("alert")).toBeVisible();
    await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
  },
};

export const LostResponsePreservesRequest: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected), http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", () => HttpResponse.error())] } },
  play: async ({ canvas, msw }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    let firstRequest: { key: string | null; body: unknown } | undefined;
    msw.use(http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", async ({ request }) => {
      firstRequest = { key: request.headers.get("Idempotency-Key"), body: await request.json() };
      return HttpResponse.error();
    }));
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByRole("button", { name: "같은 요청 결과 확인" })).toBeEnabled();
    await expect(canvas.getByLabelText("환불 사유")).toBeDisabled();
    msw.use(http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", async ({ request }) => {
      await expect({ key: request.headers.get("Idempotency-Key"), body: await request.json() }).toEqual(firstRequest);
      return HttpResponse.json({ orderReference, state: "SUCCEEDED", cashRefundRequestedKrw: 3800, cashRefundedKrw: 3800, pointsRestorationRequestedKrw: 0, pointsRestorationState: "NOT_REQUIRED", currency: "KRW", createdAt: "2026-08-17T03:00:00Z", updatedAt: "2026-08-17T03:00:05Z", correlationId: "REQ-REFUND-REPLAY" });
    }));
    await userEvent.click(canvas.getByRole("button", { name: "같은 요청 결과 확인" }));
    await expect(await canvas.findByText("현금 환불이 확인되었습니다")).toBeVisible();
    await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
  },
};

export const ConcurrentUnresolvedRefund: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected), http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", () => HttpResponse.json({ code: "REFUND_OUTCOME_UNRESOLVED", message: "이전 환불 결과를 확인 중입니다." }, { status: 409 }))] } },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByRole("alert")).toBeVisible();
    await expect(canvas.queryByRole("button", { name: "같은 요청 결과 확인" })).not.toBeInTheDocument();
    await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
    await expect(canvas.getByRole("button", { name: "금액 다시 계산" })).toBeEnabled();
  },
};

function rejectedRequest(status: number, code: string, title: string): Story {
  return {
    parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected), http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", () => HttpResponse.json({ code, message: "Internal server detail", correlationId: "REQ-REJECTED" }, { status }))] } },
    play: async ({ canvas, msw }) => {
      await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
      let rejectedKey: string | null = null;
      msw.use(http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", ({ request }) => {
        rejectedKey = request.headers.get("Idempotency-Key");
        return HttpResponse.json({ code, message: "Internal server detail" }, { status });
      }));
      await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
      await expect(await canvas.findByText(title)).toBeVisible();
      await expect(canvas.getByLabelText("환불 사유")).toBeEnabled();
      await expect(canvas.queryByRole("button", { name: "같은 요청 결과 확인" })).not.toBeInTheDocument();
      await expect(canvas.queryByText("Internal server detail")).not.toBeInTheDocument();
      await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
      // A later attempt must first pass a fresh server preview, never replay the rejected command.
      msw.use(previewHandler(selected));
      await userEvent.click(canvas.getByRole("button", { name: "금액 다시 계산" }));
      await waitFor(() => expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeEnabled());
      msw.use(http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", ({ request }) => {
        expect(request.headers.get("Idempotency-Key")).not.toBe(rejectedKey);
        return HttpResponse.json({ orderReference, state: "UNKNOWN", cashRefundRequestedKrw: 3800, pointsRestorationRequestedKrw: 0, pointsRestorationState: "NOT_REQUIRED", currency: "KRW", createdAt: "2026-08-17T03:00:00Z", updatedAt: "2026-08-17T03:00:05Z", correlationId: "REQ-RETRY" }, { status: 202 });
      }));
      await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
      await expect(await canvas.findByText("환불 결과를 확인하고 있습니다")).toBeVisible();
    },
  };
}

export const InvalidRequest: Story = rejectedRequest(400, "INVALID_REQUEST", "입력 내용을 확인해 주세요");
export const PermissionDenied: Story = rejectedRequest(403, "ACCESS_DENIED", "이 작업을 진행할 수 없습니다");
export const OrderNotFound: Story = rejectedRequest(404, "RESOURCE_NOT_FOUND", "요청한 대상을 찾을 수 없습니다");
export const ExecutionUnavailable: Story = rejectedRequest(503, "DEPENDENCY_UNAVAILABLE", "서비스 연결을 확인하고 있습니다");

export const QuantityNoLongerAvailable: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected), http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", () => HttpResponse.json({ code: "REFUND_QUANTITY_UNAVAILABLE", message: "Requested units exceed remaining units" }, { status: 422 }))] } },
  play: async ({ canvas, msw }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    let previewBody: unknown;
    msw.use(http.post("/api/v1/stores/:storeId/orders/:orderReference/refund-previews", async ({ request }) => {
      previewBody = await request.json();
      return HttpResponse.json(preview({ lines: [line({ remainingQuantity: 1 })], previewVersion: "b".repeat(64) }));
    }));
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByText(/환불 가능 수량이 바뀌었습니다/)).toBeVisible();
    await expect(previewBody).toEqual({});
    await expect(canvas.getByLabelText("환불 사유")).toBeEnabled();
    await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
    await expect(canvas.queryByRole("button", { name: "같은 요청 결과 확인" })).not.toBeInTheDocument();
    await expect(canvas.getByText("남은 환불 가능 1개")).toBeVisible();
  },
};

export const SameRequestProcessing: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected)] } },
  play: async ({ canvas, msw }) => {
    let firstRequest: { key: string | null; body: unknown } | undefined;
    msw.use(http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", async ({ request }) => {
      firstRequest = { key: request.headers.get("Idempotency-Key"), body: await request.json() };
      return HttpResponse.json({ code: "IDEMPOTENCY_REQUEST_IN_PROGRESS", message: "Request is processing" }, { status: 409, headers: { "Retry-After": "1" } });
    }));
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByRole("button", { name: "처리 상태 다시 확인" })).toBeEnabled();
    await expect(canvas.queryByText("환불 요청 결과를 확인하지 못했습니다")).not.toBeInTheDocument();
    await expect(canvas.getByLabelText("환불 사유")).toBeDisabled();
    msw.use(http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", async ({ request }) => {
      await expect({ key: request.headers.get("Idempotency-Key"), body: await request.json() }).toEqual(firstRequest);
      return HttpResponse.json({ orderReference, state: "UNKNOWN", cashRefundRequestedKrw: 3800, pointsRestorationRequestedKrw: 0, pointsRestorationState: "NOT_REQUIRED", currency: "KRW", createdAt: "2026-08-17T03:00:00Z", updatedAt: "2026-08-17T03:00:05Z", correlationId: "REQ-PROCESSING" }, { status: 202 });
    }));
    await userEvent.click(canvas.getByRole("button", { name: "처리 상태 다시 확인" }));
    await expect(await canvas.findByText("환불 결과를 확인하고 있습니다")).toBeVisible();
  },
};

export const CsrfPreparationUnavailable: Story = {
  parameters: { msw: { handlers: [...merchantFrameHandlers, previewHandler(selected)] } },
  play: async ({ canvas, msw }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    document.cookie = "BEANFLOW_MERCHANT_XSRF=; Max-Age=0; path=/";
    let sent = false;
    msw.use(
      http.get("/api/v1/auth/merchant/csrf", () => HttpResponse.error()),
      http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", () => { sent = true; return HttpResponse.error(); }),
    );
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByRole("alert")).toBeVisible();
    await expect(sent).toBe(false);
    await expect(canvas.getByLabelText("환불 사유")).toBeEnabled();
    await expect(canvas.queryByRole("button", { name: "같은 요청 결과 확인" })).not.toBeInTheDocument();
  },
};
