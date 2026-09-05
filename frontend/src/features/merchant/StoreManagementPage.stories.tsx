import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { StoreManagementPage } from "./StoreManagementPage";

const catalog = [
  { menuId: "menu-demo-01", name: "시그니처 라떼", category: "커피", priceKrw: 6_000, state: "ACTIVE", optionSummary: "온도 2 · 사이즈 2" },
  { menuId: "menu-demo-02", name: "제주 말차 크림", category: "논커피", priceKrw: 6_500, state: "SOLD_OUT", optionSummary: "온도 2" },
] as const;
const inventory = [
  { inventoryId: "inventory-demo-01", name: "원두 1kg", onHand: 18, reserved: 4, unit: "봉", state: "AVAILABLE" },
  { inventoryId: "inventory-demo-02", name: "제주 말차", onHand: 2, reserved: 2, unit: "팩", state: "DEPLETED" },
] as const;
const schedule = [
  { day: "월", hours: "08:00–20:00", closed: false },
  { day: "화", hours: "08:00–20:00", closed: false },
  { day: "수", hours: "휴무", closed: true },
] as const;
const metrics = [
  { label: "완료 주문", value: "184건", change: "전주 대비 +12%" },
  { label: "순매출", value: "₩1,842,000", change: "전주 대비 +8%" },
  { label: "평균 준비", value: "7분 42초", change: "전주 대비 36초 단축" },
] as const;

const meta = {
  title: "Pages/Store/Business management",
  component: StoreManagementPage,
  tags: ["autodocs"],
  args: {
    scenario: "ready",
    catalog,
    inventory,
    schedule,
    pickupPolicy: { acceptingOrders: true, pickupEnabled: true, nextWindow: "오늘 14:20–14:30", version: 7 },
    benefitPolicy: { pointRateLabel: "결제액의 3%", couponPolicyLabel: "스토어 부담 최대 50%", campaignCount: 2 },
    metrics,
  },
  parameters: {
    docs: { description: { component: "점주가 메뉴, 재고, 영업시간, 고객 혜택과 매출을 한곳에서 확인하는 화면입니다." }, story: { inline: false, height: "820px" } },
    routing: { path: "/store/management", initialEntry: "/store/management" },
  },
} satisfies Meta<typeof StoreManagementPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const MenuAndPricing: Story = {
  args: { initialWorkspace: "catalog" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "메뉴와 가격" })).toBeVisible();
    await expect(canvas.getByText("시그니처 라떼")).toBeVisible();
    await expect(canvas.getByText("판매 중")).toBeVisible();
    await expect(canvas.queryAllByText(/ACTIVE|SOLD_OUT|writer|Provider|immutable|workspace|원장/i)).toHaveLength(0);
  },
};

export const Inventory: Story = {
  args: { initialWorkspace: "inventory" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "재고" })).toBeVisible();
    await expect(canvas.getByText("남은 14봉")).toBeVisible();
    await expect(canvas.getByText("재고 없음")).toBeVisible();
    await expect(canvas.queryAllByText(/AVAILABLE|DEPLETED/)).toHaveLength(0);
  },
};

export const HoursAndPickup: Story = {
  args: { initialWorkspace: "hours" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "영업시간과 픽업" })).toBeVisible();
    await expect(canvas.getByText("오늘 14:20–14:30")).toBeVisible();
  },
};

export const StoreBenefits: Story = {
  args: { initialWorkspace: "benefits" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "포인트와 쿠폰" })).toBeVisible();
  },
};

export const SalesAnalytics: Story = {
  args: { initialWorkspace: "analytics" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "매출" })).toBeVisible();
    await expect(canvas.getByText("₩1,842,000")).toBeVisible();
  },
};

export const KeyboardWorkspaceChange: Story = {
  args: { initialWorkspace: "catalog" },
  play: async ({ canvas }) => {
    const inventoryTab = await canvas.findByRole("tab", { name: /재고/ });
    await userEvent.click(inventoryTab);
    await expect(canvas.getByRole("heading", { name: "재고" })).toBeVisible();
  },
};

export const ContractPending: Story = {
  args: { scenario: "contract-pending", initialWorkspace: "catalog", catalog: [], inventory: [], schedule: [], metrics: [] },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toHaveTextContent("메뉴 관리를 준비하고 있습니다");
  },
};
