import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, catalogHandlers, ids, pending, signedInHandlers, storeIdentityHandlers } from "../../../.storybook/fixtures";
import { StoreDetailPage } from "./StoreDetailPage";

const meta = {
  title: "Pages/Customer/Store detail",
  component: StoreDetailPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "매장 이름·메뉴·픽업 시간을 모두 서버에서 읽습니다. 링크로 들어오든 URL을 붙여넣든 같은 화면이 됩니다.",
      },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/stores/:storeId", initialEntry: `/app/stores/${ids.store}` },
  },
} satisfies Meta<typeof StoreDetailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Orderable: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, ...storeIdentityHandlers, ...catalogHandlers] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "시청점" })).toBeVisible();
    await expect(await canvas.findByRole("button", { name: /오늘의 필터 커피/ })).toBeDisabled();
  },
};

export const PickupClosed: Story = {
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        ...storeIdentityHandlers,
        http.get("/api/v1/stores/:storeId/menus", () => HttpResponse.json({ items: [] })),
        http.get("/api/v1/stores/:storeId/pickup-slots", () => HttpResponse.json({ items: [] })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/지금은 픽업 시간이 모두 마감됐어요/)).toBeVisible();
  },
};

export const StoreGone: Story = {
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        apiError("/api/v1/stores/:storeId", 404, "RESOURCE_NOT_FOUND", "Store is not available"),
        ...catalogHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("지금은 주문할 수 없는 매장이에요")).toBeVisible();
  },
};

export const Loading: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, pending("/api/v1/stores/:storeId")] } },
};
