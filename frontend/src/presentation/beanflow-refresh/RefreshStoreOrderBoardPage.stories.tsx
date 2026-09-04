import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { HttpResponse, http } from "msw";
import { boardOrder, ids, merchantSignedInHandlers, storeBoardHandlers } from "../../../.storybook/fixtures";
import type { StoreOrderBoard } from "../../pages/console/storeOrderBoardModel";
import { RefreshStoreOrderBoardPage } from "./MerchantPages";

const meta = {
  title: "Pages/Refresh/Store/Order board",
  component: RefreshStoreOrderBoardPage,
  args: { now: new Date("2026-08-15T03:05:00Z") },
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen", docs: { story: { inline: false, height: "820px" } },
    routing: { path: "/store", initialEntry: "/store", surface: "refresh-store" },
    msw: { handlers: [...merchantSignedInHandlers, ...storeBoardHandlers()] },
  },
} satisfies Meta<typeof RefreshStoreOrderBoardPage>;

export default meta;
type Story = StoryObj<typeof meta>;

const visualBoard: StoreOrderBoard = {
  groups: [{
    pickupBusinessDate: "2026-08-15",
    items: [
      boardOrder,
      { ...boardOrder, orderReference: "BF-WAIT-002", pickupNumber: "A-143", pickupWindowStart: "2026-08-15T03:30:00Z", acceptancePhase: "OPEN" },
      { ...boardOrder, orderReference: "BF-WAIT-003", pickupNumber: "A-144", pickupWindowStart: "2026-08-15T03:40:00Z", acceptancePhase: "OPEN" },
      { ...boardOrder, orderReference: "BF-MAKE-001", pickupNumber: "A-145", pickupWindowStart: "2026-08-15T03:25:00Z", lane: "PREPARING", status: "PREPARING", acceptancePhase: "OPEN", allowedActions: ["MARK_READY"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:01:00Z", preparingAt: "2026-08-15T03:03:00Z" } },
      { ...boardOrder, orderReference: "BF-MAKE-002", pickupNumber: "A-146", pickupWindowStart: "2026-08-15T03:35:00Z", lane: "PREPARING", status: "PREPARING", acceptancePhase: "OPEN", allowedActions: ["MARK_READY"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:02:00Z", preparingAt: "2026-08-15T03:04:00Z" } },
      { ...boardOrder, orderReference: "BF-MAKE-003", pickupNumber: "A-147", pickupWindowStart: "2026-08-15T03:45:00Z", lane: "PREPARING", status: "PREPARING", acceptancePhase: "OPEN", allowedActions: ["MARK_READY"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:03:00Z", preparingAt: "2026-08-15T03:05:00Z" } },
      { ...boardOrder, orderReference: "BF-READY-001", pickupNumber: "A-148", pickupWindowStart: "2026-08-15T03:20:00Z", lane: "READY", status: "READY", acceptancePhase: "OPEN", allowedActions: ["COMPLETE"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:00:00Z", preparingAt: "2026-08-15T03:01:00Z", readyAt: "2026-08-15T03:02:00Z" } },
      { ...boardOrder, orderReference: "BF-READY-002", pickupNumber: "A-149", pickupWindowStart: "2026-08-15T03:30:00Z", lane: "READY", status: "READY", acceptancePhase: "OPEN", allowedActions: ["COMPLETE"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:00:00Z", preparingAt: "2026-08-15T03:02:00Z", readyAt: "2026-08-15T03:04:00Z" } },
      { ...boardOrder, orderReference: "BF-READY-003", pickupNumber: "A-150", pickupWindowStart: "2026-08-15T03:40:00Z", lane: "READY", status: "READY", acceptancePhase: "OPEN", allowedActions: ["COMPLETE"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:01:00Z", preparingAt: "2026-08-15T03:03:00Z", readyAt: "2026-08-15T03:05:00Z" } },
    ],
  }],
  overflow: [],
};

export const ActiveOrders: Story = {
  parameters: { msw: { handlers: [...merchantSignedInHandlers, ...storeBoardHandlers(visualBoard)] } },
  play: async ({ canvas }) => {
    const card = await canvas.findByRole("article", { name: "주문 A-142" });
    await expect(card).toBeVisible();
    await expect(within(card).getByText("아이스 아메리카노 외 1건")).toBeVisible();
  },
};

export const EmptyBoard: Story = {
  parameters: { msw: { handlers: [...merchantSignedInHandlers, ...storeBoardHandlers({ groups: [], overflow: [] })] } },
  play: async ({ canvas }) => { await expect(await canvas.findAllByText("대기 주문 없음")).toHaveLength(3); },
};

export const TransitionConflict: Story = {
  tags: ["!autodocs"],
  parameters: { msw: { handlers: [...merchantSignedInHandlers, ...storeBoardHandlers(), http.post("/api/v1/stores/:storeId/orders/:orderReference/transitions", () => HttpResponse.json({ code: "ORDER_STATE_CONFLICT", message: "주문 상태가 변경되었습니다.", correlationId: "REQ-BOARD-409" }, { status: 409 }))] } },
  beforeEach: () => { document.cookie = "BEANFLOW_MERCHANT_XSRF=storybook-merchant-csrf; path=/"; },
  play: async ({ canvas }) => {
    const card = await canvas.findByRole("article", { name: "주문 A-142" });
    await userEvent.click(within(card).getByRole("button", { name: "주문 접수" }));
    await expect(await canvas.findByText(/다른 작업자가 먼저 처리했습니다/)).toBeVisible();
  },
};

export const MultipleLanes: Story = {
  parameters: { msw: { handlers: [...merchantSignedInHandlers, ...storeBoardHandlers({ groups: [{ pickupBusinessDate: "2026-08-15", items: [boardOrder, { ...boardOrder, orderReference: "BF-PREPARING", pickupNumber: "A-143", lane: "PREPARING", status: "PREPARING", acceptancePhase: "OPEN", allowedActions: ["MARK_READY"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:01:00Z", preparingAt: "2026-08-15T03:02:00Z" } }] }], overflow: [] })] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("article", { name: "주문 A-143" })).toBeVisible();
    await expect(canvas.getByRole("button", { name: "준비 완료" })).toBeVisible();
  },
};

export const OverflowQueue: Story = {
  parameters: {
    msw: { handlers: [...merchantSignedInHandlers, ...storeBoardHandlers({ groups: [{ pickupBusinessDate: "2026-08-15", items: [boardOrder] }], overflow: [{ lane: "READY", overflowCount: 2, nextCursor: "ready-older" }] })] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("button", { name: "오래된 준비 완료 작업 2건 보기" })).toBeVisible();
  },
};
