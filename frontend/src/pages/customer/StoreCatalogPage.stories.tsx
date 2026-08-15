import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, catalogHandlers, ids, pending } from "../../../.storybook/fixtures";
import { StoreCatalogPage } from "./CustomerPages";

const meta = {
  title: "Pages/Customer/StoreCatalog",
  component: StoreCatalogPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: { component: "판매 가능한 메뉴와 pickup slot을 함께 확인하고 한 주문 intent를 만드는 route입니다." },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/stores/:storeId", initialEntry: `/app/stores/${ids.store}` },
    msw: { handlers: catalogHandlers },
  },
} satisfies Meta<typeof StoreCatalogPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const MenuAndPickupSelection: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: /오트 라떼/ }));
    await userEvent.click(canvas.getByRole("button", { name: /오후 12:20/ }));
    await expect(canvas.getByRole("button", { name: "₩6,400 주문하기" })).toBeEnabled();
  },
};

export const EmptyMenu: Story = {
  parameters: {
    msw: { handlers: [
      http.get("/api/v1/stores/:storeId/menus", () => HttpResponse.json({ items: [] })),
      catalogHandlers[1],
    ] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("판매 중인 메뉴가 없어요")).toBeVisible();
  },
};

export const RecoverableError: Story = {
  parameters: { msw: { handlers: [apiError("/api/v1/stores/:storeId/menus"), catalogHandlers[1]] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};

export const Loading: Story = {
  parameters: { msw: { handlers: [pending("/api/v1/stores/:storeId/menus"), pending("/api/v1/stores/:storeId/pickup-slots")] } },
};
