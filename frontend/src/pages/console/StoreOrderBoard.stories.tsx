import type { Meta, StoryObj } from "@storybook/react-vite";
import { delay, HttpResponse, http } from "msw";
import { expect, userEvent, within } from "storybook/test";
import { apiError, boardOrder, pending, storeBoardHandlers } from "../../../.storybook/fixtures";
import { StoreOrderBoardPage } from "./StoreOrderBoard";

const meta = {
  title: "Pages/Store/OrderBoard",
  component: StoreOrderBoardPage,
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
