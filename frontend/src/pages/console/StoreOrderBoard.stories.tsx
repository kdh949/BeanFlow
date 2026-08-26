import type { Meta, StoryObj } from "@storybook/react-vite";
import { delay, HttpResponse, http } from "msw";
import { expect, userEvent, within } from "storybook/test";
import { apiError, boardOrder, ids, pending, storeBoardHandlers } from "../../../.storybook/fixtures";
import { StoreOrderBoardPage } from "./StoreOrderBoard";

const meta = {
  title: "Pages/Store/OrderBoard",
  component: StoreOrderBoardPage,
  args: { now: new Date("2026-08-15T03:05:00Z") },
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: { component: "active membership 안에서 server lane과 allowedActions를 표시하는 polling 주문 보드입니다." },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/store", initialEntry: "/store" },
  },
} satisfies Meta<typeof StoreOrderBoardPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ActiveOrders: Story = {
  parameters: { msw: { handlers: storeBoardHandlers() } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("article", { name: "주문 A-142" })).toBeVisible();
  },
};

export const EmptyBoard: Story = {
  parameters: { msw: { handlers: storeBoardHandlers({ groups: [], overflow: [] }) } },
  play: async ({ canvas }) => {
    await expect(await canvas.findAllByText("대기 주문 없음")).toHaveLength(3);
  },
};

export const PermissionFailure: Story = {
  parameters: { msw: { handlers: [apiError("/api/v1/merchant/me/stores", 403, "FORBIDDEN", "접근 가능한 매장을 확인할 권한이 없습니다.")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};

export const Loading: Story = {
  parameters: { msw: { handlers: [pending("/api/v1/merchant/me/stores")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("접근 가능한 매장을 확인하는 중")).toBeVisible();
  },
};

const secondStoreId = "10000000-0000-4000-8000-000000000002";

export const MultipleStores: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([
          { storeId: ids.store, storeName: "시청점", membershipRole: "OWNER" },
          { storeId: secondStoreId, storeName: "광화문점", membershipRole: "STAFF" },
        ])),
        http.get("/api/v1/stores/:storeId/orders", ({ params }) => HttpResponse.json({
          groups: [{
            pickupBusinessDate: "2026-08-15",
            items: [{
              ...boardOrder,
              pickupNumber: params["storeId"] === secondStoreId ? "B-207" : "A-142",
              orderReference: params["storeId"] === secondStoreId ? "BF-2ND-STORE" : boardOrder.orderReference,
            }],
          }],
          overflow: [],
        })),
      ],
    },
  },
  play: async ({ canvas }) => {
    const selector = await canvas.findByRole("combobox", { name: "운영 매장" });
    await userEvent.selectOptions(selector, secondStoreId);
    await expect(await canvas.findByRole("article", { name: "주문 B-207" })).toBeVisible();
  },
};

export const OverflowQueue: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        ...storeBoardHandlers({
          groups: [{ pickupBusinessDate: "2026-08-15", items: [boardOrder] }],
          overflow: [{ lane: "PENDING_ACCEPTANCE", overflowCount: 2, nextCursor: "overflow-page-1" }],
        }),
        http.get("/api/v1/stores/:storeId/orders/overflow", () => HttpResponse.json({
          lane: "PENDING_ACCEPTANCE",
          items: [{ ...boardOrder, orderReference: "BF-OLD-0001", pickupNumber: "A-099", acceptancePhase: "OPEN" }],
          nextCursor: null,
        })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "오래된 접수 대기 작업 2건 보기" }));
    await expect(await canvas.findByRole("article", { name: "주문 A-099" })).toBeVisible();
  },
};

export const TransitionLanes: Story = {
  parameters: {
    msw: {
      handlers: storeBoardHandlers({
        groups: [{
          pickupBusinessDate: "2026-08-15",
          items: [
            boardOrder,
            { ...boardOrder, orderReference: "BF-ACCEPTED-1", pickupNumber: "A-143", lane: "ACCEPTED", status: "ACCEPTED", acceptancePhase: "OPEN", allowedActions: ["START_PREPARING"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:01:00Z" } },
            { ...boardOrder, orderReference: "BF-PREPARING-1", pickupNumber: "A-144", lane: "PREPARING", status: "PREPARING", acceptancePhase: "OPEN", allowedActions: ["MARK_READY"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:01:00Z", preparingAt: "2026-08-15T03:02:00Z" } },
            { ...boardOrder, orderReference: "BF-READY-1", pickupNumber: "A-145", lane: "READY", status: "READY", acceptancePhase: "OPEN", allowedActions: ["COMPLETE"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:01:00Z", preparingAt: "2026-08-15T03:02:00Z", readyAt: "2026-08-15T03:03:00Z" } },
          ],
        }],
        overflow: [],
      }),
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("button", { name: "주문 접수" })).toBeVisible();
    await expect(canvas.getByRole("button", { name: "제조 시작" })).toBeVisible();
    await expect(canvas.getByRole("button", { name: "준비 완료" })).toBeVisible();
    await expect(canvas.getByRole("button", { name: "픽업 완료" })).toBeVisible();
    await expect(canvas.getByText("결제 후 5분 경과")).toBeVisible();
    await expect(canvas.getByText("접수 후 4분 경과")).toBeVisible();
    await expect(canvas.getByText("제조 시작 후 3분 경과")).toBeVisible();
    await expect(canvas.getByText("준비 완료 후 2분 경과")).toBeVisible();
  },
};

let conflictBoardReadCount = 0;

export const StateConflictRefreshesBoard: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([
          { storeId: ids.store, storeName: "시청점", membershipRole: "OWNER" },
        ])),
        http.get("/api/v1/stores/:storeId/orders", () => {
          conflictBoardReadCount += 1;
          const item = conflictBoardReadCount === 1
            ? boardOrder
            : { ...boardOrder, lane: "ACCEPTED", status: "ACCEPTED", acceptancePhase: "OPEN", allowedActions: ["START_PREPARING"], lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:01:00Z" } };
          return HttpResponse.json({ groups: [{ pickupBusinessDate: "2026-08-15", items: [item] }], overflow: [] });
        }),
        http.post("/api/v1/stores/:storeId/orders/:orderReference/transitions", () =>
          HttpResponse.json({ code: "ORDER_STATE_CONFLICT", message: "주문 상태가 이미 변경되었습니다." }, { status: 409 })),
      ],
    },
  },
  beforeEach: () => {
    conflictBoardReadCount = 0;
    document.cookie = "BEANFLOW_MERCHANT_XSRF=storybook-merchant-csrf; path=/";
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "주문 접수" }));
    await expect(await canvas.findByRole("status", { name: "주문 상태 갱신 안내" })).toHaveTextContent("다른 작업자가 먼저 처리했습니다");
    await expect(await canvas.findByRole("button", { name: "제조 시작" })).toBeVisible();
  },
};

export const ConflictRefresh: Story = {
  parameters: {
    msw: {
      handlers: [
        ...storeBoardHandlers(),
        http.post("/api/v1/stores/:storeId/orders/:orderReference/transitions", () => HttpResponse.json(
          { code: "ORDER_STATE_CONFLICT", message: "Order state changed", correlationId: "REQ-STORY-CONFLICT" },
          { status: 409 },
        )),
      ],
    },
  },
  play: async ({ canvas }) => {
    document.cookie = "BEANFLOW_MERCHANT_XSRF=storybook-merchant-csrf; path=/";
    const card = await canvas.findByRole("article", { name: "주문 A-142" });
    await userEvent.click(within(card).getByRole("button", { name: "주문 접수" }));
    await expect(await canvas.findByRole("status", { name: "주문 상태 갱신 안내" })).toHaveTextContent("다른 작업자가 먼저 처리했습니다");
  },
};

export const Overflow: Story = {
  parameters: {
    msw: {
      handlers: [
        ...storeBoardHandlers({
          groups: [{ pickupBusinessDate: "2026-08-15", items: [boardOrder] }],
          overflow: [{ lane: "READY", overflowCount: 2, nextCursor: "ready-overflow-cursor" }],
        }),
        http.get("/api/v1/stores/:storeId/orders/overflow", () => HttpResponse.json({
          lane: "READY",
          items: [{
            ...boardOrder,
            orderReference: "BF-4D8N-7R2K",
            pickupNumber: "A-143",
            lane: "READY",
            status: "READY",
            acceptanceDeadlineAt: undefined,
            acceptancePhase: undefined,
            allowedActions: ["COMPLETE"],
            lifecycle: { ...boardOrder.lifecycle, acceptedAt: "2026-08-15T03:01:00Z", preparingAt: "2026-08-15T03:02:00Z", readyAt: "2026-08-15T03:03:00Z" },
          }],
          nextCursor: null,
        })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "오래된 준비 완료 작업 2건 보기" }));
    await expect(await canvas.findByRole("article", { name: "주문 A-143" })).toBeVisible();
  },
};

export const TransitionBusy: Story = {
  parameters: {
    msw: {
      handlers: [
        ...storeBoardHandlers(),
        http.post("/api/v1/stores/:storeId/orders/:orderReference/transitions", async () => {
          await delay("infinite");
          return HttpResponse.json({});
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    document.cookie = "BEANFLOW_MERCHANT_XSRF=storybook-merchant-csrf; path=/";
    const card = await canvas.findByRole("article", { name: "주문 A-142" });
    await userEvent.click(within(card).getByRole("button", { name: "주문 접수" }));
    await expect(await within(card).findByRole("button", { name: "처리 중" })).toBeDisabled();
  },
};
