import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { OperationsControlPage } from "./OperationsControlPage";

const records = {
  refundApprovals: [{ reference: "refund-demo-01", storeName: "시청점", amount: "₩48,000", state: "APPROVAL_REQUIRED", reason: "운영 승인 한도 초과" }],
  disputes: [{ reference: "dispute-demo-01", storeName: "성수점", state: "UNASSIGNED", age: "42분", summary: "정산 이월 여부 검토" }],
  traces: [{ correlationId: "REQ-DEMO-052", state: "RECONCILING", steps: ["주문 접수", "결제 승인 결과 확인 중", "결제사 확인 예약"] }],
  couponJobs: [{ reference: "issuance-demo-01", campaign: "가을 웰컴", state: "RETRY_SCHEDULED", attempts: 2 }],
  campaigns: [{ reference: "campaign-demo-01", "title": "가을 웰컴", state: "SCHEDULED", window: "9월 10일 10:00–23:59" }],
  payoutFiles: [{ reference: "payout-demo-01", settlementDate: "2026-09-03", state: "READY", stores: 24, amount: "₩18,420,000" }],
} as const;

const meta = {
  title: "Pages/Operations/Control",
  component: OperationsControlPage,
  tags: ["autodocs"],
  args: { scenario: "ready", ...records },
  parameters: {
    docs: { description: { component: "환불 승인, 이의제기 라우팅, 거래 추적, 쿠폰·캠페인과 지급 파일을 분리된 탭에서 처리합니다." }, story: { inline: false, height: "860px" } },
    routing: { path: "/ops/control", initialEntry: "/ops/control" },
  },
} satisfies Meta<typeof OperationsControlPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const RefundApprovals: Story = { args: { initialWorkspace: "refunds" }, play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "환불 승인" })).toBeVisible(); } };
export const DisputeRouting: Story = { args: { initialWorkspace: "disputes" }, play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "이의제기 배정" })).toBeVisible(); } };
export const CorrelationTrace: Story = { args: { initialWorkspace: "trace" }, play: async ({ canvas }) => { await expect(await canvas.findByText("결제사 확인 예약")).toBeVisible(); await expect(canvas.getByText("추적 ID (Correlation ID)")).toBeVisible(); await expect(canvas.queryAllByText(/Provider|immutable|workspace|원장/i)).toHaveLength(0); } };
export const CouponMonitor: Story = { args: { initialWorkspace: "coupons" }, play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "쿠폰 발급 현황" })).toBeVisible(); } };
export const CampaignBuilder: Story = {
  args: { initialWorkspace: "campaigns", onCreateCampaign: async () => undefined },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("캠페인 이름"), "주말 라떼 할인");
    await userEvent.click(canvas.getByRole("button", { name: "초안 저장" }));
    await expect(canvas.getByRole("status")).toHaveTextContent("캠페인 초안을 저장했습니다");
  },
};
export const PayoutFiles: Story = { args: { initialWorkspace: "payouts" }, play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "정산 지급 파일" })).toBeVisible(); await expect(canvas.getByText(/파일을 만들어도 실제 지급이 완료된 것은 아닙니다/)).toBeVisible(); } };
export const WorkspaceChange: Story = {
  args: { initialWorkspace: "refunds" },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("tab", { name: /거래 추적/ }));
    await expect(canvas.getByRole("heading", { name: "거래 처리 내역" })).toBeVisible();
  },
};
export const ContractPending: Story = {
  args: { scenario: "contract-pending", initialWorkspace: "refunds", refundApprovals: [], disputes: [], traces: [], couponJobs: [], campaigns: [], payoutFiles: [] },
  play: async ({ canvas }) => { await expect(await canvas.findByRole("alert")).toHaveTextContent("이 화면을 준비하고 있습니다"); },
};
