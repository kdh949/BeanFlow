import MockDate from "mockdate";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, http, delay } from "msw";
import { SupportFollowUpRoute } from "./SupportFollowUpRoute";

const caseId = "a1000000-0000-4000-8000-000000000001";
const supportCase = { caseId, category: "ACCOUNT_RECOVERY", state: "IN_PROGRESS", priority: "NORMAL", assigneeId: "a7000000-0000-4000-8000-000000000001", version: 4, openedAt: "2026-08-23T09:00:00Z", subjectLinks: [] };
const item = { itemId: "a8000000-0000-4000-8000-000000000001", source: "ORDERING", type: "ORDER_STATE", state: "COMPLETED", summary: "ORDER_STATE:COMPLETED", amountKrw: 7500, occurredAt: "2026-08-23T09:30:00Z" };
const caseHandler = http.get("/api/v1/support/cases/:caseId", () => HttpResponse.json(supportCase));
const timelineHandler = http.get("/api/v1/support/cases/:caseId/timeline", ({ request }) => {
  const more = new URL(request.url).searchParams.has("cursor");
  return HttpResponse.json({ items: [more ? { ...item, itemId: "a8000000-0000-4000-8000-000000000002", summary: "CASE_STATE:OPEN", state: "OPEN", type: "CASE_STATE", source: "SUPPORT", amountKrw: null } : item], nextCursor: more ? null : "page-two" });
});
const meta = {
  title: "Pages/Support/Follow-up route",
  component: SupportFollowUpRoute,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen", docs: { story: { inline: false, height: "880px" } },
    routing: { path: "/support/follow-up", initialEntry: `/support/follow-up?caseId=${caseId}`, surface: "support" },
    msw: { handlers: [caseHandler, timelineHandler] },
  },
} satisfies Meta<typeof SupportFollowUpRoute>;
export default meta;
type Story = StoryObj<typeof meta>;

export const LinkedCase: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("계정 복구 상담")).toBeVisible();
    await expect(canvas.getByText("주문 픽업 완료")).toBeVisible();
    await expect(canvas.getByRole("link", { name: "상담 처리로 돌아가기" })).toHaveAttribute("href", `/support?caseId=${caseId}`);
    await expect(canvas.queryByText(/ORDERING|ORDER_STATE/)).not.toBeInTheDocument();
    await userEvent.click(canvas.getByRole("button", { name: "이력 더 보기" }));
    await expect(await canvas.findByText("상담 접수")).toBeVisible();
    await expect(canvas.getByText("주문 픽업 완료")).toBeVisible();
    await expect(canvas.queryByText(/ORDER_STATE:|CASE_STATE:/)).not.toBeInTheDocument();
    await expect(canvas.queryByRole("button", { name: "이력 더 보기" })).not.toBeInTheDocument();
  },
};
export const CaseRequired: Story = {
  parameters: { routing: { path: "/support/follow-up", initialEntry: "/support/follow-up", surface: "support" } },
  play: async ({ canvas }) => { await expect(await canvas.findByText("상담 건을 먼저 열어 주세요")).toBeVisible(); },
};
export const Unavailable: Story = {
  parameters: { msw: { handlers: [caseHandler, http.get("/api/v1/support/cases/:caseId/timeline", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE" }, { status: 503 }))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
    await expect(canvas.queryByText("표시할 이력이 없습니다")).not.toBeInTheDocument();
    await expect(canvas.queryByText("계정 복구 상담")).not.toBeInTheDocument();
  },
};
export const Loading: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/support/cases/:caseId", async () => { await delay("infinite"); return HttpResponse.json(supportCase); }), timelineHandler] } },
  play: async ({ canvas }) => { await expect(await canvas.findByText("상담 건과 이력을 불러오는 중")).toBeVisible(); },
};
export const EmptyTimeline: Story = {
  parameters: { msw: { handlers: [caseHandler, http.get("/api/v1/support/cases/:caseId/timeline", () => HttpResponse.json({ items: [], nextCursor: null }))] } },
  play: async ({ canvas }) => { await expect(await canvas.findByText("표시할 이력이 없습니다")).toBeVisible(); },
};
export const OrderWorkflow: Story = { play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("tab", { name: "주문 변경" })); await expect(await canvas.findByRole("button", { name: "기존 주문 변경 요청 찾기" })).toBeVisible(); await expect(canvas.getByText("업무 처리 목적의 본인확인이 필요합니다")).toBeVisible(); } };

export const CompensationWorkflow: Story = { play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("tab", { name: "고객 보상" })); await expect(await canvas.findByRole("button", { name: "기존 보상 요청 찾기" })).toBeVisible(); await expect(canvas.getByText("고객 본인확인이 필요합니다")).toBeVisible(); } };
export const ReturnedCompensation: Story = {
  parameters: { routing: { path: "/support/follow-up", initialEntry: `/support/follow-up?caseId=${caseId}&incidentId=${caseId}` }, msw: { handlers: [http.get("/api/v1/support/cases/:caseId", () => HttpResponse.json({ ...supportCase, subjectLinks: [{ linkId: caseId, subjectId: caseId, subjectType: "CUSTOMER", relationship: "REQUESTER" }] })), timelineHandler] } },
  play: async ({ canvas, msw }) => {
    msw.use(http.get("/api/v1/support/work-items", () => HttpResponse.json({ items: [{ requestId: caseId, kind: "VERIFICATION", caseId, caseCategory: "COMPENSATION", caseOpenedAt: "2026-09-11T00:00:00Z", purpose: "CASE_RESOLUTION", state: "VERIFIED", createdAt: "2026-09-11T00:00:00Z", expiresAt: "2099-09-11T00:15:00Z" }], nextCursor: null })),
      http.get("/api/v1/support/verification-sessions/:sessionId", () => HttpResponse.json({ sessionId: caseId, caseId, subjectLinkId: caseId, subjectId: caseId, subjectType: "CUSTOMER", purpose: "CASE_RESOLUTION", actionScope: "SUPPORT_ACTION", requestedLevel: "BASIC", achievedLevel: "BASIC", state: "VERIFIED", startedAt: "2026-09-11T00:00:00Z", expiresAt: "2099-09-11T00:15:00Z", challenges: [] })),
      http.get("/api/v1/support/cases/:caseId/compensation-incidents", ({ request }) => { expect(new URL(request.url).searchParams.get("incidentId")).toBe(caseId); return HttpResponse.json({ items: [{ incidentId: caseId, category: "COMPENSATION", occurredAt: null, createdAt: "2026-09-11T00:00:00Z", source: "EXISTING_COMPENSATION", benefitIssued: false }], nextCursor: null }); }));
    await userEvent.click(await canvas.findByRole("button", { name: "기존 본인확인 요청 찾기" })); await userEvent.click(await canvas.findByRole("button", { name: "이 요청 열기" }));
    await expect(await canvas.findByText("재검토 링크의 기존 사고를 확인합니다.")).toBeVisible(); await userEvent.click(await canvas.findByRole("button", { name: "이 사고 선택" })); await expect(await canvas.findByText("선택한 사고 · 보상")).toBeVisible();
  },
};

export const ProfileWorkflow: Story = { play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("tab", { name: "정보 정정" })); await expect(await canvas.findByRole("button", { name: "기존 정보 정정 요청 찾기" })).toBeVisible(); await expect(canvas.getByText("정정할 대상이 없습니다")).toBeVisible(); } };

export const BreakGlassWorkflow: Story = { play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("tab", { name: "긴급 열람" })); await expect(await canvas.findByRole("button", { name: "기존 긴급 열람 요청 찾기" })).toBeVisible(); await expect(canvas.getByText("긴급 열람할 대상이 없습니다")).toBeVisible(); } };

export const PendingOrderKeepsWorkspace: Story = {
  parameters: { routing: { path: "/support/follow-up", initialEntry: `/support/follow-up?caseId=${caseId}&requestId=${caseId}` } },
  play: async ({ canvas, msw }) => {
    const { orderChangeDigest } = await import("../../lib/supportOrderPayload");
    const digest = await orderChangeDigest("ORDER_CANCELLATION", caseId, "CHANGED_MIND", "");
    msw.use(http.get("/api/v1/support/action-requests/:requestId/workflow", () => HttpResponse.json({
      request: { requestId: caseId, caseId, action: "ORDER_CANCELLATION", targetId: caseId, revisionNumber: 1, requestVersion: 1, targetVersion: 2, state: "READY_FOR_EXECUTION", actionPayloadDigest: digest, evidenceDigest: "a".repeat(64), expiresAt: "2099-09-11T09:15:00Z", approvalSteps: [] },
      order: { orderId: caseId, storeId: caseId, state: "PAID", version: 2 }, caseVersion: 4, allowedActions: ["EXECUTE"],
    })), http.post("/api/v1/support/action-requests/:requestId/executions", () => HttpResponse.error()));
    await waitFor(() => expect(canvas.getByRole("button", { name: "확인한 주문 변경 실행" })).toBeEnabled());
    await userEvent.click(canvas.getByRole("button", { name: "확인한 주문 변경 실행" }));
    await canvas.findByRole("button", { name: "같은 요청으로 결과 확인" });
    await expect(canvas.getByRole("tab", { name: "상담 이력" })).toBeDisabled();
    await expect(canvas.getByRole("tab", { name: "고객 보상" })).toBeDisabled();
    await expect(canvas.getByRole("button", { name: "기존 본인확인 요청 찾기" })).toBeDisabled();
  },
};

function lockedBreakGlass(unknown: boolean): Story {
  const id = "84000000-0000-4000-8000-000000000001";
  return {
    beforeEach: () => { MockDate.set("2026-09-11T09:04:00Z"); return () => MockDate.reset(); },
    parameters: { routing: { path: "/support/follow-up", initialEntry: `/support/follow-up?caseId=${caseId}&breakGlassRequestId=${id}`, surface: "support" } },
    play: async ({ canvas, msw }) => {
      let revealed = false, calls = 0;
      msw.use(http.get("/api/v1/support/break-glass-requests/:id/workflow", () => HttpResponse.json({
        request: { requestId: id, caseId, subjectId: id, subjectType: "CUSTOMER", requesterId: id, approverId: caseId, field: "CUSTOMER_PRIMARY_EMAIL", purpose: "PRIVACY_INCIDENT", reasonCode: "PRIVACY_INCIDENT", state: revealed ? "REVIEW_PENDING" : "ACTIVE", version: 1, requestedAt: "2026-09-11T09:03:00Z", expiresAt: "2026-09-11T09:06:00Z" },
        allowedActions: revealed ? [] : ["REVEAL"], canViewRevealedValue: true, postReview: null,
      })), http.post("/api/v1/support/break-glass-requests/:id/reveals", () => { revealed = true; calls++; return unknown ? HttpResponse.error() : HttpResponse.json({ revealAttemptId: id, requestId: id, caseId, subjectId: id, field: "CUSTOMER_PRIMARY_EMAIL", value: "emergency@example.invalid", revealedAt: "2026-09-11T09:04:00Z" }); }));
      await userEvent.click(await canvas.findByRole("button", { name: "긴급 정보 한 번 열람" }));
      await expect(await canvas.findByText(unknown ? "긴급 열람 응답을 확인하지 못했습니다" : "emergency@example.invalid")).toBeVisible();
      const picker = canvas.getByRole("button", { name: "기존 긴급 열람 요청 찾기" });
      await waitFor(() => expect(picker).toBeDisabled());
      for (const tab of canvas.getAllByRole("tab")) await expect(tab).toBeDisabled();
      if (!unknown) {
        await userEvent.click(canvas.getByRole("button", { name: "긴급 원문 지금 지우기" }));
        await waitFor(() => expect(picker).toBeEnabled());
        await userEvent.click(canvas.getByRole("tab", { name: "상담 이력" }));
        await expect(canvas.getByText("주문 픽업 완료")).toBeVisible();
      } else {
        await userEvent.click(canvas.getByRole("button", { name: "긴급 요청 상태 새로고침" }));
        await expect(picker).toBeDisabled();
        await expect(canvas.queryByRole("button", { name: "긴급 정보 한 번 열람" })).not.toBeInTheDocument();
      }
      expect(calls).toBe(1);
    },
  };
}
export const RawRevealLocksNavigation: Story = lockedBreakGlass(false);
export const UnknownRevealLocksNavigation: Story = lockedBreakGlass(true);
