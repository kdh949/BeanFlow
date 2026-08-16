import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { apiError, orderDetailHandlers, orderSummary } from "../../../.storybook/fixtures";
import { CustomerOrderDetailPage } from "./OrderPages";

const meta = {
  title: "Pages/Customer/OrderDetail",
  component: CustomerOrderDetailPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: { component: "공개 주문번호와 immutable line snapshot으로 pickup 진행 상태를 보여주는 route입니다." },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/orders/:orderReference", initialEntry: `/app/orders/${orderSummary.orderReference}` },
  },
} satisfies Meta<typeof CustomerOrderDetailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ReadyForPickup: Story = {
  parameters: { msw: { handlers: orderDetailHandlers() } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("A-142")).toBeVisible();
  },
};

export const Cancelled: Story = {
  parameters: { msw: { handlers: orderDetailHandlers({ status: "CANCELLED", allowedActions: [] }) } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("취소된 주문입니다")).toBeVisible();
  },
};

export const PermissionFailure: Story = {
  parameters: { msw: { handlers: [apiError("/api/v1/me/orders/:orderReference", 403, "FORBIDDEN", "이 주문을 볼 권한이 없습니다.")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};
