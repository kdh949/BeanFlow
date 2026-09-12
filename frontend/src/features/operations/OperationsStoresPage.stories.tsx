import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, http } from "msw";
import { ids } from "../../../.storybook/fixtures";
import { OperationsStoresPage } from "./OperationsStoresPage";

const initial = { storeId: ids.store, name: "성수 카페", latitude: 37.54, longitude: 127.05, regionCode: "1120011400", version: 2, acceptingOrders: true, pickupEnabled: true };
const region = { code: "1120011400", fullName: "서울특별시 성동구 성수동2가", sido: "서울특별시", sigungu: "성동구", eupmyeondong: "성수동2가", ri: "" };
const brand = { brandId: "93000000-0000-4000-8000-000000000001", name: "빈플로우", status: "ACTIVE", version: 1, assignedStoreCount: 1 };
let current = { ...initial };
let retryKey: string | null = null;
let assignment: { storeId: string; brandId: string | null; brandName: string | null };
const handlers = [
  http.get("/api/v1/operations/store-targets", ({ request }) => HttpResponse.json({ items: new URL(request.url).searchParams.has("cursor") ? [{ ...current, storeId: "93000000-0000-4000-8000-000000000002", name: "다음 매장" }] : [current], nextCursor: new URL(request.url).searchParams.has("cursor") ? null : "store-next" })),
  http.get("/api/v1/operations/stores/:storeId/identity", () => HttpResponse.json(current)),
  http.put("/api/v1/operations/stores/:storeId/identity", async ({ request }) => { const body = await request.json() as Record<string, unknown>; expect(body.expectedVersion).toBe(current.version); current = { ...current, name: String(body.name), version: current.version + 1 }; return HttpResponse.json(current); }),
  http.post("/api/v1/operations/stores", async ({ request }) => { const body = await request.json() as typeof initial; expect(body.regionCode).toBe(region.code); current = { ...initial, ...body, version: 0, acceptingOrders: false, pickupEnabled: false }; return HttpResponse.json(current, { status: 201 }); }),
  http.get("/api/v1/operations/store-regions", () => HttpResponse.json({ items: [region], nextCursor: null })),
  http.get("/api/v1/operations/brands", () => HttpResponse.json({ items: [brand], page: { nextCursor: null } })),
  http.get("/api/v1/operations/stores/:storeId/brand", () => HttpResponse.json(assignment)),
  http.put("/api/v1/operations/stores/:storeId/brand", async ({ request }) => { const body = await request.json() as { brandId: string }; expect(body.brandId).toBe(brand.brandId); assignment = { storeId: ids.store, brandId: brand.brandId, brandName: brand.name }; return HttpResponse.json(assignment); }),
  http.delete("/api/v1/operations/stores/:storeId/brand", () => { assignment = { storeId: ids.store, brandId: null, brandName: null }; return HttpResponse.json(assignment); }),
];
const meta = {
  title: "Pages/Operations/Stores", component: OperationsStoresPage, tags: ["autodocs"],
  beforeEach: () => { retryKey = null; current = { ...initial }; assignment = { storeId: ids.store, brandId: null, brandName: null }; },
  parameters: { a11y: { test: "error" }, layout: "fullscreen", routing: { surface: "ops", path: "/ops/stores", initialEntry: "/ops/stores" }, msw: { handlers }, docs: { story: { inline: false, height: "1000px" }, description: { component: "운영 권한으로 매장을 검색·생성하고 현재 식별정보와 브랜드 소속을 관리합니다. 변경 사유와 조회 버전을 보존하며 등록 지역만 선택합니다." } } },
} satisfies Meta<typeof OperationsStoresPage>;
export default meta;
type Story = StoryObj<typeof meta>;
async function open(canvas: Parameters<NonNullable<Story["play"]>>[0]["canvas"]) {
  await userEvent.click(await canvas.findByRole("button", { name: "성수 카페 관리" }));
  await expect(await canvas.findByLabelText("매장 이름")).toHaveValue("성수 카페");
}
export const StoreList: Story = { play: async ({ canvas }) => { await expect(await canvas.findByRole("button", { name: "성수 카페 관리" })).toBeVisible(); await userEvent.click(canvas.getByRole("button", { name: "다음 매장 목록" })); await expect(await canvas.findByRole("button", { name: "다음 매장 관리" })).toBeVisible(); await userEvent.click(canvas.getByRole("button", { name: "이전 매장 목록" })); await expect(await canvas.findByRole("button", { name: "성수 카페 관리" })).toBeVisible(); } };
export const EditIdentity: Story = { play: async ({ canvas }) => { await open(canvas); await userEvent.clear(canvas.getByLabelText("매장 이름")); await userEvent.type(canvas.getByLabelText("매장 이름"), "성수 본점"); await userEvent.type(canvas.getByLabelText("식별정보 변경 사유"), "간판 변경 확인"); await userEvent.click(canvas.getByRole("button", { name: "식별정보 저장" })); await expect(await canvas.findByText("식별정보를 저장했습니다.")).toBeVisible(); await waitFor(() => expect(canvas.getByLabelText("매장 이름")).toHaveValue("성수 본점")); } };
export const CreateStore: Story = { play: async ({ canvas }) => { await userEvent.click(canvas.getByRole("button", { name: "새 매장 등록" })); await userEvent.type(canvas.getByLabelText("매장 이름"), "새 카페"); await userEvent.type(canvas.getByLabelText("위도"), "37.54"); await userEvent.type(canvas.getByLabelText("경도"), "127.05"); await userEvent.click(canvas.getByRole("button", { name: "지역 검색" })); await userEvent.click(await canvas.findByRole("button", { name: `${region.fullName} 선택` })); await userEvent.type(canvas.getByLabelText("매장 등록 사유"), "신규 매장 계약 확인"); await userEvent.click(canvas.getByRole("button", { name: "매장 생성" })); await expect(await canvas.findByText("매장을 등록했습니다. 주문 접수와 픽업은 중지 상태입니다.")).toBeVisible(); } };
export const BrandAssignment: Story = { play: async ({ canvas }) => { await open(canvas); await expect(await canvas.findByText("현재 소속 브랜드가 없습니다.")).toBeVisible(); await userEvent.click(canvas.getByRole("button", { name: "브랜드 목록 조회" })); await userEvent.click(await canvas.findByRole("button", { name: "빈플로우 선택" })); await userEvent.type(canvas.getByLabelText("브랜드 변경 사유"), "가맹 계약 확인"); await userEvent.click(canvas.getByRole("button", { name: "선택한 브랜드로 지정" })); await expect(await canvas.findByText("현재 브랜드: 빈플로우")).toBeVisible(); await userEvent.type(canvas.getByLabelText("브랜드 변경 사유"), "가맹 해지 확인"); await userEvent.click(canvas.getByRole("button", { name: "현재 브랜드 해제" })); await expect(await canvas.findByText("현재 소속 브랜드가 없습니다.")).toBeVisible(); } };
export const StaleIdentity: Story = { parameters: { msw: { handlers: [http.put("/api/v1/operations/stores/:storeId/identity", () => HttpResponse.json({ code: "RESOURCE_STATE_CONFLICT", message: "다른 운영자가 변경했습니다.", correlationId: "REQ-STORE-STALE" }, { status: 409 })), ...handlers] } }, play: async ({ canvas }) => { await open(canvas); await userEvent.type(canvas.getByLabelText("식별정보 변경 사유"), "정보 정정"); await userEvent.click(canvas.getByRole("button", { name: "식별정보 저장" })); await expect(await canvas.findByText("문의 코드 REQ-STORE-STALE")).toBeVisible(); await expect(canvas.getByRole("button", { name: "현재 식별정보 다시 읽기" })).toBeEnabled(); } };
export const ReadDenied: Story = { parameters: { msw: { handlers: [http.get("/api/v1/operations/store-targets", () => HttpResponse.json({ code: "ACCESS_DENIED", message: "매장 조회 권한이 없습니다.", correlationId: "REQ-STORE-DENIED" }, { status: 403 }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("문의 코드 REQ-STORE-DENIED")).toBeVisible(); await expect(canvas.queryByText("검색 결과가 없습니다.")).not.toBeInTheDocument(); } };

export const LostResponse: Story = {
  parameters: { msw: { handlers: [http.put("/api/v1/operations/stores/:storeId/identity", async ({ request }) => {
      if (!retryKey) { retryKey = request.headers.get("Idempotency-Key"); return HttpResponse.error(); }
      expect(request.headers.get("Idempotency-Key")).toBe(retryKey);
      current = { ...current, version: current.version + 1 };
      return HttpResponse.json(current);
  }), ...handlers] } },
  play: async ({ canvas }) => { await open(canvas); await userEvent.type(canvas.getByLabelText("식별정보 변경 사유"), "동일 요청 재시도"); await userEvent.click(canvas.getByRole("button", { name: "식별정보 저장" })); await expect(await canvas.findByRole("alert")).toBeVisible(); await userEvent.click(canvas.getByRole("button", { name: "식별정보 저장" })); await expect(await canvas.findByText("식별정보를 저장했습니다.")).toBeVisible(); },
};
export const BrandPagination: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/operations/brands", ({ request }) => new URL(request.url).searchParams.has("cursor") ? HttpResponse.json({ items: [brand], page: { nextCursor: null } }) : HttpResponse.json({ items: [{ ...brand, brandId: "93000000-0000-4000-8000-000000000005", name: "이전 브랜드", status: "ARCHIVED" }], page: { nextCursor: "brands-next" } })), ...handlers] } },
  play: async ({ canvas }) => { await open(canvas); await userEvent.click(canvas.getByRole("button", { name: "브랜드 목록 조회" })); await expect(await canvas.findByRole("button", { name: "이전 브랜드 선택" })).toBeDisabled(); await userEvent.click(canvas.getByRole("button", { name: "다음 브랜드" })); await expect(await canvas.findByRole("button", { name: "빈플로우 선택" })).toBeEnabled(); await userEvent.click(canvas.getByRole("button", { name: "이전 브랜드" })); await expect(await canvas.findByRole("button", { name: "이전 브랜드 선택" })).toBeDisabled(); },
};
export const BrandUnavailable: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/operations/stores/:storeId/brand", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", correlationId: "REQ-BRAND-503" }, { status: 503 })), ...handlers] } },
  play: async ({ canvas }) => { await open(canvas); await expect(await canvas.findByText("문의 코드 REQ-BRAND-503")).toBeVisible(); await expect(canvas.getByRole("button", { name: "현재 브랜드 해제" })).toBeDisabled(); await expect(canvas.queryByText("현재 소속 브랜드가 없습니다.")).not.toBeInTheDocument(); },
};

export const StoreBusinessTabs: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/operations/stores/:storeId/settlement-terms", () => HttpResponse.json({ items: [], revision: 0, nextCursor: null })), http.get("/api/v1/operations/stores/:storeId/memberships", () => HttpResponse.json({ items: [], nextCursor: null })), ...handlers] } },
  play: async ({ canvas }) => { await open(canvas); await userEvent.click(canvas.getByRole("tab", { name: "정산 계약" })); await expect(await canvas.findByText("등록된 정산 계약이 없습니다")).toBeVisible(); await userEvent.click(canvas.getByRole("tab", { name: "점주·직원 소속" })); await expect(await canvas.findByText("등록된 소속이 없습니다")).toBeVisible(); },
};
export const StorePointPolicyTab: Story = { play: async ({ canvas }) => { await open(canvas); await userEvent.click(canvas.getByRole("tab", { name: "포인트 정책" })); await expect(canvas.getByRole("button", { name: "현재 매장 포인트 정책 조회" })).toBeVisible(); } };
export const StoreMediaTab: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/operations/stores/:storeId/image", () => HttpResponse.json({})), http.get("/api/v1/operations/stores/:storeId/media-menus", () => HttpResponse.json({ items: [], nextCursor: null })), ...handlers] } },
  play: async ({ canvas }) => { await open(canvas); await userEvent.click(canvas.getByRole("tab", { name: "매장·메뉴 이미지" })); await expect(await canvas.findByRole("heading", { name: "매장 대표 이미지" })).toBeVisible(); await expect(await canvas.findByText("해당 범위의 메뉴가 없습니다")).toBeVisible(); },
};

const onlyPurpose = (purpose: string) => [
  http.get("/api/v1/operations/store-targets", ({ request }) => new URL(request.url).searchParams.get("purpose") === purpose ? HttpResponse.json({ items: [{ storeId: ids.store, name: initial.name }], nextCursor: null }) : HttpResponse.json({ code: "ACCESS_DENIED" }, { status: 403 })),
  http.get("/api/v1/operations/stores/:storeId/identity", () => { throw new Error("Identity access is not part of this workflow"); }),
  http.get("/api/v1/operations/stores/:storeId/settlement-terms", () => HttpResponse.json({ items: [], revision: 0, nextCursor: null })),
  http.get("/api/v1/operations/stores/:storeId/memberships", () => HttpResponse.json({ items: [], nextCursor: null })),
  ...handlers,
];
export const TermsPermissionOnly: Story = { parameters: { msw: { handlers: onlyPurpose("TERMS") } }, play: async ({ canvas }) => {
  await userEvent.selectOptions(canvas.getByLabelText("매장 관리 목적"), "TERMS");
  await userEvent.click(await canvas.findByRole("button", { name: "성수 카페 관리" }));
  await expect(await canvas.findByText("등록된 정산 계약이 없습니다")).toBeVisible();
  await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
} };
export const MembershipPermissionOnly: Story = { parameters: { msw: { handlers: onlyPurpose("MEMBERSHIP") } }, play: async ({ canvas }) => {
  await userEvent.selectOptions(canvas.getByLabelText("매장 관리 목적"), "MEMBERSHIP");
  await userEvent.click(await canvas.findByRole("button", { name: "성수 카페 관리" }));
  await expect(await canvas.findByText("등록된 소속이 없습니다")).toBeVisible();
  await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
} };
export const BrandPermissionOnly: Story = { parameters: { msw: { handlers: onlyPurpose("BRAND") } }, play: async ({ canvas }) => {
  await userEvent.selectOptions(canvas.getByLabelText("매장 관리 목적"), "BRAND");
  await userEvent.click(await canvas.findByRole("button", { name: "성수 카페 관리" }));
  await expect(await canvas.findByText("현재 소속 브랜드가 없습니다.")).toBeVisible();
  await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
} };
