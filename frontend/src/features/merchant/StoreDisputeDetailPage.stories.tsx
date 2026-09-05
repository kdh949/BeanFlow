import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { StoreDisputeDetailPage } from "./StoreDisputeDetailPage";

const rejected = {
  disputeId: "dispute-demo-01",
  settlementItemReference: "명세 BF-20260901-0084",
  state: "REJECTED",
  filedAt: "2026-09-01T11:20:00+09:00",
  decidedAt: "2026-09-03T16:40:00+09:00",
  expectedAdjustmentKrw: 8_400,
  heldAmountKrw: 0,
  reasonSummary: "부분 환불 조정액이 정산 명세에 반영되지 않았습니다.",
  decisionSummary: "환불 성공 시각이 정산 확정 이후로 확인되어 다음 정산으로 이월됩니다.",
  evidenceCount: 2,
} as const;

const meta = {
  title: "Pages/Store/Dispute detail",
  component: StoreDisputeDetailPage,
  tags: ["autodocs"],
  parameters: {
    docs: { description: { component: "점주가 이의제기 내용과 검토 결과를 확인하고 다시 검토를 요청하는 화면입니다." }, story: { inline: false, height: "760px" } },
    routing: { path: "/store/disputes/:disputeId", initialEntry: "/store/disputes/dispute-demo-01" },
  },
} satisfies Meta<typeof StoreDisputeDetailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const RejectedWithReappeal: Story = {
  args: { scenario: "ready", dispute: rejected, onRequestReappeal: async () => undefined },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "이의제기 내용" })).toBeVisible();
    await expect(canvas.getByText("검토 결과")).toBeVisible();
    await userEvent.type(canvas.getByLabelText("다시 검토할 이유"), "환불 완료 시각과 명세 반영 시점을 다시 확인해 주세요.");
    await userEvent.click(canvas.getByRole("button", { name: "다시 검토 요청" }));
    await expect(canvas.getByRole("status")).toHaveTextContent("요청을 보냈습니다");
  },
};

export const UnderReview: Story = {
  args: { scenario: "ready", dispute: { ...rejected, state: "UNDER_REVIEW", decidedAt: null, decisionSummary: null } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("검토 중입니다")).toBeVisible();
    await expect(canvas.queryByRole("button", { name: "다시 검토 요청" })).not.toBeInTheDocument();
  },
};

export const ContractPending: Story = {
  args: { scenario: "contract-pending" },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toHaveTextContent("상세 내용을 준비하고 있습니다");
  },
};
