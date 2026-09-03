import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { customerStore } from "../../../.storybook/fixtures";
import { RefreshStoreCard } from "./RefreshShared";

const meta = {
  title: "Patterns/Commerce/Store card",
  component: RefreshStoreCard,
  tags: ["autodocs"],
  args: { store: customerStore },
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: {
        component: "고객 검색·추천·즐겨찾기에서 같은 계약 필드와 신규 presentation 스타일을 사용하는 매장 링크 패턴입니다.",
      },
    },
    routing: { surface: "customer", path: "/app/favorites", initialEntry: "/app/favorites" },
  },
} satisfies Meta<typeof RefreshStoreCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Orderable: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("link", { name: /시청점/ })).toBeVisible();
    await expect(canvas.getByText("주문 가능")).toBeVisible();
  },
};

export const OrderingPaused: Story = {
  args: {
    store: { ...customerStore, orderingAvailable: false },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText("주문 쉬는 중")).toBeVisible();
  },
};
