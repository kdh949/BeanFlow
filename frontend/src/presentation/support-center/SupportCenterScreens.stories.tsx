import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import {
  SupportApprovalsScreen,
  SupportCaseDetailScreen,
  SupportCaseIntakeScreen,
  SupportCompensationScreen,
  SupportOrderActionScreen,
  SupportProfileChangeScreen,
  SupportQueueScreen,
  SupportVerificationScreen,
  type ApprovalDetail,
  type ApprovalPage,
  type ApprovalTimeline,
  type CaseOverview,
  type CompensationPage,
  type ProfileChangePage,
  type QueuePage,
  type QueueSummary,
  type SearchResult,
} from "./SupportCenterScreens";

const id = {
  case: "10000000-0000-4000-8000-000000000001",
  actor: "10000000-0000-4000-8000-000000000002",
  customer: "10000000-0000-4000-8000-000000000003",
  order: "10000000-0000-4000-8000-000000000004",
  task: "10000000-0000-4000-8000-000000000005",
};

const summary: QueueSummary = { active: 24, open: 12, inProgress: 8, waiting: 4, urgent: 3 };
const queueItem: QueuePage["items"][number] = { caseId: id.case, state: "IN_PROGRESS", priority: "URGENT", category: "PAYMENT_OR_REFUND", assigneeId: id.actor, version: 4, openedAt: "2026-08-29T05:10:00Z", latestChangedAt: "2026-08-29T05:28:00Z", latestChannel: "PHONE", primarySubject: { subjectType: "CUSTOMER", subjectId: id.customer, maskedDisplayName: "김*연", maskedMatchedValue: "010-12**-5678" } };
const queuePage: QueuePage = { items: [
  queueItem,
  { caseId: "10000000-0000-4000-8000-000000000011", state: "WAITING", priority: "NORMAL", category: "ORDER_STATUS", assigneeId: id.actor, version: 2, openedAt: "2026-08-29T04:15:00Z", latestChangedAt: "2026-08-29T05:13:00Z", latestChannel: "CHAT" },
] };
const order = { orderId: id.order, publicReference: "#BF-2026-0829-001", state: "PAID", version: 7, orderedAt: "2026-08-29T04:30:00Z", pickupWindowStart: "2026-08-29T05:00:00Z", pickupWindowEnd: "2026-08-29T05:10:00Z", storeName: "빈플로우 카페 강남점", subtotalKrw: 18_900, couponDiscountKrw: 0, pointsAppliedKrw: 0, payableKrw: 18_900, currency: "KRW" as const, paymentState: "PAID" as const, paidAt: "2026-08-29T04:31:00Z", lines: [{ sequence: 1, menuName: "아메리카노", quantity: 2, amountKrw: 9_000 }, { sequence: 2, menuName: "카페 라테", quantity: 1, amountKrw: 9_900 }] };
const overview: CaseOverview = { case: queueItem, subjects: [queueItem.primarySubject!], orders: [order], availableSections: ["DETAIL", "VERIFICATION", "ORDER_ACTION", "COMPENSATION", "PROFILE_CHANGE", "AUDIT"] };
const searchResult: SearchResult = { searchId: id.task, matchedCount: 2, ambiguous: true, hasMore: false, items: [
  { subjectType: "CUSTOMER", subjectId: id.customer, maskedDisplayName: "김*연", matchedCriterionType: "PHONE", maskedMatchedValue: "010-12**-5678" },
  { subjectType: "CUSTOMER", subjectId: "10000000-0000-4000-8000-000000000013", maskedDisplayName: "김*연", matchedCriterionType: "PHONE", maskedMatchedValue: "010-12**-5678" },
] };
const compensationItem: CompensationPage["items"][number] = { requestId: id.task, caseId: id.case, benefitType: "COUPON", amountKrw: 12_000, band: "MEDIUM", state: "AWAITING_SUPPORT_MANAGER", notificationState: "NOT_REQUESTED", version: 2, updatedAt: "2026-08-29T05:28:00Z" };
const compensationPage: CompensationPage = { items: [compensationItem] };
const profileItem: ProfileChangePage["items"][number] = { profileChangeId: id.task, caseId: id.case, subjectType: "CUSTOMER", purpose: "PRIMARY_PHONE_CHANGE", riskClass: "R3", state: "AWAITING_SUPPORT_MANAGER", notificationState: "NOT_REQUESTED", maskedBefore: "010-12**-5678", maskedAfter: "010-98**-7654", version: 3, updatedAt: "2026-08-29T05:28:00Z" };
const profilePage: ProfileChangePage = { items: [profileItem] };
const approvalTask = { taskType: "DATA_ACCESS_GRANT" as const, resourceId: id.task, caseId: id.case, state: "APPROVAL_PENDING", version: 2, requesterActorId: id.actor, updatedAt: "2026-08-29T05:28:00Z", allowedActions: ["APPROVE", "DENY"] as ("APPROVE" | "DENY")[] };
const approvalPage: ApprovalPage = { items: [approvalTask] };
const approvalDetail: ApprovalDetail = { task: approvalTask, lineage: [{ step: "REQUESTED", state: "APPROVAL_PENDING", actorId: id.actor, occurredAt: "2026-08-29T05:28:00Z" }] };
const approvalTimeline: ApprovalTimeline = { items: [{ eventId: id.task, eventType: "REQUESTED", state: "APPROVAL_PENDING", actorId: id.actor, occurredAt: "2026-08-29T05:28:00Z" }] };

const meta = {
  title: "Pages/Support center/Screens",
  component: SupportQueueScreen,
  tags: ["autodocs"],
  args: { status: "ready", summary, page: queuePage, filters: { scope: "MINE", state: "", priority: "" } },
  parameters: { layout: "fullscreen", a11y: { test: "error" }, docs: { story: { inline: false, height: "922px" } }, routing: { path: "/support/*", initialEntry: "/support/queue" } },
} satisfies Meta<typeof SupportQueueScreen>;

export default meta;
type Story = StoryObj<typeof meta>;

export const QueueReady: Story = { play: async ({ canvas }) => { await expect(canvas.getByRole("table", { name: "상담 대기열" })).toBeVisible(); await expect(canvas.getByText("24")).toBeVisible(); } };
export const QueueLoading: Story = { args: { status: "loading", summary: undefined, page: undefined } };
export const QueueEmpty: Story = { args: { page: { items: [] } }, play: async ({ canvas }) => { await expect(canvas.getByText("조건에 맞는 상담이 없습니다")).toBeVisible(); } };
export const DependencyError: Story = { args: { status: "error", summary: undefined, page: undefined } };
export const PermissionDenied: Story = { args: { status: "permission", summary: undefined, page: undefined } };

export const IntakeBeforeSearch: Story = { render: () => <SupportCaseIntakeScreen status="ready" criterionType="PHONE" criterion="" /> };
export const IntakeAmbiguous: Story = { render: () => <SupportCaseIntakeScreen status="ready" criterionType="PHONE" criterion="" result={searchResult} /> };
export const IntakeRateLimited: Story = { render: () => <SupportCaseIntakeScreen status="error" criterionType="PHONE" criterion="" /> };

export const CaseActive: Story = { render: () => <SupportCaseDetailScreen status="ready" overview={overview} timeline={[{ itemId: id.task, source: "SUPPORT", type: "CASE_STATE", state: "OPEN", summary: "상담이 접수됨", amountKrw: null, occurredAt: "2026-08-29T05:10:00Z" }]} /> };
export const CaseTerminal: Story = { render: () => <SupportCaseDetailScreen status="ready" overview={{ ...overview, case: { ...overview.case, state: "CLOSED" } }} timeline={[]} /> };

export const VerificationPending: Story = { render: () => <SupportVerificationScreen status="ready" state="pending" verificationCode="" /> };
export const VerificationInvalid: Story = { render: () => <SupportVerificationScreen status="ready" state="invalid" verificationCode="123456" /> };
export const VerificationLocked: Story = { render: () => <SupportVerificationScreen status="ready" state="locked" verificationCode="" /> };
export const VerificationExpired: Story = { render: () => <SupportVerificationScreen status="ready" state="expired" verificationCode="" /> };
export const VerificationGrantPending: Story = { render: () => <SupportVerificationScreen status="ready" state="grant-pending" verificationCode="" /> };
export const VerificationActive: Story = { render: () => <SupportVerificationScreen status="ready" state="active" verificationCode="" revealedValue="010-1234-5678" /> };

export const OrderActionAllowed: Story = { render: () => <SupportOrderActionScreen status="ready" overview={order} actionState="allowed" /> };
export const OrderApprovalRequired: Story = { render: () => <SupportOrderActionScreen status="ready" overview={order} actionState="approval-required" /> };
export const OrderStale: Story = { render: () => <SupportOrderActionScreen status="ready" overview={order} actionState="stale" /> };
export const OrderReconciliation: Story = { render: () => <SupportOrderActionScreen status="ready" overview={order} actionState="reconciling" /> };
export const OrderManualReview: Story = { render: () => <SupportOrderActionScreen status="ready" overview={order} actionState="manual-review" /> };

export const CompensationApprovalPending: Story = { render: () => <SupportCompensationScreen status="ready" page={compensationPage} /> };
export const CompensationIssued: Story = { render: () => <SupportCompensationScreen status="ready" page={{ items: [{ ...compensationItem, state: "ISSUED", notificationState: "ACCEPTED" }] }} /> };
export const CompensationNotificationReview: Story = { render: () => <SupportCompensationScreen status="ready" page={{ items: [{ ...compensationItem, state: "ISSUED", notificationState: "MANUAL_REVIEW" }] }} /> };

export const ProfileR3Approval: Story = { render: () => <SupportProfileChangeScreen status="ready" page={profilePage} /> };
export const ProfileExecuted: Story = { render: () => <SupportProfileChangeScreen status="ready" page={{ items: [{ ...profileItem, state: "EXECUTED", notificationState: "ACCEPTED" }] }} /> };
export const ProfileStale: Story = { render: () => <SupportProfileChangeScreen status="ready" page={{ items: [{ ...profileItem, state: "STALE" }] }} /> };

export const ApprovalsPending: Story = { render: () => <SupportApprovalsScreen status="ready" page={approvalPage} detail={approvalDetail} timeline={approvalTimeline} /> };
export const ApprovalsEmpty: Story = { render: () => <SupportApprovalsScreen status="ready" page={{ items: [] }} /> };
export const ApprovalsDenied: Story = { render: () => <SupportApprovalsScreen status="ready" page={{ items: [{ ...approvalTask, state: "DENIED", allowedActions: [] }] }} detail={{ task: { ...approvalTask, state: "DENIED", allowedActions: [] }, lineage: approvalDetail.lineage }} timeline={approvalTimeline} /> };
export const ApprovalsReassignment: Story = { render: () => <SupportApprovalsScreen status="ready" page={{ items: [{ ...approvalTask, taskType: "SUPPORT_ACTION", state: "REASSIGNMENT_REQUIRED", allowedActions: ["REASSIGN"] }] }} /> };
