import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { HttpResponse, http } from "msw";
import { homeHandlers, orderListHandlers, signedInHandlers } from "../../../.storybook/fixtures";
import { RefreshCustomerHomePage } from "./CustomerDiscoveryPages";

const meta = {
  title: "Pages/Refresh/Customer/Home",
  component: RefreshCustomerHomePage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    layout: "fullscreen",
    docs: { story: { inline: false, height: "844px" } },
    routing: { path: "/app", initialEntry: "/app", surface: "refresh-customer" },
    msw: { handlers: homeHandlers },
  },
} satisfies Meta<typeof RefreshCustomerHomePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ActiveOrderAndRecommendations: Story = {
  play: async ({ canvas, canvasElement }) => {
    await expect(await canvas.findByRole("link", { name: /A-142 준비 완료/ })).toBeVisible();
    const recentStoreLabels = await canvas.findAllByText("최근 주문한 매장");
    await expect(recentStoreLabels.length).toBeGreaterThan(0);
    await expect(canvas.queryByText("IN PROGRESS")).not.toBeInTheDocument();
    await expect(canvas.queryByText("FOR YOU")).not.toBeInTheDocument();
    await expect(canvasElement.querySelector(".bf-status")).toBeNull();
  },
};

export const NothingInProgress: Story = {
  parameters: {
    msw: { handlers: [...signedInHandlers, ...orderListHandlers([]), http.get("/api/v1/me/store-recommendations", () => HttpResponse.json({ items: [] }))] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("진행 중인 주문이 없어요")).toBeVisible();
    await expect(await canvas.findByText("추천할 매장이 아직 없어요")).toBeVisible();
  },
};

export const RecommendationFailure: Story = {
  parameters: {
    msw: { handlers: [...signedInHandlers, ...orderListHandlers(), http.get("/api/v1/me/store-recommendations", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", message: "추천 서비스를 사용할 수 없습니다.", correlationId: "REQ-REFRESH-42" }, { status: 503 }))] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};
