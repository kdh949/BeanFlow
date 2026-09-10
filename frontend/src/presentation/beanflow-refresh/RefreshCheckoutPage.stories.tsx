import type { Meta, StoryObj } from "@storybook/react-vite";
import MockDate from "mockdate";
import { requestTossStandardPayment } from "../../payment/toss";
import { expect, userEvent, mocked, waitFor } from "storybook/test";
import { HttpResponse, http } from "msw";
import { checkoutHandlers, publicCheckout, ids, signedInHandlers } from "../../../.storybook/fixtures";
import { RefreshCheckoutPage } from "./CustomerTransactionPages";

const meta = {
  title: "Pages/Refresh/Customer/Checkout",
  component: RefreshCheckoutPage,
  beforeEach: () => { MockDate.set("2026-08-15T03:00:00Z"); mocked(requestTossStandardPayment).mockClear().mockResolvedValue(undefined); return () => MockDate.reset(); },
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen", docs: { story: { inline: false, height: "844px" } },
    routing: { path: "/app/orders/:orderReference/checkout", initialEntry: `/app/orders/${publicCheckout.order.orderReference}/checkout`, surface: "refresh-customer" },
    msw: { handlers: [...signedInHandlers, ...checkoutHandlers] },
  },
} satisfies Meta<typeof RefreshCheckoutPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const PendingPayment: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("다음 결제창에서 카드·간편결제를 선택해 주세요.")).toBeVisible();
    await expect(await canvas.findByRole("button", { name: /12,800.*결제하기/ })).toBeEnabled();
    await expect(canvas.queryByText(/저장된 카드/)).not.toBeInTheDocument();
    await expect(canvas.getByText(/까지 결제해 주세요/)).toBeVisible();
    await expect(canvas.queryByText("예약 만료까지")).not.toBeInTheDocument();
  },
};

export const ExpiredOrder: Story = {
  parameters: { msw: { handlers: [...signedInHandlers, http.get("/api/v1/me/orders/:orderReference/checkout", () => HttpResponse.json({ ...publicCheckout, canPay: false, order: { ...publicCheckout.order, status: "EXPIRED" } }))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toHaveTextContent("결제 시간이 만료됐어요");
    await expect(await canvas.findByRole("button", { name: /결제하기/ })).toBeDisabled();
  },
};

export const ResumeReadyAttempt: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/me/orders/:orderReference/checkout", () => HttpResponse.json({ ...publicCheckout, paymentId: ids.payment, paymentState: "READY", readyAttempt: { paymentId: ids.payment, orderReference: publicCheckout.order.orderReference, state: "READY", providerOrderId: "bf_same_payment", customerKey: "bf_customer_key", orderName: "오트 라떼", amount: { value: 12800, currency: "KRW" }, method: "CARD", successUrl: "https://checkout.beanflow.test/success", failUrl: "https://checkout.beanflow.test/fail", expiresAt: publicCheckout.order.reservationExpiresAt, updatedAt: "2026-08-15T02:55:00Z", correlationId: "CHECKOUT-1" } })), http.get("/api/v1/payment-config", () => HttpResponse.json({ clientKey: "test_ck_storybook" })), ...signedInHandlers] } },
  play: async ({ canvas }) => {
    await waitFor(() => expect(canvas.getByRole("button", { name: /결제하기/ })).toBeEnabled());
    await userEvent.click(canvas.getByRole("button", { name: /결제하기/ }));
    await waitFor(() => expect(sessionStorage.getItem(`beanflow.payment-attempt.${ids.payment}`)).not.toBeNull());
    await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
    await waitFor(() => expect(mocked(requestTossStandardPayment)).toHaveBeenCalledWith("test_ck_storybook", expect.objectContaining({ orderId: "bf_same_payment", amount: { value: 12800, currency: "KRW" } })));
  },
};

export const UnknownPayment: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/me/orders/:orderReference/checkout", () => HttpResponse.json({ ...publicCheckout, canPay: false, paymentId: ids.payment, paymentState: "UNKNOWN" })), ...signedInHandlers] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("결제 결과를 확인하고 있어요. 새 결제를 시작하지 마세요.")).toBeVisible();
    await expect(canvas.getByRole("button", { name: /결제하기/ })).toBeDisabled();
    await expect(mocked(requestTossStandardPayment)).not.toHaveBeenCalled();
  },
};

export const LeaseExpiresWhileOpen: Story = {
  tags: ["!autodocs"],
  parameters: { msw: { handlers: [http.get("/api/v1/me/orders/:orderReference/checkout", () => HttpResponse.json({ ...publicCheckout, canPay: Date.now() < Date.parse("2026-08-15T03:00:00.500Z"), order: { ...publicCheckout.order, reservationExpiresAt: "2026-08-15T03:00:00.500Z", status: Date.now() < Date.parse("2026-08-15T03:00:00.500Z") ? "PENDING_PAYMENT" : "EXPIRED" } })), ...signedInHandlers] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("button", { name: /결제하기/ })).toBeEnabled();
    MockDate.set("2026-08-15T03:00:01Z");
    await expect(await canvas.findByRole("alert")).toHaveTextContent("결제 시간이 만료됐어요");
    await expect(canvas.getByRole("button", { name: /결제하기/ })).toBeDisabled();
  },
};
