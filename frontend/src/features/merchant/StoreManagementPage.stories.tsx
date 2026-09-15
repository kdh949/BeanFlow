import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { ids, merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { StoreManagementPage } from "./StoreManagementPage";
const meta = {
  title: "Pages/Store/Business management", component: StoreManagementPage, tags: ["autodocs"],
  args: { catalogContent: <p>현재 메뉴 카탈로그</p> },
  parameters: { a11y: { test: "error" }, docs: { description: { component: "현재 메뉴, 고객 공개 정보, 즉시 주문 영업시간과 매장 이미지를 관리하는 진입입니다. 거래 내역은 정산 화면으로 연결합니다." }, story: { inline: false, height: "820px" } }, routing: { path: "/store/management", initialEntry: "/store/management" }, msw: { handlers: [http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([{ storeId: ids.store, storeName: "시청점", membershipRole: "OWNER" }])), http.get("/api/v1/stores/:storeId/support-order-change-requests", () => HttpResponse.json({ items: [], nextCursor: null })), http.get("/api/v1/stores/:storeId/customer-display", () => HttpResponse.json({ version: 3, addressLine: "서울 중구 세종대로 110", directionsHint: "시청역 5번 출구", operatingHours: null })), http.get("/api/v1/stores/:storeId/image", () => HttpResponse.json({})), ...merchantSignedInHandlers] } },
} satisfies Meta<typeof StoreManagementPage>;
export default meta;
type Story = StoryObj<typeof meta>;
export const MenuAndPricing: Story = { play: async ({ canvas }) => { await expect(canvas.getByText("현재 메뉴 카탈로그")).toBeVisible(); await expect(canvas.getByRole("link", { name: "정산 내역" })).toHaveAttribute("href", "/store/settlements"); } };
export const HoursAndStorefront: Story = { args: { initialWorkspace: "hours" }, play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "고객 공개 정보와 주간 영업시간" })).toBeVisible(); await expect(await canvas.findByRole("checkbox", { name: /주간 영업시간 설정/ })).not.toBeChecked(); } };
export const KeyboardWorkspaceChange: Story = { play: async ({ canvas }) => { canvas.getByRole("tab", { name: "영업시간과 이미지" }).focus(); await userEvent.keyboard("{Enter}"); await expect(await canvas.findByRole("heading", { name: "고객 공개 정보와 주간 영업시간" })).toBeVisible(); } };

export const SupportOrderConsent: Story = { args: { initialWorkspace: "support" }, play: async ({ canvas }) => { await expect(await canvas.findByText("현재 조회 구간에 동의할 주문 변경이 없습니다")).toBeVisible(); } };
export const HoursDraftLocksNavigation: Story = { args: { initialWorkspace: "hours" }, play: async ({ canvas }) => {
  await userEvent.type(await canvas.findByLabelText("고객에게 표시할 주소"), " 추가");
  await expect(canvas.getByRole("tab", { name: "메뉴와 가격" })).toBeDisabled();
  await expect(canvas.getByLabelText("매장 선택")).toBeDisabled();
  await userEvent.click(canvas.getByRole("button", { name: "현재 공개 정보 다시 읽기" }));
  await userEvent.click(canvas.getByRole("tab", { name: "메뉴와 가격" }));
  await expect(canvas.getByText("현재 메뉴 카탈로그")).toBeVisible();
} };
