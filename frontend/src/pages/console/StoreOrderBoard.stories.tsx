import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { apiError, storeBoardHandlers } from "../../../.storybook/fixtures";
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
