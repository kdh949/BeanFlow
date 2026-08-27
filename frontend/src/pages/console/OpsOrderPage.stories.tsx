import type { Meta, StoryObj } from "@storybook/react-vite";
import { HttpResponse, http } from "msw";
import { expect, userEvent } from "storybook/test";
import { compensationManualReview, compensationSucceeded } from "../../../.storybook/fixtures";
import { ApiRequestError } from "../../api/client";
import { ErrorState, LoadingState } from "../../design-system";
import { CompensationResult, OpsOrderPage } from "./ConsolePages";

const meta = {
  title: "Pages/Operations/CompensationLookup",
  component: OpsOrderPage,
  tags: ["autodocs"],
  parameters: {
    routing: { path: "/ops/orders", initialEntry: "/ops/orders" },
    docs: {
      description: { component: "감사 사유가 있는 운영자 보상 조회와 불확실한 복구 단계를 표시하는 현재 route입니다." },
    },
  },
} satisfies Meta<typeof OpsOrderPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Idle: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByText("감사 조회 대기")).toBeVisible();
  },
};

export const ManualReview: Story = {
  render: () => <div className="console-page"><CompensationResult result={compensationManualReview} /></div>,
  parameters: { docs: { description: { story: "동일한 product result component로 UNKNOWN step과 MANUAL_REVIEW case를 고정 재현합니다." } } },
  play: async ({ canvas }) => {
    await expect(canvas.getByText("매장 거절 보상")).toBeVisible();
    await expect(canvas.getByText(/PROVIDER_TIMEOUT/)).toBeVisible();
  },
};

async function submitLookup(canvas: Parameters<NonNullable<Story["play"]>>[0]["canvas"]) {
  await userEvent.type(canvas.getByLabelText("주문 번호"), "40000000-0000-4000-8000-000000000001");
  await userEvent.type(canvas.getByLabelText("접근 사유"), "compensation recovery review");
  await userEvent.click(canvas.getByRole("button", { name: "조회" }));
}

export const SuccessfulLookup: Story = {
  render: () => <div className="console-page"><CompensationResult result={compensationSucceeded} /></div>,
  play: async ({ canvas }) => {
    await expect(canvas.getByText("완료")).toBeVisible();
  },
};

export const RecoverableError: Story = {
  render: () => <div className="console-page"><ErrorState error={new ApiRequestError(503, "DEPENDENCY_UNAVAILABLE", "서비스 연결을 확인하고 있습니다.", "REQ-DEMO-42")} /></div>,
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("alert")).toBeVisible();
  },
};

export const Loading: Story = {
  render: () => <div className="console-page"><LoadingState label="보상 상태를 조회하는 중" /></div>,
  play: async ({ canvas }) => {
    await expect(canvas.getByText("보상 상태를 조회하는 중")).toBeVisible();
  },
};

export const SuccessfulLookupInteraction: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: { handlers: [http.get(/\/operations\/orders\/[^/]+\/compensation$/, () => HttpResponse.json({ compensation: compensationSucceeded }))] },
  },
  play: async ({ canvas }) => {
    await submitLookup(canvas);
    await expect(await canvas.findByText("완료")).toBeVisible();
  },
};
