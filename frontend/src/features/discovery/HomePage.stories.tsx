import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, homeHandlers, orderListHandlers, pending, signedInHandlers } from "../../../.storybook/fixtures";
import { CustomerHomePage } from "./HomePage";

const meta = {
  title: "Pages/Customer/Home",
  component: CustomerHomePage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "로그인한 고객의 첫 화면입니다. 진행 중인 주문과 추천 매장을 서버에서 읽으며, 위치 좌표는 요청하지 않습니다.",
      },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app", initialEntry: "/app" },
  },
} satisfies Meta<typeof CustomerHomePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ActiveOrderAndRecommendations: Story = {
  parameters: { msw: { handlers: homeHandlers } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("아이스 아메리카노 외 1건")).toBeVisible();
    await expect(await canvas.findByText("최근 주문한 매장")).toBeVisible();
  },
};

export const NothingInProgress: Story = {
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        http.get("/api/v1/me/orders", () => HttpResponse.json({ items: [], page: {} })),
        http.get("/api/v1/me/store-recommendations", () => HttpResponse.json({ items: [] })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("진행 중인 주문이 없어요")).toBeVisible();
  },
};

export const RecommendationsUnavailable: Story = {
  parameters: {
    msw: { handlers: [...signedInHandlers, ...orderListHandlers(), apiError("/api/v1/me/store-recommendations")] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};

export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        pending("/api/v1/me/orders"),
        http.get("/api/v1/me/store-recommendations", () => HttpResponse.json({ items: [] })),
      ],
    },
  },
};
