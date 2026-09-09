import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { HttpResponse, http } from "msw";
import { ids } from "../../../.storybook/fixtures";
import { OperationsPolicyPage } from "./OperationsPolicyPage";

const brandId = "15199b3a-1294-5fd2-a127-761b899b74b8";
const pointPolicy = {
  policyVersionId: 12,
  scopeType: "GLOBAL",
  scopeReference: "d6578ecd-7fe4-5493-92e0-bebde2fbcd13",
  state: "OVERRIDE",
  accrualRateBps: 500,
  roundingMode: "FLOOR",
  issuerType: "PLATFORM",
  issuerReference: "platform:beanflow",
  expiryRule: "SEOUL_CALENDAR_DAYS_FROM_COMPLETION",
  validityDays: 365,
  effectiveAt: "2026-08-15T00:00:00+09:00",
  actorType: "PLATFORM_OPERATOR",
  actorReference: "operator-1",
  reason: "기본 적립률 설정",
};
const restorationPolicies = [
  { policyVersionId: 18, trigger: "STORE_REJECTION", benefitType: "COUPON", mode: "COMPENSATE_WITH_NEW_ISSUANCE", compensationValidityDays: 30, effectiveAt: "2026-08-15T00:00:00+09:00", updatedBy: "operator-1", reason: "거절 쿠폰 보상" },
  { policyVersionId: 19, trigger: "STORE_REJECTION", benefitType: "POINTS", mode: "PRESERVE_ORIGINAL_EXPIRY", compensationValidityDays: 30, effectiveAt: "2026-08-15T00:00:00+09:00", updatedBy: "operator-1", reason: "거절 포인트 복원" },
  { policyVersionId: 20, trigger: "CUSTOMER_CANCELLATION", benefitType: "COUPON", mode: "COMPENSATE_WITH_NEW_ISSUANCE", compensationValidityDays: 45, effectiveAt: "2026-08-15T00:00:00+09:00", updatedBy: "operator-1", reason: "취소 쿠폰 보상" },
  { policyVersionId: 21, trigger: "CUSTOMER_CANCELLATION", benefitType: "POINTS", mode: "PRESERVE_ORIGINAL_EXPIRY", compensationValidityDays: 30, effectiveAt: "2026-08-15T00:00:00+09:00", updatedBy: "operator-1", reason: "취소 포인트 복원" },
  { policyVersionId: 22, trigger: "PARTIAL_REFUND", benefitType: "POINTS", mode: "COMPENSATE_WITH_NEW_ISSUANCE", compensationValidityDays: 30, effectiveAt: "2026-08-15T00:00:00+09:00", updatedBy: "operator-1", reason: "부분 환불 포인트 보상" },
];
const brands = {
  items: [
    { brandId, name: "빈플로우 커피", status: "ACTIVE", assignedStoreCount: 12, version: 3 },
    { brandId: "15199b3a-1294-5fd2-a127-761b899b74b9", name: "로스터스 랩", status: "ACTIVE", assignedStoreCount: 0, version: 1 },
  ],
  page: { nextCursor: null },
};

const getPoint = http.get("/api/v1/operations/policies/ordinary-point-accrual/global", () => HttpResponse.json(pointPolicy));
const updatePoint = http.patch("/api/v1/operations/policies/ordinary-point-accrual/global", () => HttpResponse.json({ ...pointPolicy, policyVersionId: 13, accrualRateBps: 700 }));
const getRestoration = http.get("/api/v1/operations/policies/expired-benefit-restoration", () => HttpResponse.json(restorationPolicies));
const updateRestoration = http.patch("/api/v1/operations/policies/expired-benefit-restoration/:trigger/:benefitType", () => HttpResponse.json({ ...restorationPolicies[0], policyVersionId: 23, compensationValidityDays: 60 }));
const getBrands = http.get("/api/v1/operations/brands", () => HttpResponse.json(brands));
const createBrand = http.post("/api/v1/operations/brands", () => HttpResponse.json({ brandId, name: "빈플로우 로스터스", status: "ACTIVE", assignedStoreCount: 0, version: 0 }, { status: 201 }));
const updateBrand = http.patch("/api/v1/operations/brands/:brandId", () => HttpResponse.json({ ...brands.items[1], status: "ARCHIVED", version: 2 }));

const meta = {
  title: "Pages/Operations/Policy management",
  component: OperationsPolicyPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component: "기존 Button, FeedbackState, StatusText와 콘솔 카드 패턴을 조합해 포인트·만료 혜택 정책, 브랜드, 검색 색인을 관리합니다. 모든 변경은 현재 버전과 멱등성 키를 사용합니다.",
      },
      story: { inline: false, height: "980px" },
    },
    routing: { path: "/ops/policies", initialEntry: "/ops/policies" },
    msw: { handlers: [getPoint, updatePoint, getRestoration, updateRestoration, getBrands, createBrand, updateBrand] },
  },
} satisfies Meta<typeof OperationsPolicyPage>;

export default meta;
type Story = StoryObj<typeof meta>;

async function loadPoint(canvas: ReturnType<typeof within>) {
  await userEvent.selectOptions(canvas.getByLabelText("정책 조회 사유"), "POLICY_CHANGE_REVIEW");
  await userEvent.click(canvas.getByRole("button", { name: "현재 적립 정책 조회" }));
  await expect(await canvas.findByText("5.00%")).toBeVisible();
}

export const GlobalPointPolicy: Story = {
  play: async ({ canvas }) => {
    await loadPoint(canvas);
    await expect(canvas.getByText("버전 12 적용 중")).toBeVisible();
  },
};

export const PointPolicyConflict: Story = {
  parameters: {
    msw: { handlers: [getPoint, http.patch("/api/v1/operations/policies/ordinary-point-accrual/global", () => HttpResponse.json({ code: "POLICY_VERSION_CONFLICT", message: "다른 운영자가 정책을 먼저 변경했습니다. 현재값을 다시 조회해 주세요.", correlationId: "REQ-POLICY-409" }, { status: 409 }))] },
  },
  play: async ({ canvas }) => {
    await loadPoint(canvas);
    await userEvent.clear(canvas.getByLabelText("적립률(%)"));
    await userEvent.type(canvas.getByLabelText("적립률(%)"), "7");
    await userEvent.type(canvas.getByLabelText("변경 사유"), "프로모션 적립률 반영");
    await userEvent.click(canvas.getByRole("button", { name: "새 적립 정책 적용" }));
    await expect(await canvas.findByText("정책 버전이 변경되었습니다")).toBeVisible();
  },
};

export const RestorationPolicies: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("tab", { name: "만료 혜택 복원" }));
    await userEvent.selectOptions(canvas.getByLabelText("복원 정책 조회 사유"), "BENEFIT_POLICY_REVIEW");
    await userEvent.click(canvas.getByRole("button", { name: "복원 정책 조회" }));
    await expect(await canvas.findByText("PARTIAL_REFUND")).toBeVisible();
    await expect(canvas.getAllByRole("button", { name: "정책 변경" })).toHaveLength(5);
  },
};

export const BrandCatalog: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("tab", { name: "브랜드" }));
    await expect(await canvas.findByText("빈플로우 커피")).toBeVisible();
    await expect(canvas.getByText("소속 매장 12개")).toBeVisible();
  },
};

export const SearchIndexComplete: Story = {
  parameters: { msw: { handlers: [http.post("/api/v1/operations/search-index/rebuild", () => HttpResponse.json({ indexedStoreCount: 128, skippedStoreCount: 2, failedStoreIds: [], complete: true }))] } },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("tab", { name: "검색 색인" }));
    await userEvent.type(canvas.getByLabelText("재생성 사유"), "브랜드 변경 후 검색 정합성 복구");
    await userEvent.click(canvas.getByRole("button", { name: "검색 색인 재생성" }));
    await expect(await canvas.findByText("대상 매장 재생성 완료")).toBeVisible();
  },
};

export const SearchIndexPartial: Story = {
  parameters: { msw: { handlers: [http.post("/api/v1/operations/search-index/rebuild", () => HttpResponse.json({ indexedStoreCount: 127, skippedStoreCount: 2, failedStoreIds: [ids.store], complete: false }))] } },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("tab", { name: "검색 색인" }));
    await userEvent.type(canvas.getByLabelText("재생성 사유"), "매장 검색 색인 장애 복구");
    await userEvent.click(canvas.getByRole("button", { name: "검색 색인 재생성" }));
    await expect(await canvas.findByText("일부 매장 완료 · 추가 확인 필요")).toBeVisible();
    await expect(canvas.getByText(ids.store)).toBeVisible();
  },
};

export const SearchIndexInProgress: Story = {
  parameters: { msw: { handlers: [http.post("/api/v1/operations/search-index/rebuild", () => HttpResponse.json({ code: "IDEMPOTENCY_REQUEST_IN_PROGRESS", message: "같은 재생성 요청이 아직 실행 중입니다. Retry-After 이후 다시 확인해 주세요.", correlationId: "REQ-INDEX-409" }, { status: 409, headers: { "Retry-After": "5" } }))] } },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("tab", { name: "검색 색인" }));
    await userEvent.type(canvas.getByLabelText("재생성 사유"), "매장 검색 색인 장애 복구");
    await userEvent.click(canvas.getByRole("button", { name: "검색 색인 재생성" }));
    await expect(await canvas.findByText("요청을 처리하고 있습니다")).toBeVisible();
  },
};
