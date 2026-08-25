import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { customerStore, favoriteHandlers, ids, signedInHandlers } from "../../../.storybook/fixtures";
import { cart } from "./cart";
import { StoreDetailPage } from "./StoreDetailPage";

const demoImage = (name: string) => ({
  url: `/demo/catalog/${name}.webp`,
  expiresAt: "2099-01-01T00:00:00Z",
});

const imageCatalogMenus = [
  {
    menuId: "20000000-0000-4000-8000-000000000101",
    name: "아메리카노",
    basePriceKrw: 4_500,
    currency: "KRW",
    available: true,
    options: [{ optionId: "21000000-0000-4000-8000-000000000101", name: "샷 추가", additionalPriceKrw: 500, available: true }],
    image: demoImage("americano"),
  },
  { menuId: "20000000-0000-4000-8000-000000000102", name: "카페 라떼", basePriceKrw: 5_500, currency: "KRW", available: true, options: [], image: demoImage("cafe-latte") },
  { menuId: "20000000-0000-4000-8000-000000000103", name: "카라멜 마키아또", basePriceKrw: 6_200, currency: "KRW", available: true, options: [], image: demoImage("caramel-macchiato") },
  { menuId: "20000000-0000-4000-8000-000000000104", name: "딸기 요거트 스무디", basePriceKrw: 6_500, currency: "KRW", available: true, options: [], image: demoImage("smoothie") },
  { menuId: "20000000-0000-4000-8000-000000000105", name: "바스크 치즈케이크", basePriceKrw: 6_800, currency: "KRW", available: true, options: [], image: demoImage("slice-cake") },
  { menuId: "20000000-0000-4000-8000-000000000106", name: "플레인 베이글", basePriceKrw: 4_200, currency: "KRW", available: true, options: [], image: demoImage("bagel") },
  { menuId: "20000000-0000-4000-8000-000000000107", name: "햄 치즈 샌드위치", basePriceKrw: 7_200, currency: "KRW", available: true, options: [], image: demoImage("sandwich") },
  { menuId: "20000000-0000-4000-8000-000000000108", name: "그릭 요거트", basePriceKrw: 5_900, currency: "KRW", available: true, options: [], image: demoImage("yogurt") },
  { menuId: "20000000-0000-4000-8000-000000000109", name: "마카롱 세트", basePriceKrw: 7_500, currency: "KRW", available: true, options: [], image: demoImage("macaron") },
];

const meta = {
  title: "Pages/Customer/Store detail image catalog",
  component: StoreDetailPage,
  tags: ["autodocs"],
  parameters: {
    layout: "fullscreen",
    docs: {
      description: {
        component: "이미지가 있는 9개 메뉴를 고객 앱 셸 안에서 보여주며, 기존 옵션·수량·장바구니 흐름도 그대로 사용할 수 있습니다.",
      },
      story: { inline: false, height: "844px" },
    },
    routing: { surface: "customer", path: "/app/stores/:storeId", initialEntry: `/app/stores/${ids.store}` },
  },
} satisfies Meta<typeof StoreDetailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ImageCatalog: Story = {
  parameters: {
    a11y: { test: "error" },
    msw: {
      handlers: [
        ...signedInHandlers,
        http.get("/api/v1/stores/:storeId", () => HttpResponse.json({ ...customerStore, name: "성수 로스터리" })),
        http.get("/api/v1/stores/:storeId/menus", () => HttpResponse.json({ items: imageCatalogMenus })),
        http.get("/api/v1/stores/:storeId/pickup-slots", () => HttpResponse.json({ items: [
          { pickupSlotId: ids.slot, startsAt: "2026-08-15T03:20:00Z", endsAt: "2026-08-15T03:30:00Z", remainingCapacity: 7 },
        ] })),
        ...favoriteHandlers,
      ],
    },
  },
  beforeEach: () => {
    cart.clear();
  },
  play: async ({ canvas }) => {
    const firstMenu = await canvas.findByRole("button", { name: /아메리카노/ });
    const menuRows = canvas.getAllByRole("button").filter((button) => button.classList.contains("menu-card"));
    await expect(menuRows).toHaveLength(9);

    await userEvent.click(firstMenu);
    await expect(firstMenu).toHaveAttribute("aria-expanded", "true");
    await userEvent.click(canvas.getByRole("checkbox", { name: /샷 추가/ }));
    await userEvent.click(canvas.getByRole("button", { name: "수량 늘리기" }));
    await expect(canvas.getByRole("button", { name: "₩10,000 담기" })).toBeVisible();
  },
};
