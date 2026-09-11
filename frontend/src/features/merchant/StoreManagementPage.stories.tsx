import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { ids, merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { StoreManagementPage } from "./StoreManagementPage";
const meta = {
  title: "Pages/Store/Business management", component: StoreManagementPage, tags: ["autodocs"],
  args: { catalogContent: <p>현재 메뉴 카탈로그</p> },
  parameters: { a11y: { test: "error" }, docs: { description: { component: "현재 메뉴 관리, 고객 공개 정보와 실제 픽업 시간 관리의 진입입니다. 거래 내역은 정산 화면으로 연결합니다." }, story: { inline: false, height: "820px" } }, routing: { path: "/store/management", initialEntry: "/store/management" }, msw: { handlers: [http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([{ storeId: ids.store, storeName: "시청점", membershipRole: "STAFF" }])), http.get("/api/v1/stores/:storeId/pickup-slot-management", () => HttpResponse.json({ items: [], nextCursor: null })), ...merchantSignedInHandlers] } },
} satisfies Meta<typeof StoreManagementPage>;
export default meta;
type Story = StoryObj<typeof meta>;
export const MenuAndPricing: Story = { play: async ({ canvas }) => { await expect(canvas.getByText("현재 메뉴 카탈로그")).toBeVisible(); await expect(canvas.getByRole("link", { name: "정산 내역" })).toHaveAttribute("href", "/store/settlements"); } };
export const HoursAndPickup: Story = { args: { initialWorkspace: "hours" }, play: async ({ canvas }) => { await expect(await canvas.findByRole("button", { name: "새 픽업 시간" })).toBeVisible(); } };
export const KeyboardWorkspaceChange: Story = { play: async ({ canvas }) => { canvas.getByRole("tab", { name: "영업시간과 픽업" }).focus(); await userEvent.keyboard("{Enter}"); await expect(await canvas.findByRole("button", { name: "새 픽업 시간" })).toBeVisible(); } };

export const PickupDraftLocksNavigation: Story = { args: { initialWorkspace: "hours" }, play: async ({ canvas }) => {
  await userEvent.click(await canvas.findByRole("button", { name: "새 픽업 시간" }));
  await userEvent.type(canvas.getByLabelText("정원"), "12");
  await expect(canvas.getByRole("tab", { name: "메뉴와 가격" })).toBeDisabled();
  await expect(canvas.getByLabelText("매장 선택")).toBeDisabled();
  await userEvent.click(canvas.getByRole("button", { name: "편집 닫기" }));
  await userEvent.click(canvas.getByRole("tab", { name: "메뉴와 가격" }));
  await expect(canvas.getByText("현재 메뉴 카탈로그")).toBeVisible();
} };
