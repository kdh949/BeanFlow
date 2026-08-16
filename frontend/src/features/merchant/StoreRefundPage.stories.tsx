import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { StoreRefundPage } from "./StoreRefundPage";

const storeId = "10000000-0000-4000-8000-000000000001";
const orderReference = "BF-7K3M-9Q2P";

function line(overrides: Record<string, unknown> = {}) {
  return {
    lineSequence: 0,
    menuName: "아이스 아메리카노",
    selectedQuantity: 0,
    remainingQuantity: 2,
    grossAttributionKrw: 0,
    couponAttributionKrw: 0,
    pointsRestorationKrw: 0,
    cashRefundKrw: 0,
    ...overrides,
  };
}

function preview(overrides: Record<string, unknown> = {}) {
  return {
    orderReference,
    lines: [line(), line({ lineSequence: 1, menuName: "오트 라떼", remainingQuantity: 1 })],
    totals: { grossAttributionKrw: 0, couponAttributionKrw: 0, pointsRestorationKrw: 0, cashRefundKrw: 0, currency: "KRW" },
    previewVersion: "a".repeat(64),
    ...overrides,
  };
}

/** Each preview call answers with the server's amounts for the current selection. */
function previewHandler(body: Record<string, unknown> = preview()) {
  return http.post("/api/v1/stores/:storeId/orders/:orderReference/refund-previews", () => HttpResponse.json(body));
}

const meta = {
  title: "Pages/Store/Item refund",
  component: StoreRefundPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "주문번호와 품목 순번만으로 환불을 실행하는 화면입니다. 결제 번호나 주문 상품 UUID를 입력하는 자리가 없고, 금액은 언제나 서버가 계산한 값입니다.",
      },
      story: { inline: false, height: "760px" },
    },
    routing: {
      path: "/store/refunds/:storeId/:orderReference",
      initialEntry: `/store/refunds/${storeId}/${orderReference}`,
    },
    msw: { handlers: [...merchantSignedInHandlers, previewHandler()] },
  },
} satisfies Meta<typeof StoreRefundPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SelectableItems: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("아이스 아메리카노")).toBeVisible();
    await expect(canvas.getAllByLabelText("환불 수량")[0]).toHaveValue(0);
    await expect(canvas.getByRole("button", { name: /부분 환불 실행/ })).toBeDisabled();
  },
};

/** The selected amount is whatever the server returned, never a client-side multiplication. */
export const ServerCalculatedAmount: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        previewHandler(
          preview({
            lines: [
              line({ selectedQuantity: 1, cashRefundKrw: 3_800, couponAttributionKrw: 200, grossAttributionKrw: 4_000 }),
              line({ lineSequence: 1, menuName: "오트 라떼", remainingQuantity: 1 }),
            ],
            totals: {
              grossAttributionKrw: 4_000,
              couponAttributionKrw: 200,
              pointsRestorationKrw: 0,
              cashRefundKrw: 3_800,
              currency: "KRW",
            },
          }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    // 품목 줄과 합계가 같은 금액을 보여 주므로 두 곳에서 확인한다.
    await expect(await canvas.findAllByText("₩3,800")).toHaveLength(2);
  },
};

/** A preview taken before someone else's refund is stale; the screen re-prices instead of retrying blindly. */
export const StalePreview: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        previewHandler(
          preview({
            lines: [line({ selectedQuantity: 1, cashRefundKrw: 3_800 }), line({ lineSequence: 1, menuName: "오트 라떼", remainingQuantity: 1 })],
            totals: { grossAttributionKrw: 4_000, couponAttributionKrw: 200, pointsRestorationKrw: 0, cashRefundKrw: 3_800, currency: "KRW" },
          }),
        ),
        http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", () =>
          HttpResponse.json(
            { code: "REFUND_PREVIEW_STALE", message: "환불 상태가 변경되었습니다.", correlationId: "REQ-DEMO-42" },
            { status: 409 },
          )),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByRole("alert")).toHaveTextContent(/새로 계산한 금액을 확인한 뒤 다시 실행/);
  },
};

/** 202 is neither success nor failure, so the screen says the outcome is still being confirmed. */
export const UnresolvedProviderOutcome: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        previewHandler(
          preview({
            lines: [line({ selectedQuantity: 1, cashRefundKrw: 3_800 }), line({ lineSequence: 1, menuName: "오트 라떼", remainingQuantity: 1 })],
            totals: { grossAttributionKrw: 4_000, couponAttributionKrw: 200, pointsRestorationKrw: 0, cashRefundKrw: 3_800, currency: "KRW" },
          }),
        ),
        http.post("/api/v1/stores/:storeId/orders/:orderReference/refunds", () =>
          HttpResponse.json(
            {
              orderReference,
              state: "UNKNOWN",
              cashRefundRequestedKrw: 3_800,
              pointsRestorationRequestedKrw: 0,
              pointsRestorationState: "NOT_REQUIRED",
              currency: "KRW",
              createdAt: "2026-08-17T03:00:00Z",
              updatedAt: "2026-08-17T03:00:05Z",
              correlationId: "REQ-DEMO-42",
            },
            { status: 202 },
          )),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("환불 사유"), "고객 요청");
    await userEvent.click(canvas.getByRole("button", { name: /부분 환불 실행/ }));
    await expect(await canvas.findByText("환불 결과를 확인하고 있습니다")).toBeVisible();
  },
};

export const NothingLeftToRefund: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        previewHandler(preview({ lines: [line({ remainingQuantity: 0 })] })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("이 주문에는 더 환불할 수 있는 품목이 없습니다.")).toBeVisible();
  },
};

export const PreviewUnavailable: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        http.post("/api/v1/stores/:storeId/orders/:orderReference/refund-previews", () =>
          HttpResponse.json(
            { code: "DEPENDENCY_UNAVAILABLE", message: "환불 정보를 불러오지 못했습니다.", correlationId: "REQ-DEMO-42" },
            { status: 503 },
          )),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("요청을 완료하지 못했습니다")).toBeVisible();
  },
};
