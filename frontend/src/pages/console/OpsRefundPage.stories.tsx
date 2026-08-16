import type { Meta, StoryObj } from "@storybook/react-vite";
import { HttpResponse, http } from "msw";
import { expect, userEvent } from "storybook/test";
import { refund } from "../../../.storybook/fixtures";
import { ApiRequestError } from "../../api/client";
import { ErrorState, LoadingState } from "../../components/Ui";
import { OpsRefundPage, RefundResult } from "./ConsolePages";

const meta = {
  title: "Pages/Operations/RefundAdjustment",
  component: OpsRefundPage,
  tags: ["autodocs"],
  parameters: {
    routing: { path: "/ops/refunds", initialEntry: "/ops/refunds" },
    docs: {
      description: { component: "Plan 90이 교체하기 전 legacy 환불 form의 현재 계약입니다. 현재 결과 상태를 재현하지만 Session/CSRF 전환을 완료한 것으로 간주하지 않습니다." },
    },
  },
} satisfies Meta<typeof OpsRefundPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const FullRefundForm: Story = {};

export const PartialRefundForm: Story = {
  args: { initialPartial: true },
  play: async ({ canvas }) => {
    await expect(canvas.getByText("주문 상품 번호")).toBeVisible();
  },
};

async function submitFullRefund(canvas: Parameters<NonNullable<Story["play"]>>[0]["canvas"]) {
  await userEvent.type(canvas.getByLabelText("결제 번호"), "50000000-0000-4000-8000-000000000001");
  await userEvent.type(canvas.getByLabelText("환불 사유"), "고객 요청에 따른 전액 환불");
  await userEvent.click(canvas.getByRole("button", { name: "전액 환불 요청" }));
}

export const SuccessfulRefund: Story = {
  render: () => <div className="console-page narrow-console-page"><RefundResult result={refund("SUCCEEDED")} /></div>,
  play: async ({ canvas }) => {
    await expect(canvas.getByText("현금 환불이 확인되었습니다")).toBeVisible();
  },
};

export const UnknownReconciliation: Story = {
  render: () => <div className="console-page narrow-console-page"><RefundResult result={refund("UNKNOWN")} /></div>,
  play: async ({ canvas }) => {
    await expect(canvas.getByText("환불 결과를 확인 중입니다")).toBeVisible();
  },
};

export const RecoverableError: Story = {
  render: () => <div className="console-page narrow-console-page"><ErrorState error={new ApiRequestError(503, "DEPENDENCY_UNAVAILABLE", "서비스 연결을 확인하고 있습니다.", "REQ-DEMO-42")} /></div>,
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("alert")).toBeVisible();
  },
};

export const Loading: Story = {
  render: () => <div className="console-page narrow-console-page"><LoadingState label="환불 요청 중" /></div>,
  play: async ({ canvas }) => {
    await expect(canvas.getByText("환불 요청 중")).toBeVisible();
  },
};

export const SuccessfulRefundInteraction: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        http.get("/api/v1/auth/merchant/csrf", () => new HttpResponse(null, { status: 204 })),
        http.post("/api/v1/payments/:paymentId/refunds", ({ request }) => {
          if (request.headers.get("X-BEANFLOW-CSRF") !== "merchant-csrf-token") {
            return HttpResponse.json({ code: "CSRF_TOKEN_INVALID", message: "CSRF token is required." }, { status: 403 });
          }
          return HttpResponse.json(refund("SUCCEEDED"), { status: 201 });
        }),
      ],
    },
  },
  beforeEach: () => {
    document.cookie = "BEANFLOW_MERCHANT_XSRF=merchant-csrf-token; path=/";
    return () => {
      document.cookie = "BEANFLOW_MERCHANT_XSRF=; Max-Age=0; path=/";
    };
  },
  play: async ({ canvas }) => {
    await submitFullRefund(canvas);
    await expect(await canvas.findByText("현금 환불이 확인되었습니다")).toBeVisible();
  },
};
