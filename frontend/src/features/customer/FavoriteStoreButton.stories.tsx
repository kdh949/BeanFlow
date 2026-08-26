import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { customerStore, ids } from "../../../.storybook/fixtures";
import { FavoriteStoreButton } from "./FavoriteStoresPage";

const meta = {
  title: "Patterns/Customer/Favorite store action",
  component: FavoriteStoreButton,
  tags: ["autodocs"],
  args: { storeId: ids.store, storeName: "시청점" },
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: { component: "매장 상세에서 즐겨찾기 상태를 조회하고 멱등 PUT/DELETE로 전환하는 재사용 action입니다." },
      story: { inline: false, height: "320px" },
    },
  },
  beforeEach: () => {
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=storybook-customer-csrf; path=/";
  },
} satisfies Meta<typeof FavoriteStoreButton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const NotSaved: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/me/favorite-stores", () => HttpResponse.json({ items: [] }))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("button", { name: "시청점 즐겨찾기 추가" })).toHaveAttribute("aria-pressed", "false");
  },
};

export const Saved: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/me/favorite-stores", () => HttpResponse.json({ items: [customerStore] }))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("button", { name: "시청점 즐겨찾기 해제" })).toHaveAttribute("aria-pressed", "true");
  },
};

export const LimitConflict: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        http.get("/api/v1/me/favorite-stores", () => HttpResponse.json({ items: [] })),
        http.put("/api/v1/me/favorite-stores/:storeId", () => HttpResponse.json({
          code: "FAVORITE_STORE_LIMIT_EXCEEDED",
          message: "즐겨찾기는 최대 200개까지 저장할 수 있습니다.",
        }, { status: 409 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "시청점 즐겨찾기 추가" }));
    await expect(await canvas.findByRole("alert")).toHaveTextContent("최대 200개");
  },
};
