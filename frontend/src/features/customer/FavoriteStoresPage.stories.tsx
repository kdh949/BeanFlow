import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, customerDisplay, customerStore, pending } from "../../../.storybook/fixtures";
import { FavoriteStoresPage } from "./FavoriteStoresPage";

const meta = {
  title: "Pages/Customer/Favorite stores",
  component: FavoriteStoresPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: { component: "현재 고객이 저장한 매장을 조회하고 서버 멱등 DELETE로 해제하는 목록입니다." },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/favorites", initialEntry: "/app/favorites" },
  },
} satisfies Meta<typeof FavoriteStoresPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SavedStores: Story = {
  parameters: {
    msw: {
      handlers: [http.get("/api/v1/me/favorite-stores", () => HttpResponse.json({ items: [
        customerStore,
        {
          storeId: "10000000-0000-4000-8000-000000000002",
          name: "광화문점",
          orderingAvailable: false,
          pickupAvailable: false,
          customerDisplay: { ...customerDisplay, operatingStatus: "CLOSED" },
        },
      ] }))],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("region", { name: "즐겨찾기 매장" })).toBeVisible();
    await expect(canvas.getByRole("button", { name: "시청점 즐겨찾기 해제" })).toBeVisible();
  },
};

export const Empty: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/me/favorite-stores", () => HttpResponse.json({ items: [] }))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("즐겨찾기한 매장이 없어요")).toBeVisible();
  },
};

export const Unavailable: Story = {
  parameters: { msw: { handlers: [apiError("/api/v1/me/favorite-stores")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};

export const Loading: Story = {
  parameters: { msw: { handlers: [pending("/api/v1/me/favorite-stores")] } },
};
