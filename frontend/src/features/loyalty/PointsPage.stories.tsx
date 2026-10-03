import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, pointsHandlers, signedInHandlers } from "../../../.storybook/fixtures";
import { CustomerPointsPage } from "./PointsPage";

const meta = {
  title: "Pages/Customer/Points",
  component: CustomerPointsPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: {
        component:
          "actor-scoped 포인트 조회입니다. 계정 UUID를 입력하지 않으며, 조회 실패를 잔액 0원으로 그리지 않습니다.",
      },
      story: { inline: false, height: "720px" },
    },
    routing: { path: "/app/points", initialEntry: "/app/points" },
  },
} satisfies Meta<typeof CustomerPointsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const BalanceAndLedger: Story = {
  parameters: { msw: { handlers: pointsHandlers } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("1,500P")).toBeVisible();
    await expect(await canvas.findByText("+200P")).toBeVisible();
  },
};

/** A real zero is shown as zero. */
export const ZeroBalance: Story = {
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        http.get("/api/v1/me/points", () => HttpResponse.json({
          availablePointsKrw: 0, recoveryPendingKrw: 0, currency: "KRW", expiring: [],
        })),
        http.get("/api/v1/me/point-transactions", () => HttpResponse.json({ items: [], page: {} })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("0P")).toBeVisible();
    await expect(await canvas.findByText("아직 포인트 내역이 없어요")).toBeVisible();
  },
};

/** A broken point account is never drawn as a zero balance. */
export const AccountIntegrityFailure: Story = {
  parameters: {
    msw: {
      handlers: [
        ...signedInHandlers,
        apiError("/api/v1/me/points", 503, "POINT_ACCOUNT_INTEGRITY_FAILURE", "포인트 계정을 확인할 수 없습니다."),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/정확한 포인트 잔액을 확인할 수 없어/)).toBeVisible();
    await expect(canvas.queryByText("0P")).not.toBeInTheDocument();
  },
};

/** Partial expiry windows remain explicit; only typed order-backed accrual gets a link. */
export const OrderContextAndExpiry: Story = {
  parameters: { msw: { handlers: [
    ...signedInHandlers,
    http.get("/api/v1/me/points", () => HttpResponse.json({
      availablePointsKrw: 1500, recoveryPendingKrw: 0, currency: "KRW", expiringHasMore: true,
      expiring: [1, 2, 3, 4].map((month) => ({ expiresAt: `2027-0${month}-01T00:00:00Z`, amountKrw: 100 })),
    })),
    http.get("/api/v1/me/point-transactions", () => HttpResponse.json({ items: [
      { transactionId: "point-order", type: "ACCRUAL", amountKrw: 200, occurredAt: "2026-10-03T00:00:00Z", sourceReference: "opaque", orderContext: { publicReference: "BF-7K3M-9Q2P", storeName: "시청광장테이크아웃전문점긴한글매장명", firstMenuName: "바닐라오트밀크라떼추가샷" } },
      { transactionId: "point-legacy", type: "ACCRUAL", amountKrw: 100, occurredAt: "2026-10-02T00:00:00Z", sourceReference: "opaque" },
      { transactionId: "point-adjust", type: "ADJUSTMENT", amountKrw: 300, occurredAt: "2026-10-01T00:00:00Z", sourceReference: "opaque" },
    ], page: {} })),
  ] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/조회된 목록에서 가장 가까운 만료/)).toHaveTextContent("2027. 1. 1.");
    await expect(canvas.getByText(/이후에 만료되는 포인트가 더 있어요/)).toBeVisible();
    const expand = canvas.getByRole("button", { name: "조회된 만료 예정 4건 모두 보기" });
    await expect(expand).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(expand);
    await expect(canvas.getByText(/2027. 4. 1./)).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "만료 예정 접기" }));
    await expect(canvas.queryByText(/2027. 4. 1./)).not.toBeInTheDocument();
    const link = await canvas.findByRole("link", { name: "주문 BF-7K3M-9Q2P 보기" });
    await expect(canvas.getAllByRole("link")).toHaveLength(1);
    await expect(link).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
    link.focus(); await expect(link).toHaveFocus();
  },
};

export const OrderContextUnavailable: Story = {
  parameters: { msw: { handlers: [
    apiError("/api/v1/me/point-transactions", 503, "DEPENDENCY_UNAVAILABLE", "주문 표시 정보를 조회할 수 없습니다."),
    ...pointsHandlers,
  ] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("button", { name: /다시 시도/ })).toBeVisible();
    await expect(canvas.queryByText("아직 포인트 내역이 없어요")).not.toBeInTheDocument();
  },
};
