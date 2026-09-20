import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, http } from "msw";
import { ids, merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { StoreSchedulingPage } from "./StoreSchedulingPage";

const days = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
const profile = { version: 3, addressLine: "서울 중구 세종대로 110", directionsHint: "시청역 5번 출구", operatingHours: { timezone: "Asia/Seoul", days: days.map(dayOfWeek => ({ dayOfWeek, closed: false, opensAt: "08:00", closesAt: "20:00" })) } };
let current = structuredClone(profile);
const membership = (role = "OWNER") => http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([{ storeId: ids.store, storeName: "시청점", membershipRole: role }]));
const handlers = [membership(), ...merchantSignedInHandlers,
  http.get("/api/v1/stores/:storeId/image", () => HttpResponse.json({})),
  http.get("/api/v1/stores/:storeId/customer-display", () => HttpResponse.json(current)),
  http.put("/api/v1/stores/:storeId/customer-display", async ({ request }) => { const body = await request.json() as typeof profile & { expectedVersion: number }; expect(body.expectedVersion).toBe(3); expect(body.operatingHours.days).toHaveLength(7); current = { ...body, version: 4 }; return HttpResponse.json(current); }),
];
const meta = {
  title: "Pages/Store/Scheduling", component: StoreSchedulingPage, tags: ["autodocs"],
  beforeEach: () => { current = structuredClone(profile); sessionStorage.removeItem("beanflow.merchant.selected-store"); },
  parameters: { a11y: { test: "error" }, docs: { description: { component: "점주는 고객 공개 정보, 매장 이미지와 즉시 주문의 요일별 영업시간을 관리합니다." }, story: { inline: false, height: "1000px" } }, routing: { path: "/store/management", initialEntry: "/store/management", surface: "store" }, msw: { handlers } },
} satisfies Meta<typeof StoreSchedulingPage>;
export default meta;
type Story = StoryObj<typeof meta>;
export const WeeklyHours: Story = { play: async ({ canvas }) => {
  const address = await canvas.findByLabelText("고객에게 표시할 주소"); await userEvent.clear(address); await userEvent.type(address, "서울 중구 세종대로 120");
  await expect(canvas.getByLabelText("월요일 시작")).toHaveValue("08:00");
  await userEvent.click(canvas.getByRole("button", { name: "공개 정보 저장" }));
  await expect(await canvas.findByText("공개 정보를 저장했습니다.")).toBeVisible();
  await waitFor(() => expect(canvas.getByLabelText("고객에게 표시할 주소")).toHaveValue("서울 중구 세종대로 120"));
  await userEvent.click(canvas.getByLabelText("월요일 휴무"));
  await expect(canvas.queryByText("공개 정보를 저장했습니다.")).not.toBeInTheDocument();
  await expect(canvas.getByLabelText("매장 선택")).toBeDisabled();
} };
export const StaffReadOnly: Story = { parameters: { msw: { handlers: [membership("STAFF"), ...handlers.slice(1)] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("고객 공개 정보는 점주가 변경할 수 있습니다")).toBeVisible(); await expect(canvas.queryByLabelText("고객에게 표시할 주소")).not.toBeInTheDocument(); await expect(canvas.queryByText(/픽업 시간과 정원/)).not.toBeInTheDocument(); } };
export const VersionConflict: Story = { parameters: { msw: { handlers: [http.put("/api/v1/stores/:storeId/customer-display", () => HttpResponse.json({ code: "MERCHANT_CONTENT_STALE", message: "changed" }, { status: 409 })), ...handlers] } }, play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("button", { name: "공개 정보 저장" })); await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByText("공개 정보를 저장했습니다.")).not.toBeInTheDocument(); } };
export const Unavailable: Story = { parameters: { msw: { handlers: [http.get("/api/v1/stores/:storeId/customer-display", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", message: "unavailable" }, { status: 503 })), ...handlers] } }, play: async ({ canvas }) => { await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByLabelText("고객에게 표시할 주소")).not.toBeInTheDocument(); } };
