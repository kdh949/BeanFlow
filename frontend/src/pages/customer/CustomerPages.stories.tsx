import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, nearbyHandlers, pending } from "../../../.storybook/fixtures";
import { CustomerHomePage } from "./CustomerPages";

const meta = {
  title: "Pages/Customer/Home",
  component: CustomerHomePage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: { component: "위치 동의 뒤 가까운 매장을 탐색하는 고객 app의 현재 home route입니다." },
      story: { inline: false, height: "720px" },
    },
    routing: {
      path: "/app",
      initialEntry: "/app?lat=37.5665&lng=126.9780",
    },
  },
} satisfies Meta<typeof CustomerHomePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const NearbyStores: Story = {
  parameters: { msw: { handlers: nearbyHandlers } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("시청점")).toBeVisible();
  },
};

export const LocationRequired: Story = {
  parameters: {
    routing: {
      path: "/app",
      initialEntry: "/app",
    },
  },
};

export const EmptyRadius: Story = {
  parameters: {
    msw: { handlers: [http.get("/api/v1/stores/nearby", () => HttpResponse.json({ items: [] }))] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("가까운 매장이 없어요")).toBeVisible();
  },
};

export const RecoverableError: Story = {
  parameters: { msw: { handlers: [apiError("/api/v1/stores/nearby")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};

export const Loading: Story = {
  parameters: { msw: { handlers: [pending("/api/v1/stores/nearby")] } },
};
