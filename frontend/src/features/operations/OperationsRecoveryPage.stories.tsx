import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { OperationsRecoveryPage } from "./OperationsRecoveryPage";

const failureSummary = [
  { type: "PAYMENT", attentionCount: 7, oldestAge: "18분" },
  { type: "NOTIFICATION", attentionCount: 3, oldestAge: "9분" },
  { type: "SETTLEMENT", attentionCount: 1, oldestAge: "1일" },
] as const;
const failureItems = [{
  workReference: "work-demo-01",
  queueType: "PAYMENT",
  sourceState: "RECONCILING",
  attentionState: "ACTION_REQUIRED",
  attemptCount: 4,
  attemptCountAvailable: true,
  updatedAt: "2026-09-04T13:42:00+09:00",
  correlationId: "REQ-DEMO-042",
  summary: "승인 결과 조회가 끝나지 않아 동일 결제 키로 대사 중입니다.",
  allowedActions: ["RETRY_RECONCILIATION"],
}] as const;
const settlements = [{
  settlementBatchId: "settlement-demo-01",
  storeName: "시청점",
  settlementDate: "2026-09-03",
  state: "CONFIRMED",
  reconciliationState: "MISMATCH",
  storedNetKrw: 1_842_000,
  computedNetKrw: 1_838_500,
  differenceKrw: 3_500,
  reason: "확정 후 부분 환불 조정 1건이 다음 정산으로 이월되지 않았습니다.",
}] as const;
const auditRecords = [{
  auditRecordId: "audit-demo-01",
  occurredAt: "2026-09-04T13:48:00+09:00",
  actor: "운영자 OP••42",
  action: "SUPPORT_DATA_REVEAL",
  target: "CASE case-demo-01",
  reason: "고객 본인확인 후 연락처 확인",
  correlationId: "REQ-DEMO-043",
}] as const;

const meta = {
  title: "Pages/Operations/Recovery",
  component: OperationsRecoveryPage,
  tags: ["autodocs"],
  args: { scenario: "ready", failureSummary, failureItems, settlements, auditRecords },
  parameters: {
    docs: { description: { component: "운영팀이 실패한 업무, 정산 차이와 감사 기록을 확인하고 필요한 복구를 요청하는 화면입니다." }, story: { inline: false, height: "860px" } },
    routing: { path: "/ops/recovery", initialEntry: "/ops/recovery" },
  },
} satisfies Meta<typeof OperationsRecoveryPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const FailureQueues: Story = {
  args: { initialWorkspace: "failures" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "실패한 업무" })).toBeVisible();
    await expect(canvas.getByText("승인 결과 조회가 끝나지 않아 동일 결제 키로 대사 중입니다.")).toBeVisible();
    await expect(canvas.getByText("조치 필요")).toBeVisible();
    await expect(canvas.queryAllByText(/PAYMENT|ACTION_REQUIRED|writer|Provider|immutable|workspace|원장/i)).toHaveLength(0);
  },
};

export const FailureDetailAndRetry: Story = {
  args: { initialWorkspace: "failures", onRetryFailure: async () => undefined },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "다시 처리" }));
    await expect(canvas.getByRole("status")).toHaveTextContent("다시 처리 요청을 보냈습니다");
  },
};

export const SettlementMismatch: Story = {
  args: { initialWorkspace: "settlements" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "정산 대사" })).toBeVisible();
    await expect(canvas.getByText("₩3,500")).toBeVisible();
  },
};

export const AuditTrail: Story = {
  args: { initialWorkspace: "audit" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "감사 기록" })).toBeVisible();
    await expect(canvas.getByText("고객 정보 열람")).toBeVisible();
    await expect(canvas.getByText("REQ-DEMO-043")).toBeVisible();
  },
};

export const ContractPending: Story = {
  args: { scenario: "contract-pending", initialWorkspace: "failures", failureSummary: [], failureItems: [], settlements: [], auditRecords: [] },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toHaveTextContent("문제 확인 화면을 준비하고 있습니다");
  },
};
