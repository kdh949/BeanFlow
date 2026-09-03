import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { ids, merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { StoreSettlementsPage } from "./StoreSettlementsPage";

const batchId = "90000000-0000-4000-8000-000000000001";
const itemId = "91000000-0000-4000-8000-000000000001";

const ownerStores = http.get("/api/v1/merchant/me/stores", () =>
  HttpResponse.json([{ storeId: ids.store, storeName: "시청점", membershipRole: "OWNER" }]));

const staffOnlyStores = http.get("/api/v1/merchant/me/stores", () =>
  HttpResponse.json([{ storeId: ids.store, storeName: "시청점", membershipRole: "STAFF" }]));

const confirmedBatch = {
  settlementBatchId: batchId,
  storeId: ids.store,
  settlementDate: "2026-08-14",
  state: "CONFIRMED",
  grossPaidKrw: 185_000,
  feeKrw: 9_250,
  benefitCostKrw: 4_500,
  adjustmentKrw: -3_000,
  netSettlementKrw: 168_250,
  currency: "KRW",
  confirmedAt: "2026-08-15T08:00:00+09:00",
};

const batchHandler = (items: unknown[] = [confirmedBatch]) =>
  http.get("/api/v1/stores/:storeId/settlements", () => HttpResponse.json({ items, page: {} }));

const itemHandler = http.get("/api/v1/stores/:storeId/settlements/:settlementBatchId/items", () =>
  HttpResponse.json({
    items: [
      {
        settlementItemId: itemId,
        settlementBatchId: batchId,
        orderId: ids.order,
        completedAt: "2026-08-14T15:20:00+09:00",
        grossPaidKrw: 12_000,
        feeKrw: 600,
        benefitCostKrw: 1_000,
        netSettlementKrw: 10_400,
        currency: "KRW",
      },
    ],
    page: {},
  }));

const meta = {
  title: "Pages/Store/Settlements",
  component: StoreSettlementsPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "확정 정산과 주문별 명세를 보는 점주 전용 화면입니다. 명세에서 바로 이의를 제기하며, 조회 실패를 '정산 없음'으로 표시하지 않습니다.",
      },
      story: { inline: false, height: "760px" },
    },
    routing: { surface: "store", path: "/store/settlements", initialEntry: "/store/settlements" },
    msw: { handlers: [...merchantSignedInHandlers, ownerStores, batchHandler(), itemHandler] },
  },
} satisfies Meta<typeof StoreSettlementsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ConfirmedSettlements: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("₩168,250")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "주문별 명세" })).toBeVisible();
  },
};

export const ItemsWithDisputeEntry: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "주문별 명세" }));
    await expect(await canvas.findByText("₩10,400")).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "이의제기" }));
    await expect(await canvas.findByLabelText("기대하는 조정 금액 (원)")).toBeVisible();
  },
};

export const NoSettlementYet: Story = {
  parameters: {
    msw: { handlers: [...merchantSignedInHandlers, ownerStores, batchHandler([])] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("아직 확정된 정산이 없습니다")).toBeVisible();
  },
};

/** A staff account has no owner-scoped store, so the screen explains the limit instead of showing an empty ledger. */
export const StaffHasNoOwnedStore: Story = {
  parameters: {
    msw: { handlers: [...merchantSignedInHandlers, staffOnlyStores] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("정산을 볼 수 있는 매장이 없습니다")).toBeVisible();
  },
};

/** A query failure is retryable, never "there are no settlements". */
export const SettlementQueryUnavailable: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        ownerStores,
        http.get("/api/v1/stores/:storeId/settlements", () =>
          HttpResponse.json(
            { code: "DEPENDENCY_UNAVAILABLE", message: "정산 조회를 완료하지 못했습니다.", correlationId: "REQ-DEMO-42" },
            { status: 503 },
          )),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("서비스 연결을 확인하고 있습니다")).toBeVisible();
    await expect(canvas.getByRole("button", { name: /다시 시도/ })).toBeVisible();
  },
};
