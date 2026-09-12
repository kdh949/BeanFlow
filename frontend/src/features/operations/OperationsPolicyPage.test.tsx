import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router";
import { authToken } from "../../auth/session";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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

beforeEach(() => { authToken.set(`test.${btoa(JSON.stringify({ sub: "operator-1" }))}.fixture`); });

function renderPolicy() {
  const router = createMemoryRouter([{ path: "*", element: <OperationsPolicyPage /> }]);
  render(<RouterProvider router={router} />);
}

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

    renderPolicy();
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

    await userEvent.click(screen.getByRole("tab", { name: "만료 혜택 복원" }));
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

    renderPolicy();
    await userEvent.click(screen.getByRole("tab", { name: "브랜드" }));
    expect(await screen.findByText("빈플로우 커피")).toBeVisible();
    await userEvent.type(screen.getByLabelText("새 브랜드 이름"), "빈플로우 로스터스");
    await userEvent.type(screen.getByLabelText("브랜드 등록 사유"), "신규 브랜드 계약 승인");
    await userEvent.click(screen.getByRole("button", { name: "브랜드 등록" }));
    expect(await screen.findByText("브랜드를 등록했습니다: 빈플로우 로스터스")).toBeVisible();

    await userEvent.click(screen.getByRole("tab", { name: "검색 색인" }));
    await userEvent.type(screen.getByLabelText("재생성 사유"), "브랜드 변경 후 검색 정합성 복구");
    await userEvent.click(screen.getByRole("button", { name: "검색 색인 재생성" }));
    expect(await screen.findByText("일부 매장 완료 · 추가 확인 필요")).toBeVisible();
    expect(screen.getByText("실패 매장 1개")).toBeVisible();
    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
  });
});

it.each(["points", "cost-owners"])("retains %s command through back, forward and route exits until retry settles", async workspace => {
  vi.spyOn(operationsApi, "GET").mockImplementation((async (path: string) => {
    if (path.endsWith("ordinary-point-accrual/global")) return response(pointPolicy);
    if (path.endsWith("point-cost-issuers")) return response({ items: [], nextCursor: null, canRegisterPlatform: true });
    return response({ items: [], page: { nextCursor: null } });
  }) as never);
  let release!: () => void;
  const write = vi.spyOn(operationsApi, workspace === "points" ? "PATCH" : "POST")
    .mockImplementationOnce(async () => { await new Promise<void>(resolve => { release = resolve; }); throw new TypeError("Lost response"); })
    .mockResolvedValue(response(workspace === "points" ? { ...pointPolicy, policyVersionId: 13 } : { displayName: "검토된 비용 주체" }));
  const current = `/ops/policies?workspace=${workspace}`;
  const router = createMemoryRouter([
    { path: "/ops/policies", element: <OperationsPolicyPage /> },
    { path: "/outside", element: <p>다른 업무 화면</p> },
  ], { initialEntries: ["/ops/policies?workspace=brands", current, "/ops/policies?workspace=search"], initialIndex: 1 });
  render(<RouterProvider router={router} />);
  if (workspace === "points") {
    await userEvent.selectOptions(screen.getByLabelText("정책 조회 사유"), "POLICY_CHANGE_REVIEW");
    await userEvent.click(screen.getByRole("button", { name: "현재 적립 정책 조회" }));
    await userEvent.type(await screen.findByLabelText("변경 사유"), "동일 정책 요청 보존");
    await userEvent.click(screen.getByRole("button", { name: "새 적립 정책 적용" }));
  } else {
    await userEvent.type(await screen.findByLabelText("플랫폼 비용 주체 이름"), "검토된 비용 주체");
    await userEvent.type(screen.getByLabelText("비용 주체 등록 사유"), "비용 책임 명부 등록");
    await userEvent.click(screen.getByRole("button", { name: "플랫폼 비용 주체 등록" }));
  }
  await waitFor(() => expect(write).toHaveBeenCalledTimes(1));
  await act(async () => { await router.navigate(-1); });
  expect(router.state.location.search).toBe(`?workspace=${workspace}`);
  expect(screen.getByText(/처리 결과를 확인한 후 다시 이동해 주세요/)).toBeVisible();
  await act(async () => { release(); });
  const retryName = workspace === "points" ? "같은 공통 정책 변경 결과 확인" : "같은 비용 주체 등록 결과 확인";
  await waitFor(() => expect(screen.getByRole("button", { name: retryName })).toBeEnabled());
  await act(async () => { await router.navigate(1); });
  expect(router.state.location.search).toBe(`?workspace=${workspace}`);
  await act(async () => { await router.navigate("/outside"); });
  expect(router.state.location.pathname + router.state.location.search).toBe(current);
  expect(screen.queryByText("다른 업무 화면")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: retryName }));
  expect(await screen.findByText(workspace === "points" ? "버전 13 적용 중" : "검토된 비용 주체 등록 완료")).toBeVisible();
  expect(write).toHaveBeenCalledTimes(2);
  expect(write.mock.calls[1]).toEqual(write.mock.calls[0]);
  await act(async () => { await router.navigate(1); });
  expect(router.state.location.search).toBe("?workspace=search");
  await act(async () => { await router.navigate(-1); });
  expect(router.state.location.search).toBe(`?workspace=${workspace}`);
  await act(async () => { await router.navigate("/outside"); });
  expect(await screen.findByText("다른 업무 화면")).toBeVisible();
});
