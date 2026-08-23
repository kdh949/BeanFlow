import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { operationsApi } from "../../api/consoleClient";
import { OperationsPolicyPage } from "./OperationsPolicyPage";

const brandId = "15199b3a-1294-5fd2-a127-761b899b74b8";

function response(data: unknown, status = 200) {
  return { data, response: new Response(null, { status }) } as never;
}

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

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("OperationsPolicyPage", () => {
  it("loads audited policies and writes new versions with optimistic concurrency", async () => {
    const get = vi.spyOn(operationsApi, "GET").mockImplementation((async (path: string) => {
      if (path.endsWith("ordinary-point-accrual/global")) return response(pointPolicy);
      if (path.endsWith("expired-benefit-restoration")) return response(restorationPolicies);
      throw new Error(`unexpected GET ${path}`);
    }) as never);
    const patch = vi.spyOn(operationsApi, "PATCH").mockImplementation((async (path: string) => {
      if (path.endsWith("ordinary-point-accrual/global")) return response({ ...pointPolicy, policyVersionId: 13, accrualRateBps: 700 });
      return response({ ...restorationPolicies[0], policyVersionId: 23, compensationValidityDays: 60 });
    }) as never);

    render(<MemoryRouter><OperationsPolicyPage /></MemoryRouter>);
    await userEvent.selectOptions(screen.getByLabelText("정책 조회 사유"), "POLICY_CHANGE_REVIEW");
    await userEvent.click(screen.getByRole("button", { name: "현재 적립 정책 조회" }));
    expect(await screen.findByText("5.00%" )).toBeVisible();
    expect(get).toHaveBeenCalledWith("/operations/policies/ordinary-point-accrual/global", {
      params: { header: { "X-Access-Reason": "POLICY_CHANGE_REVIEW" } },
    });

    await userEvent.clear(screen.getByLabelText("적립률(%)"));
    await userEvent.type(screen.getByLabelText("적립률(%)"), "7");
    await userEvent.type(screen.getByLabelText("변경 사유"), "프로모션 적립률 반영");
    await userEvent.click(screen.getByRole("button", { name: "새 적립 정책 적용" }));
    expect(await screen.findByText("버전 13 적용 중")).toBeVisible();
    const [, pointOptions] = patch.mock.calls[0] as unknown as [string, { body: Record<string, unknown>; params: { header: Record<string, string> } }];
    expect(pointOptions.body).toMatchObject({ expectedPolicyVersionId: 12, accrualRateBps: 700, reason: "프로모션 적립률 반영" });
    expect(pointOptions.params.header["Idempotency-Key"]).toBeTruthy();

    await userEvent.click(screen.getByRole("button", { name: "만료 혜택 복원" }));
    await userEvent.selectOptions(screen.getByLabelText("복원 정책 조회 사유"), "BENEFIT_POLICY_REVIEW");
    await userEvent.click(screen.getByRole("button", { name: "복원 정책 조회" }));
    expect(await screen.findAllByText("신규 혜택 발급")).toHaveLength(3);
    await userEvent.click(screen.getAllByRole("button", { name: "정책 변경" })[0]!);
    await userEvent.clear(screen.getByLabelText("보상 유효일수"));
    await userEvent.type(screen.getByLabelText("보상 유효일수"), "60");
    await userEvent.type(screen.getByLabelText("복원 정책 변경 사유"), "고객 보상 기간 연장");
    await userEvent.click(screen.getByRole("button", { name: "새 복원 정책 적용" }));
    expect(await screen.findByText("버전 23 적용 중")).toBeVisible();
  });

  it("manages brands and distinguishes partial search-index results from completion", async () => {
    vi.spyOn(operationsApi, "GET").mockResolvedValue(response({
      items: [{ brandId, name: "빈플로우 커피", status: "ACTIVE", assignedStoreCount: 0, version: 3 }],
      page: { nextCursor: null },
    }));
    const post = vi.spyOn(operationsApi, "POST").mockImplementation((async (path: string) => {
      if (path.endsWith("/brands")) return response({ brandId, name: "빈플로우 로스터스", status: "ACTIVE", assignedStoreCount: 0, version: 0 }, 201);
      return response({ indexedStoreCount: 128, skippedStoreCount: 2, failedStoreIds: ["5273704d-f924-59e0-8883-827535fb86ad"], complete: false });
    }) as never);

    render(<MemoryRouter><OperationsPolicyPage /></MemoryRouter>);
    await userEvent.click(screen.getByRole("button", { name: "브랜드" }));
    expect(await screen.findByText("빈플로우 커피")).toBeVisible();
    await userEvent.type(screen.getByLabelText("새 브랜드 이름"), "빈플로우 로스터스");
    await userEvent.type(screen.getByLabelText("브랜드 등록 사유"), "신규 브랜드 계약 승인");
    await userEvent.click(screen.getByRole("button", { name: "브랜드 등록" }));
    expect(await screen.findByText("빈플로우 로스터스")).toBeVisible();

    await userEvent.click(screen.getByRole("button", { name: "검색 색인" }));
    await userEvent.type(screen.getByLabelText("재생성 사유"), "브랜드 변경 후 검색 정합성 복구");
    await userEvent.click(screen.getByRole("button", { name: "검색 색인 재생성" }));
    expect(await screen.findByText("부분 완료 · 재조정 필요")).toBeVisible();
    expect(screen.getByText("실패 매장 1개")).toBeVisible();
    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
  });
});
