import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, customerDisplay, searchHandlers, signedInHandlers } from "../../../.storybook/fixtures";
import { StoreSearchPage } from "./StoreSearchPage";

const meta = {
  title: "Pages/Customer/Store search",
  component: StoreSearchPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
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

const demoImage = (name: string) => ({
  url: `/demo/catalog/${name}.webp`,
  expiresAt: "2099-01-01T00:00:00Z",
});

const imageCatalogStores = [
  ["10000000-0000-4000-8000-000000000101", "성수 로스터리", "오트 라떼"],
  ["10000000-0000-4000-8000-000000000102", "연남 아틀리에", "아메리카노"],
  ["10000000-0000-4000-8000-000000000103", "을지로 브루어스", "카페 라떼"],
  ["10000000-0000-4000-8000-000000000104", "망원 코너", "카라멜 마키아또"],
  ["10000000-0000-4000-8000-000000000105", "서촌 그라운드", "플레인 베이글"],
  ["10000000-0000-4000-8000-000000000106", "한남 플랜트", "딸기 요거트 스무디"],
  ["10000000-0000-4000-8000-000000000107", "합정 포트", "햄 치즈 샌드위치"],
  ["10000000-0000-4000-8000-000000000108", "문래 다크룸", "바스크 치즈케이크"],
  ["10000000-0000-4000-8000-000000000109", "잠실 데일리", "그릭 요거트"],
  ["10000000-0000-4000-8000-000000000110", "압구정 테이블", "마카롱 세트"],
  ["10000000-0000-4000-8000-000000000111", "동대문 웨이브", "콜드브루"],
  ["10000000-0000-4000-8000-000000000112", "시청 테라스", "오늘의 필터 커피"],
] as const;

export const Results: Story = {
  parameters: { msw: { handlers: searchHandlers } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("시청점")).toBeVisible();
    await expect(await canvas.findByText("주문 불가")).toBeVisible();
    await expect(await canvas.findByText("영업시간 아님")).toBeVisible();
  },
};

/** A dense, phone-sized customer surface with all image-bearing store cards. */
export const StorefrontImageResults: Story = {
  parameters: {
    layout: "fullscreen",
    a11y: { test: "error" },
    docs: { story: { inline: false, height: "844px" } },
    routing: { surface: "customer", path: "/app/stores", initialEntry: "/app/stores?query=%EC%B9%B4%ED%8E%98" },
    msw: {
      handlers: [
        ...signedInHandlers,
        http.get("/api/v1/stores/search", () => HttpResponse.json({
          items: imageCatalogStores.map(([storeId, name, menuName], index) => ({
            storeId,
            name,
            matchReason: ["MENU_NAME"],
            orderingAvailable: true,
            pickupAvailable: true,
            nextPickupWindow: { startsAt: "2026-08-15T03:20:00Z", endsAt: "2026-08-15T03:30:00Z" },
            customerDisplay,
            matchedMenus: [{ menuId: `20000000-0000-4000-8000-0000000001${String(index + 1).padStart(2, "0")}`, name: menuName }],
            image: demoImage(`store-${String(index + 1).padStart(2, "0")}`),
          })),
          page: {},
          distanceAvailable: false,
        })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("link", { name: /성수 로스터리/ })).toHaveAttribute(
      "href",
      "/app/stores/10000000-0000-4000-8000-000000000101",
    );
    const storeLinks = canvas.getAllByRole("link").filter((link) => link.getAttribute("href")?.startsWith("/app/stores/"));
    await expect(storeLinks).toHaveLength(12);
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

export const LocationPermissionDenied: Story = {
  tags: ["!autodocs"],
  parameters: {
    routing: { path: "/app/stores", initialEntry: "/app/stores" },
    msw: { handlers: searchHandlers },
  },
  beforeEach: () => {
    const descriptor = Object.getOwnPropertyDescriptor(navigator, "geolocation");
    Object.defineProperty(navigator, "geolocation", {
      configurable: true,
      value: {
        getCurrentPosition: (_success: PositionCallback, failure: PositionErrorCallback) =>
          failure({ code: 1, PERMISSION_DENIED: 1 } as GeolocationPositionError),
      },
    });
    return () => {
      if (descriptor) Object.defineProperty(navigator, "geolocation", descriptor);
      else Reflect.deleteProperty(navigator, "geolocation");
    };
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "현재 위치로 찾기" }));
    await expect(await canvas.findByText(/위치 권한이 꺼져 있어/)).toBeVisible();
    await expect(canvas.getByLabelText("검색어")).toBeEnabled();
  },
};
