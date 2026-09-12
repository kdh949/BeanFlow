import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, http } from "msw";
import MockDate from "mockdate";
import { ids, merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { StoreSchedulingPage } from "./StoreSchedulingPage";

const days = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
const profile = { version: 3, addressLine: "서울 중구 세종대로 110", directionsHint: "시청역 5번 출구", operatingHours: { timezone: "Asia/Seoul", days: days.map(dayOfWeek => ({ dayOfWeek, closed: false, opensAt: "08:00", closesAt: "20:00" })) } };
const slot = { slotId: "40000000-0000-4000-8000-000000000001", storeId: ids.store, startsAt: "2026-10-02T01:00:00Z", endsAt: "2026-10-02T01:10:00Z", capacity: 20, reservedCount: 1, confirmedCount: 2, version: 4 };
let current = structuredClone(profile);
let slots = [slot];
const membership = (role = "OWNER") => http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([{ storeId: ids.store, storeName: "시청점", membershipRole: role }]));
const handlers = [membership(), ...merchantSignedInHandlers,
  http.get("/api/v1/stores/:storeId/image", () => HttpResponse.json({})),
  http.get("/api/v1/stores/:storeId/customer-display", () => HttpResponse.json(current)),
  http.put("/api/v1/stores/:storeId/customer-display", async ({ request }) => { const body = await request.json() as typeof profile & { expectedVersion: number }; expect(body.expectedVersion).toBe(3); expect(body.operatingHours.days).toHaveLength(7); current = { ...body, version: 4 }; return HttpResponse.json(current); }),
  http.get("/api/v1/stores/:storeId/pickup-slot-management", () => HttpResponse.json({ items: slots, nextCursor: null })),
  http.get("/api/v1/stores/:storeId/pickup-slot-management/:slotId", () => HttpResponse.json(slot)),
  http.post("/api/v1/stores/:storeId/pickup-slot-management", async ({ request }) => { expect(request.headers.get("Idempotency-Key")).toBeTruthy(); expect(request.headers.get("X-BEANFLOW-CSRF")).toBeTruthy(); const body = await request.json() as typeof slot; const created = { ...slot, ...body, slotId: "40000000-0000-4000-8000-000000000002", reservedCount: 0, confirmedCount: 0, version: 0 }; slots = [...slots, created]; return HttpResponse.json(created, { status: 201 }); }),
];
const meta = {
  title: "Pages/Store/Scheduling", component: StoreSchedulingPage, tags: ["autodocs"],
  beforeEach: () => { MockDate.set("2026-10-01T00:00:00Z"); current = structuredClone(profile); slots = [slot]; sessionStorage.removeItem("beanflow.merchant.selected-store"); return () => MockDate.reset(); },
  parameters: { a11y: { test: "error" }, docs: { description: { component: "점주는 고객 공개 정보와 주간 운영시간을, 점주와 직원은 실제 픽업 시간과 정원을 관리합니다." }, story: { inline: false, height: "1000px" } }, routing: { path: "/store/management", initialEntry: "/store/management", surface: "store" }, msw: { handlers } },
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
export const StaffPickupOnly: Story = { parameters: { msw: { handlers: [membership("STAFF"), ...handlers.slice(1)] } }, play: async ({ canvas }) => { await expect(await canvas.findByRole("button", { name: "새 픽업 시간" })).toBeVisible(); await expect(canvas.queryByLabelText("고객에게 표시할 주소")).not.toBeInTheDocument(); } };
export const CreateSlot: Story = { play: async ({ canvas }) => {
  await userEvent.click(await canvas.findByRole("button", { name: "새 픽업 시간" }));
  await userEvent.type(canvas.getByLabelText("픽업 시작"), "2026-10-03T10:00"); await userEvent.type(canvas.getByLabelText("픽업 종료"), "2026-10-03T10:10");
  await userEvent.type(canvas.getByLabelText("정원"), "10"); await userEvent.type(canvas.getByLabelText("픽업 변경 사유"), "오전 예약 창구 추가");
  await userEvent.click(canvas.getByRole("button", { name: "픽업 시간 저장" }));
  await expect(await canvas.findByText("픽업 시간을 저장했습니다.")).toBeVisible();
} };
export const ReservedSlot: Story = { play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("button", { name: /픽업 시간 수정/ })); await expect(await canvas.findByLabelText("픽업 시작")).toBeDisabled(); await expect(canvas.getByLabelText("정원")).toHaveAttribute("min", "3"); } };
export const VersionConflict: Story = { parameters: { msw: { handlers: [http.put("/api/v1/stores/:storeId/customer-display", () => HttpResponse.json({ code: "MERCHANT_CONTENT_STALE", message: "changed" }, { status: 409 })), ...handlers] } }, play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("button", { name: "공개 정보 저장" })); await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByText("공개 정보를 저장했습니다.")).not.toBeInTheDocument(); } };
export const Unavailable: Story = { parameters: { msw: { handlers: [http.get("/api/v1/stores/:storeId/customer-display", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", message: "unavailable" }, { status: 503 })), ...handlers] } }, play: async ({ canvas }) => { await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByLabelText("고객에게 표시할 주소")).not.toBeInTheDocument(); } };
export const Paginated: Story = { parameters: { msw: { handlers: [http.get("/api/v1/stores/:storeId/pickup-slot-management", ({ request }) => HttpResponse.json(new URL(request.url).searchParams.has("cursor") ? { items: [], nextCursor: null } : { items: [slot], nextCursor: "next-page" })), ...handlers] } }, play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("button", { name: "다음 픽업 목록" })); await expect(await canvas.findByText("이 구간에 픽업 시간이 없습니다")).toBeVisible(); await expect(canvas.getByRole("button", { name: "처음 목록" })).toBeEnabled(); } };

export const UpdateReservedCapacity: Story = {
  parameters: { msw: { handlers: [http.put("/api/v1/stores/:storeId/pickup-slot-management/:slotId", async ({ request }) => { const body = await request.json() as typeof slot & { expectedVersion: number }; expect(body.expectedVersion).toBe(4); expect(body.startsAt).toBe(slot.startsAt); expect(body.endsAt).toBe(slot.endsAt); expect(body.capacity).toBe(10); return HttpResponse.json({ ...slot, ...body, version: 5 }); }), ...handlers] } },
  play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("button", { name: /픽업 시간 수정/ })); const input = await canvas.findByLabelText("정원"); await userEvent.clear(input); await userEvent.type(input, "10"); await userEvent.type(canvas.getByLabelText("픽업 변경 사유"), "수용 가능한 주문 수 조정"); await userEvent.click(canvas.getByRole("button", { name: "픽업 시간 저장" })); await expect(await canvas.findByText("픽업 시간을 저장했습니다.")).toBeVisible(); },
};
