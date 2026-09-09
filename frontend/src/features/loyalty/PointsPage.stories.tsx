import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, pointsHandlers, signedInHandlers } from "../../../.storybook/fixtures";
import { CustomerPointsPage } from "./PointsPage";

const meta = {
  title: "Pages/Customer/Points",
  component: CustomerPointsPage,
  tags: ["autodocs"],
  parameters: {
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
