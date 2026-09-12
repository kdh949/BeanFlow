import { http, HttpResponse } from "msw";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { apiError, orderListHandlers, orderSummary, pending, signedInHandlers } from "../../../.storybook/fixtures";
import { CustomerOrdersPage } from "./CustomerOrdersPage";

const meta = {
  title: "Pages/Customer/Orders",
  component: CustomerOrdersPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen",
    docs: {
      description: { component: "server-owned ACTIVE/PAST classification과 공개 주문번호를 사용하는 고객 주문 목록입니다." },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/orders", surface: "customer", initialEntry: "/app/orders?status=ACTIVE&from=2026-07-17&to=2026-08-15" },
  },
} satisfies Meta<typeof CustomerOrdersPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ActiveOrder: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, ...orderListHandlers()] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "주문 내역" })).toBeVisible();
    await expect(await canvas.findByRole("link", { name: /아이스 아메리카노 외 1건/ })).toBeVisible();
  },
};

export const PastOrder: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("link", { name: "다시 주문" })).toHaveAttribute("href", `/app/orders/${orderSummary.orderReference}?reorder=1`);
    await expect(canvas.queryByLabelText("조회 시작일")).not.toBeInTheDocument();
    await userEvent.click(canvas.getByRole("button", { name: "기간 변경" }));
    await expect(canvas.getByLabelText("조회 시작일")).toHaveValue("2026-07-17");
  },
  parameters: {
    routing: { path: "/app/orders", surface: "customer", initialEntry: "/app/orders?status=PAST&from=2026-07-17&to=2026-08-15" },
    msw: { handlers: [...signedInHandlers, ...orderListHandlers([{ ...orderSummary, status: "COMPLETED", allowedActions: ["REORDER"] }])] },
  },
};

export const RefundOrder: Story = {
  parameters: {
    routing: { path: "/app/orders", surface: "customer", initialEntry: "/app/orders?status=PAST&from=2026-07-17&to=2026-08-15" },
    msw: { handlers: [...signedInHandlers, ...orderListHandlers([{ ...orderSummary, status: "CANCELLED", allowedActions: ["VIEW_REFUND"] }])] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "주문 내역" })).toBeVisible();
    const order = await canvas.findByRole("link", { name: /환불 내역 확인/ });
    await expect(order).toHaveAttribute("href", `/app/orders/${orderSummary.orderReference}`);
  },
};

export const Empty: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, ...orderListHandlers([])] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("진행 중인 주문이 없어요")).toBeVisible();
  },
};

export const RecoverableError: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, apiError("/api/v1/me/orders")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};

export const Loading: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, pending("/api/v1/me/orders")] } },
};

export const LoadedPagesSurviveRefresh: Story = {
  tags: ["!autodocs"],
  parameters: { msw: { handlers: [...signedInHandlers, http.get("/api/v1/me/orders", ({ request }) => {
    const second = new URL(request.url).searchParams.get("cursor") === "page-two";
    return HttpResponse.json({ items: [{ ...orderSummary, orderReference: second ? "BF-2222-3333" : orderSummary.orderReference, itemSummary: second ? "두 번째 페이지 주문" : "첫 번째 페이지 주문" }], page: { nextCursor: second ? "page-three" : "page-two" } });
  })] } },
  play: async ({ canvas, msw }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "주문 더 보기" }));
    const row = await canvas.findByRole("link", { name: /두 번째 페이지 주문/ });
    await waitFor(() => expect(canvas.getByRole("button", { name: "주문 더 보기" })).toBeEnabled());
    await expect(document.visibilityState).toBe("visible");
    let reads = 0;
    let release!: () => void;
    const wait = new Promise<void>(resolve => { release = resolve; });
    msw.use(http.get("/api/v1/me/orders", async ({ request }) => {
      ++reads; await wait;
      const second = new URL(request.url).searchParams.get("cursor") === "page-two";
      return HttpResponse.json({ items: [{ ...orderSummary, orderReference: second ? "BF-2222-3333" : orderSummary.orderReference, itemSummary: second ? "두 번째 페이지 주문" : "갱신한 첫 번째 주문" }], page: { nextCursor: second ? "page-three" : "page-two" } });
    }));
    try { window.dispatchEvent(new Event("focus")); await waitFor(() => expect(reads).toBe(1)); await expect(row).toBeInTheDocument(); }
    finally { release(); }
    await expect(await canvas.findByRole("link", { name: /갱신한 첫 번째 주문/ })).toBeVisible();
    await expect(canvas.getByRole("link", { name: /두 번째 페이지 주문/ })).toBeVisible();
    await expect(canvas.getByRole("button", { name: "주문 더 보기" })).toBeEnabled();
    await expect(reads).toBe(2);
  },
};
