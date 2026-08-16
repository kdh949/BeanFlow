import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, searchHandlers, signedInHandlers } from "../../../.storybook/fixtures";
import { StoreSearchPage } from "./StoreSearchPage";

const meta = {
  title: "Pages/Customer/Store search",
  component: StoreSearchPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "매장·브랜드·지역·메뉴 이름으로 찾습니다. 검색 전, 결과 없음, 조회 실패를 서로 다른 상태로 구분합니다.",
      },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/stores", initialEntry: "/app/stores?query=%EC%8B%9C%EC%B2%AD" },
  },
} satisfies Meta<typeof StoreSearchPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Results: Story = {
  parameters: { msw: { handlers: searchHandlers } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("시청점")).toBeVisible();
  },
};

export const NoResults: Story = {
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        http.get("/api/v1/stores/search", () => HttpResponse.json({ items: [], page: {}, distanceAvailable: false })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/검색 결과가 없어요/)).toBeVisible();
  },
};

/** No query yet is a prompt, not an empty result. */
export const BeforeSearching: Story = {
  parameters: {
    routing: { path: "/app/stores", initialEntry: "/app/stores" },
    msw: { handlers: searchHandlers },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("찾고 싶은 매장을 알려주세요")).toBeVisible();
  },
};

export const SearchUnavailable: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, apiError("/api/v1/stores/search")] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
  },
};
