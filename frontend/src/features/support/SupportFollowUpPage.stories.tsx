import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { SupportFollowUpPage } from "./SupportFollowUpPage";

const supportCase = { caseId: "case-demo-01", state: "IN_PROGRESS", assigneeLabel: "상담사 SU••17", version: 8 } as const;
const events = [
  { reference: "assignment-demo-01", label: "결제 전문 상담사에게 배정", state: "ASSIGNED", occurredAt: "2026-09-04T13:20:00+09:00" },
  { reference: "interaction-demo-01", label: "전화 상담 내용", state: "RECORDED", occurredAt: "2026-09-04T13:24:00+09:00" },
  { reference: "note-demo-01", label: "내부 메모", state: "RECORDED", occurredAt: "2026-09-04T13:26:00+09:00" },
] as const;
const actionRequests = [{ reference: "action-demo-01", action: "PICKUP_RESCHEDULE", state: "READY_FOR_EXECUTION", approvalRoute: "SUPPORT_MANAGER", requester: "SU••17", executor: "SU••23", version: 4 }] as const;
const resolutions = [{ reference: "resolution-demo-01", outcome: "PARTIAL_REFUND", responsibility: "STORE", state: "PARTIALLY_RESOLVED", steps: [{ label: "현금 환불", state: "SUCCEEDED" }, { label: "정산 조정", state: "RECONCILING" }, { label: "고객 알림", state: "RETRY_SCHEDULED" }] }] as const;
const compensations = [{ reference: "compensation-demo-01", benefit: "포인트 10,000원", state: "BENEFIT_ISSUED", notificationState: "RETRY_SCHEDULED" }] as const;
const profileChanges = [{ reference: "profile-demo-01", purpose: "CUSTOMER_PRIMARY_PHONE", risk: "R3", state: "READY_FOR_EXECUTION", notificationState: "PENDING" }] as const;
const breakGlassRequests = [{ reference: "break-glass-demo-01", field: "CUSTOMER_PRIMARY_PHONE", purpose: "ACTIVE_FRAUD", state: "REVIEW_PENDING", expiresAt: "2026-09-04T13:40:00+09:00" }] as const;

const meta = {
  title: "Pages/Support/Follow-up",
  component: SupportFollowUpPage,
  tags: ["autodocs"],
  args: {
    scenario: "ready",
    supportCase,
    events,
    actionRequests,
    resolutions,
    compensations,
    profileChanges,
    breakGlassRequests,
    onCommand: async () => undefined,
  },
  parameters: {
    docs: { description: { component: "상담 건에 연결된 기록, 주문 처리, 보상, 정보 변경과 긴급 열람 업무를 확인하는 화면입니다." }, story: { inline: false, height: "880px" } },
    routing: { path: "/support/follow-up", initialEntry: "/support/follow-up?caseId=case-demo-01" },
  },
} satisfies Meta<typeof SupportFollowUpPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const CaseCollaboration: Story = { args: { initialWorkspace: "collaboration" }, play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "담당자와 상담 기록" })).toBeVisible(); await expect(canvas.getByText("전화 상담 내용")).toBeVisible(); } };
export const ActionApprovalAndExecution: Story = {
  args: { initialWorkspace: "actions" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "주문 요청 처리" })).toBeVisible();
    await expect(canvas.getByText("픽업 시간 변경")).toBeVisible();
    await expect(canvas.getByText("실행 준비")).toBeVisible();
    await expect(canvas.queryAllByText(/PICKUP_RESCHEDULE|SUPPORT_MANAGER|READY_FOR_EXECUTION|writer|Provider|immutable|workspace|원장/i)).toHaveLength(0);
    await userEvent.click(canvas.getByRole("button", { name: "실행하기" }));
    await expect(canvas.getByRole("status")).toHaveTextContent("실행 요청을 보냈습니다");
  },
};
export const PostAcceptanceResolution: Story = { args: { initialWorkspace: "resolutions" }, play: async ({ canvas }) => { await expect(await canvas.findByText("정산 조정")).toBeVisible(); await expect(canvas.getByText("복구 중")).toBeVisible(); } };
export const CompensationExecution: Story = { args: { initialWorkspace: "compensations" }, play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "보상과 알림" })).toBeVisible(); await expect(canvas.getByRole("button", { name: "알림 다시 보내기" })).toBeVisible(); } };
export const ProfileChanges: Story = { args: { initialWorkspace: "profiles" }, play: async ({ canvas }) => { await expect(await canvas.findByText("기본 연락처")).toBeVisible(); } };
export const BreakGlassReview: Story = { args: { initialWorkspace: "break-glass" }, play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "긴급 정보 열람 요청" })).toBeVisible(); await expect(canvas.getByRole("button", { name: "정보 보기" })).toBeVisible(); } };
export const CaseRequired: Story = {
  args: { scenario: "case-required", supportCase: undefined, events: [], actionRequests: [], resolutions: [], compensations: [], profileChanges: [], breakGlassRequests: [], onCommand: undefined },
  play: async ({ canvas }) => { await expect(await canvas.findByText("상담 건을 먼저 열어 주세요")).toBeVisible(); },
};
