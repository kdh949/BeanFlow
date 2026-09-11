import { storeSelectionHandler } from "../../../.storybook/storeSelectionFixtures";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { http, HttpResponse } from "msw";
import { ids } from "../../../.storybook/fixtures";
import { StorePointPolicyDirectory } from "./StorePointPolicyDirectory";

const first = { scopeName: "빈플로우 성수점", policyVersionId: 12, scopeType: "STORE", scopeReference: ids.store, state: "INHERIT_GLOBAL", effectiveAt: "2026-09-11T00:00:00Z", actorType: "PLATFORM_OPERATOR", actorReference: "operator-1", reason: "공통 상속" };
const handler = http.get("/api/v1/operations/policies/ordinary-point-accrual/stores", ({ request }) => { expect(request.headers.get("X-Access-Reason")).toBe("POLICY_AUDIT_REVIEW"); const q = new URL(request.url).searchParams; return HttpResponse.json(q.has("cursor") ? { items: [{ ...first, scopeName: "빈플로우 서울숲점", scopeReference: "97000000-0000-4000-8000-000000000001" }], page: { nextCursor: null } } : q.get("state") === "OVERRIDE" ? { items: [], page: { nextCursor: null } } : { items: [first], page: { nextCursor: "store-policy-next" } }); });
const meta = { title: "Patterns/Operations/Store point policy directory", component: StorePointPolicyDirectory, tags: ["autodocs"], parameters: { a11y: { test: "error" }, msw: { handlers: [storeSelectionHandler, handler] }, docs: { description: { component: "명시적으로 저장된 매장 포인트 설정을 필터·커서로 찾고 해당 매장의 실제 적용 정책을 조회합니다. 별도 설정이 없는 매장도 이름으로 찾아 선택할 수 있습니다." }, story: { inline: false, height: "950px" } } } } satisfies Meta<typeof StorePointPolicyDirectory>;
export default meta; type Story = StoryObj<typeof meta>;
export const FilterAndPage: Story = { play: async ({ canvas }) => { await userEvent.selectOptions(canvas.getByLabelText("매장 정책 목록 조회 사유"), "POLICY_AUDIT_REVIEW"); await userEvent.click(canvas.getByRole("button", { name: "매장 정책 목록 조회" })); await expect(await canvas.findByText("빈플로우 성수점")).toBeVisible(); await userEvent.click(canvas.getByRole("button", { name: "다음 매장 정책" })); await expect(await canvas.findByText("빈플로우 서울숲점")).toBeVisible(); await userEvent.selectOptions(canvas.getByLabelText("매장 정책 상태"), "OVERRIDE"); await userEvent.click(canvas.getByRole("button", { name: "매장 정책 목록 조회" })); await expect(await canvas.findByText("일치하는 매장 정책이 없습니다")).toBeVisible(); } };
export const SelectStore: Story = { play: async ({ canvas }) => { await userEvent.selectOptions(canvas.getByLabelText("매장 정책 목록 조회 사유"), "POLICY_AUDIT_REVIEW"); await userEvent.click(canvas.getByRole("button", { name: "매장 정책 목록 조회" })); await userEvent.click(await canvas.findByRole("button", { name: "빈플로우 성수점 포인트 관리" })); await expect(await canvas.findByRole("button", { name: "현재 매장 포인트 정책 조회" })).toBeVisible(); } };
export const NoExplicitHead: Story = { play: async ({ canvas }) => { await userEvent.click(canvas.getByRole("button", { name: "매장 찾기" })); await userEvent.click(await canvas.findByRole("button", { name: "빈플로우 성수점 선택" })); await expect(await canvas.findByRole("button", { name: "현재 매장 포인트 정책 조회" })).toBeVisible(); } };

export const LostChangeKeepsStore: Story = {
  parameters: { msw: { handlers: [storeSelectionHandler,
    http.get("/api/v1/operations/policies/ordinary-point-accrual/stores/:storeId", () => HttpResponse.json({
      storeId: ids.store, selectionSource: "GLOBAL_NO_OVERRIDE", effectivePolicy: {
        policyVersionId: 12, accrualRateBps: 500, roundingMode: "FLOOR", issuerType: "PLATFORM",
        issuerReference: "platform:beanflow", expiryRule: "SEOUL_CALENDAR_DAYS_FROM_COMPLETION", validityDays: 365,
      },
    })),
  ] } },
  play: async ({ canvas, msw }) => {
    await userEvent.click(canvas.getByRole("button", { name: "매장 찾기" }));
    await userEvent.click(await canvas.findByRole("button", { name: "빈플로우 성수점 선택" }));
    await userEvent.selectOptions(await canvas.findByLabelText("매장 포인트 조회 사유"), "POLICY_CHANGE_REVIEW");
    await userEvent.click(canvas.getByRole("button", { name: "현재 매장 포인트 정책 조회" }));
    await userEvent.type(await canvas.findByLabelText("매장 포인트 변경 사유"), "공통 정책 적용");
    let submitted: unknown;
    msw.use(http.patch("/api/v1/operations/policies/ordinary-point-accrual/stores/:storeId", async ({ request }) => {
      submitted = { key: request.headers.get("Idempotency-Key"), body: await request.json() };
      return HttpResponse.error();
    }));
    await userEvent.click(canvas.getByRole("button", { name: "매장 포인트 정책 저장" }));
    await canvas.findByRole("button", { name: "같은 정책 변경 결과 확인" });
    await expect(canvas.getByRole("button", { name: "다른 매장 찾기" })).toBeDisabled();
    await expect(canvas.getByLabelText("매장 포인트 변경 사유")).toBeDisabled();
    msw.use(http.patch("/api/v1/operations/policies/ordinary-point-accrual/stores/:storeId", async ({ request }) => {
      expect({ key: request.headers.get("Idempotency-Key"), body: await request.json() }).toEqual(submitted);
      return HttpResponse.json(first);
    }));
    await userEvent.click(canvas.getByRole("button", { name: "같은 정책 변경 결과 확인" }));
    await expect(await canvas.findByText("매장 포인트 정책을 저장했습니다. 이후 생성되는 주문에 적용합니다.")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "다른 매장 찾기" })).toBeEnabled();
  },
};
