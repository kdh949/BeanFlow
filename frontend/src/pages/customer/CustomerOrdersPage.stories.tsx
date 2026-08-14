import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { apiError, orderListHandlers, orderSummary, pending } from "../../../.storybook/fixtures";
import { CustomerOrdersPage } from "./CustomerOrders";

const meta = {
  title: "Pages/Customer/Orders",
  component: CustomerOrdersPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: { component: "server-owned ACTIVE/PAST classification과 공개 주문번호를 사용하는 고객 주문 목록입니다." },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/orders", initialEntry: "/app/orders?status=ACTIVE&from=2026-07-17&to=2026-08-15" },
  },
} satisfies Meta<typeof CustomerOrdersPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ActiveOrder: Story = {
  parameters: { msw: { handlers: orderListHandlers() } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("link", { name: /아이스 아메리카노 외 1건/ })).toBeVisible();
  },
};

export const PastOrder: Story = {
  parameters: {
    routing: { path: "/app/orders", initialEntry: "/app/orders?status=PAST&from=2026-07-17&to=2026-08-15" },
    msw: { handlers: orderListHandlers([{ ...orderSummary, status: "COMPLETED", allowedActions: [] }]) },
  },
};

export const Empty: Story = {
  parameters: { msw: { handlers: orderListHandlers([]) } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("진행 중인 주문이 없어요")).toBeVisible();
  },
};

export const RecoverableError: Story = {
  parameters: { msw: { handlers: [apiError("/api/v1/me/orders")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};

export const Loading: Story = {
  parameters: { msw: { handlers: [pending("/api/v1/me/orders")] } },
};
