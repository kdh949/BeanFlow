import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { customerStore, searchHandlers, signedInHandlers } from "../../../.storybook/fixtures";
import { RefreshStoreSearchPage } from "./CustomerDiscoveryPages";

const meta = {
  title: "Pages/Refresh/Customer/Store search",
  component: RefreshStoreSearchPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    layout: "fullscreen",
    docs: { story: { inline: false, height: "844px" } },
    routing: { path: "/app/stores", initialEntry: "/app/stores?query=%EC%8B%9C%EC%B2%AD", surface: "refresh-customer" },
    msw: { handlers: searchHandlers },
  },
} satisfies Meta<typeof RefreshStoreSearchPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Results: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("시청점")).toBeVisible();
    await expect(await canvas.findAllByText("주문 쉬는 중")).toHaveLength(2);
    await expect(canvas.queryByText(/총 .*건/)).not.toBeInTheDocument();
  },
};

export const QueryHelper: Story = {
  parameters: { routing: { path: "/app/stores", initialEntry: "/app/stores", surface: "refresh-customer" } },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("button", { name: "라떼" }));
    await expect(await canvas.findByDisplayValue("라떼")).toBeVisible();
    await expect(await canvas.findByText("시청점")).toBeVisible();
  },
};

export const NoResults: Story = {
  parameters: {
    msw: { handlers: [...signedInHandlers, http.get("/api/v1/stores/search", () => HttpResponse.json({ items: [], page: {}, distanceAvailable: false }))] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/검색 결과가 없어요/)).toBeVisible();
  },
};

export const LocationPermissionDenied: Story = {
  parameters: { routing: { path: "/app/stores", initialEntry: "/app/stores", surface: "refresh-customer" } },
  beforeEach: () => {
    Object.defineProperty(navigator, "geolocation", { configurable: true, value: { getCurrentPosition: (_success: PositionCallback, failure: PositionErrorCallback) => failure({ code: 1, PERMISSION_DENIED: 1 } as GeolocationPositionError) } });
  },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("button", { name: "현재 위치로 가까운 매장 찾기" }));
    await expect(await canvas.findByText(/위치 권한이 꺼져 있어/)).toBeVisible();
    await expect(canvas.getByLabelText("매장과 메뉴 검색")).toBeEnabled();
  },
};

export const OrderableFilter: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, http.get("/api/v1/stores/search", ({ request }) => { const params = new URL(request.url).searchParams; return HttpResponse.json({ items: params.get("openOnly") === "true" && params.get("sort") === "relevance" ? [customerStore] : [{ ...customerStore, name: "주문 쉬는 매장", orderingAvailable: false }], page: {}, distanceAvailable: false }); })] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("주문 쉬는 매장")).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "주문 가능한 매장만" }));
    await expect(await canvas.findByText("시청점")).toBeVisible();
    await expect(canvas.queryByText("주문 쉬는 매장")).not.toBeInTheDocument();
    await expect(canvas.getByRole("combobox", { name: "검색 정렬" })).toHaveValue("relevance");
  },
};
