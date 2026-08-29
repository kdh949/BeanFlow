import type { Meta, StoryObj } from "@storybook/react-vite";
import type { ReactNode } from "react";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import {
  SupportApprovalsRoute,
  SupportCaseDetailRoute,
  SupportCaseIntakeRoute,
  SupportCompensationRoute,
  SupportIndexRoute,
  SupportOrderActionRoute,
  SupportProfileChangeRoute,
  SupportQueueRoute,
  SupportVerificationRoute,
} from "./SupportCenterRoutes";
import { SupportWorkspaceShell } from "../../presentation/support-workspace/SupportWorkspaceShell";

const caseId = "10000000-0000-4000-8000-000000000001";
const actorId = "10000000-0000-4000-8000-000000000002";
const customerId = "10000000-0000-4000-8000-000000000003";
const orderId = "10000000-0000-4000-8000-000000000004";
const resourceId = "10000000-0000-4000-8000-000000000005";
const queueItem = { caseId, state: "IN_PROGRESS", priority: "URGENT", category: "PAYMENT_OR_REFUND", assigneeId: actorId, version: 4, openedAt: "2026-08-29T05:10:00Z", latestChangedAt: "2026-08-29T05:28:00Z", latestChannel: "PHONE", primarySubject: { subjectType: "CUSTOMER", subjectId: customerId, maskedDisplayName: "김*연", maskedMatchedValue: "010-12**-5678" } };
const order = { orderId, publicReference: "#BF-2026-0829-001", state: "PAID", version: 7, orderedAt: "2026-08-29T04:30:00Z", pickupWindowStart: "2026-08-29T05:00:00Z", pickupWindowEnd: "2026-08-29T05:10:00Z", storeName: "빈플로우 카페 강남점", subtotalKrw: 18_900, couponDiscountKrw: 0, pointsAppliedKrw: 0, payableKrw: 18_900, currency: "KRW", paymentState: "PAID", paidAt: "2026-08-29T04:31:00Z", lines: [{ sequence: 1, menuName: "아메리카노", quantity: 2, amountKrw: 18_900 }] };
const task = { taskType: "DATA_ACCESS_GRANT", resourceId, caseId, state: "APPROVAL_PENDING", version: 2, requesterActorId: actorId, updatedAt: "2026-08-29T05:28:00Z", allowedActions: ["APPROVE", "DENY"] };
const actor = { displayName: "김사랑님", teamLabel: "운영팀 · 상담" };
const inWorkspace = (content: ReactNode) => <SupportWorkspaceShell actor={actor} hasUnreadNotification>{content}</SupportWorkspaceShell>;
const queueItems = Array.from({ length: 8 }, (_, index) => ({
  ...queueItem,
  caseId: `10000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
  state: ["IN_PROGRESS", "WAITING", "IN_PROGRESS", "WAITING"][index % 4],
  priority: ["URGENT", "HIGH", "NORMAL", "NORMAL"][index % 4],
  category: ["PAYMENT_OR_REFUND", "DELIVERY", "ACCOUNT", "PRODUCT_OR_STOCK"][index % 4],
  version: index + 2,
  latestChangedAt: `2026-08-29T05:${String(28 - index * 3).padStart(2, "0")}:00Z`,
  latestChannel: ["PHONE", "CHAT", "EMAIL", "WEB"][index % 4],
  primarySubject: { ...queueItem.primarySubject, maskedDisplayName: ["김*연", "박*민", "이*준", "최*영"][index % 4] },
}));
const timelineItems = ["상담 접수", "담당자 배정", "주문 연결", "본인확인 요청", "고객 연락", "처리 메모 기록"].map((summary, index) => ({
  itemId: `20000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
  source: index < 2 ? "SUPPORT" : index === 2 ? "ORDERING" : "AUDIT",
  type: index === 2 ? "ORDER_LINK" : "CASE_STATE",
  state: index === 5 ? "IN_PROGRESS" : "OPEN",
  summary,
  amountKrw: null,
  occurredAt: `2026-08-29T05:${String(10 + index * 3).padStart(2, "0")}:00Z`,
}));
const approvalTasks = [
  task,
  { ...task, taskType: "BREAK_GLASS", resourceId: "30000000-0000-4000-8000-000000000002", state: "REVIEW_PENDING", updatedAt: "2026-08-29T05:24:00Z", allowedActions: ["REVIEW"] },
  { ...task, taskType: "SUPPORT_ACTION", resourceId: "30000000-0000-4000-8000-000000000003", state: "AWAITING_SUPPORT_MANAGER", updatedAt: "2026-08-29T05:18:00Z", allowedActions: ["APPROVE", "DENY", "RETURN_FOR_REVISION"] },
  { ...task, taskType: "COMPENSATION", resourceId: "30000000-0000-4000-8000-000000000004", state: "READY_FOR_EXECUTION", updatedAt: "2026-08-29T05:12:00Z", allowedActions: ["EXECUTE"] },
  { ...task, taskType: "PROFILE_CHANGE", resourceId: "30000000-0000-4000-8000-000000000005", state: "REASSIGNMENT_REQUIRED", updatedAt: "2026-08-29T05:06:00Z", allowedActions: ["REASSIGN"] },
];

const handlers = [
  http.get("/api/v1/support/case-queue/summary", () => HttpResponse.json({ active: 24, open: 12, inProgress: 8, waiting: 4, urgent: 3 })),
  http.get("/api/v1/support/case-queue", () => HttpResponse.json({ items: queueItems })),
  http.post("/api/v1/support/searches", () => HttpResponse.json({ items: [
    { subjectType: "CUSTOMER", subjectId: customerId, maskedDisplayName: "김*연", maskedMatchedValue: "010-12**-5678" },
    { subjectType: "CUSTOMER", subjectId: "10000000-0000-4000-8000-000000000006", maskedDisplayName: "김*연", maskedMatchedValue: "010-34**-8901" },
    { subjectType: "STORE", subjectId: "10000000-0000-4000-8000-000000000007", maskedDisplayName: "빈플로우 카페 강남점", maskedMatchedValue: "매장 ID 123456" },
  ] })),
  http.get("/api/v1/support/cases/:caseId/overview", () => HttpResponse.json({ case: queueItem, subjects: [queueItem.primarySubject], orders: [order], availableSections: ["DETAIL", "VERIFICATION", "ORDER_ACTION", "COMPENSATION", "PROFILE_CHANGE", "AUDIT"] })),
  http.get("/api/v1/support/cases/:caseId/timeline", () => HttpResponse.json({ items: timelineItems, nextCursor: null })),
  http.post("/api/v1/support/cases/:caseId/notes", () => HttpResponse.json({ noteId: resourceId, summary: "NOTE_RECORDED", createdAt: "2026-08-29T05:30:00Z", caseVersion: 5 })),
  http.post("/api/v1/support/cases/:caseId/interactions", () => HttpResponse.json({ interactionId: resourceId, channel: "PHONE", direction: "INBOUND", summary: "INTERACTION_RECORDED", occurredAt: "2026-08-29T05:30:00Z", recordedAt: "2026-08-29T05:30:01Z", caseVersion: 5 })),
  http.get("/api/v1/support/cases/:caseId", () => HttpResponse.json({ caseId, state: "IN_PROGRESS", priority: "URGENT", assigneeId: actorId, version: 4, openedAt: "2026-08-29T05:10:00Z", subjectLinks: [{ linkId: resourceId, subjectType: "CUSTOMER", subjectId: customerId, relationship: "REQUESTER", linkedAt: "2026-08-29T05:10:00Z", caseVersion: 2 }] })),
  http.get("/api/v1/support/orders/:orderId/overview", () => HttpResponse.json(order)),
  http.post("/api/v1/support/cases/:caseId/action-evaluations", () => HttpResponse.json({ action: "ORDER_CANCELLATION", orderId, decision: "APPROVAL_REQUIRED", reasonCodes: ["POLICY_APPROVAL_REQUIRED"], requiredPermissions: ["SUPPORT_ACTION_REQUEST", "SUPPORT_ORDER_CANCEL"], requiredVerificationLevel: "ENHANCED", approvalRequirements: ["SUPPORT_MANAGER"], policyVersion: "support-action-policy/2026-08-12/v1", targetVersion: 7, evaluatedAt: "2026-08-29T05:29:00Z", expiresAt: "2026-08-29T05:31:00Z" })),
  http.get("/api/v1/support/compensations", () => HttpResponse.json({ items: [
    { requestId: resourceId, caseId, benefitType: "COUPON", amountKrw: 12_000, band: "MEDIUM", state: "AWAITING_SUPPORT_MANAGER", notificationState: "NOT_REQUESTED", version: 2, updatedAt: "2026-08-29T05:28:00Z" },
    { requestId: "40000000-0000-4000-8000-000000000002", caseId, benefitType: "POINT", amountKrw: 5_000, band: "LOW", state: "READY_FOR_EXECUTION", notificationState: "NOT_REQUESTED", version: 3, updatedAt: "2026-08-29T05:18:00Z" },
    { requestId: "40000000-0000-4000-8000-000000000003", caseId, benefitType: "COUPON", amountKrw: 3_000, band: "LOW", state: "NOTIFICATION_RETRY", notificationState: "RETRY_SCHEDULED", version: 5, updatedAt: "2026-08-29T05:08:00Z" },
  ] })),
  http.get("/api/v1/support/profile-changes", () => HttpResponse.json({ items: [
    { profileChangeId: resourceId, caseId, subjectType: "CUSTOMER", purpose: "CUSTOMER_PRIMARY_PHONE", riskClass: "R3", state: "AWAITING_APPROVAL", notificationState: "PENDING", maskedBefore: "010-12**-5678", maskedAfter: "010-98**-7654", version: 3, updatedAt: "2026-08-29T05:28:00Z" },
    { profileChangeId: "50000000-0000-4000-8000-000000000002", caseId, subjectType: "CUSTOMER", purpose: "CUSTOMER_DISPLAY_NAME", riskClass: "R1", state: "EXECUTED", notificationState: "ACCEPTED", maskedBefore: "김*연", maskedAfter: "김*윤", version: 2, updatedAt: "2026-08-29T05:18:00Z" },
    { profileChangeId: "50000000-0000-4000-8000-000000000003", caseId, subjectType: "STORE", purpose: "STORE_SETTLEMENT_ACCOUNT", riskClass: "R4", state: "READY_FOR_EXECUTION", notificationState: "PENDING", maskedBefore: "국민 ****-01", maskedAfter: "신한 ****-88", version: 4, updatedAt: "2026-08-29T05:08:00Z" },
  ] })),
  http.get("/api/v1/support/approval-tasks", () => HttpResponse.json({ items: approvalTasks })),
  http.get("/api/v1/support/approval-tasks/:taskType/:resourceId", () => HttpResponse.json({ task, lineage: [{ step: "REQUESTED", state: "APPROVAL_PENDING", actorId, occurredAt: "2026-08-29T05:28:00Z" }] })),
  http.get("/api/v1/support/approval-tasks/:taskType/:resourceId/timeline", () => HttpResponse.json({ items: [{ eventId: resourceId, eventType: "REQUESTED", state: "APPROVAL_PENDING", actorId, occurredAt: "2026-08-29T05:28:00Z" }] })),
  http.post("/api/v1/support/data-access-grants/:grantId/approvals", () => HttpResponse.json({ grantId: resourceId, caseId, subjectLinkId: resourceId, subjectType: "CUSTOMER", subjectId: customerId, purpose: "CONTACT_CONFIRMATION", fields: ["CUSTOMER_PRIMARY_PHONE"], risk: "SENSITIVE", state: "ACTIVE", maxReveals: 1, reservedReveals: 0, requestedAt: "2026-08-29T05:28:00Z", expiresAt: "2026-08-29T05:58:00Z", version: 3 })),
];

const meta = {
  title: "Pages/Support center/Runtime routes",
  component: SupportQueueRoute,
  tags: ["autodocs"],
  parameters: { layout: "fullscreen", a11y: { test: "error" }, msw: { handlers }, routing: { path: "/support/queue", initialEntry: "/support/queue" }, docs: { story: { inline: false, height: "922px" } } },
  render: () => inWorkspace(<SupportQueueRoute />),
} satisfies Meta<typeof SupportQueueRoute>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Queue: Story = { play: async ({ canvas }) => { await expect(await canvas.findByText("상담 대기열")).toBeVisible(); } };
export const IndexRedirect: Story = { render: () => <SupportIndexRoute />, parameters: { routing: { path: "/support", initialEntry: "/support" } } };
export const Intake: Story = {
  render: () => inWorkspace(<SupportCaseIntakeRoute />),
  parameters: { routing: { path: "/support/cases/new", initialEntry: "/support/cases/new" } },
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText("검색 값"), "010-1234-5678");
    await userEvent.click(canvas.getByRole("button", { name: "검색" }));
    await expect(await canvas.findAllByRole("button", { name: "상담 생성" })).toHaveLength(3);
  },
};
export const Detail: Story = {
  render: () => inWorkspace(<SupportCaseDetailRoute />),
  parameters: { routing: { path: "/support/cases/:caseId", initialEntry: `/support/cases/${caseId}` } },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("새 내부 메모"), "고객이 재배송을 원하지 않는다고 확인함");
    await userEvent.click(canvas.getByRole("button", { name: "메모 추가" }));
    await expect(await canvas.findByText("메모가 비식별 기록으로 저장되었습니다.")).toBeVisible();
  },
};
export const Verification: Story = { render: () => inWorkspace(<SupportVerificationRoute />), parameters: { routing: { path: "/support/cases/:caseId/verification", initialEntry: `/support/cases/${caseId}/verification` } } };
export const OrderAction: Story = {
  render: () => inWorkspace(<SupportOrderActionRoute />),
  parameters: { routing: { path: "/support/cases/:caseId/orders/:orderId/action", initialEntry: `/support/cases/${caseId}/orders/${orderId}/action` } },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("본인확인 세션 ID"), "60000000-0000-4000-8000-000000000001");
    await userEvent.click(canvas.getByRole("button", { name: "가능 여부 평가" }));
    await expect(await canvas.findByText("APPROVAL_REQUIRED")).toBeVisible();
  },
};
export const Compensation: Story = { render: () => inWorkspace(<SupportCompensationRoute />), parameters: { routing: { path: "/support/cases/:caseId/compensations/:requestId?", initialEntry: `/support/cases/${caseId}/compensations/${resourceId}` } } };
export const ProfileChange: Story = { render: () => inWorkspace(<SupportProfileChangeRoute />), parameters: { routing: { path: "/support/cases/:caseId/profile-changes/:profileChangeId?", initialEntry: `/support/cases/${caseId}/profile-changes/${resourceId}` } } };
export const Approvals: Story = {
  render: () => inWorkspace(<SupportApprovalsRoute />),
  parameters: { routing: { path: "/support/approvals/:taskType?/:resourceId?", initialEntry: `/support/approvals/DATA_ACCESS_GRANT/${resourceId}` } },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "APPROVE" }));
    await expect(await canvas.findByText("열람 승인이 완료되었습니다.")).toBeVisible();
  },
};

export const DependencyFailure: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/support/case-queue/summary", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", message: "Owner query unavailable" }, { status: 503 })), http.get("/api/v1/support/case-queue", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", message: "Owner query unavailable" }, { status: 503 }))] } },
};

export const PermissionFailure: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/support/case-queue/summary", () => HttpResponse.json({ code: "ACCESS_DENIED", message: "Forbidden" }, { status: 403 })), http.get("/api/v1/support/case-queue", () => HttpResponse.json({ code: "ACCESS_DENIED", message: "Forbidden" }, { status: 403 }))] } },
};
