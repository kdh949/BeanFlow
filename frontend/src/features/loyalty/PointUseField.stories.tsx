import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { pointsHandlers } from "../../../.storybook/fixtures";
import { PointUseField, usePointUse } from "./PointUseField";

const meta = {
  title: "Patterns/Customer/Point use",
  component: PointUseField,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" }, msw: { handlers: pointsHandlers }, docs: { story: { inline: false, height: "460px" } } },
  render: function PointUseStory() { const selection = usePointUse(); return <PointUseField selection={selection} maximum={900} />; },
} satisfies Meta<typeof PointUseField>;
export default meta;
type Story = StoryObj<Omit<Parameters<typeof PointUseField>[0], "selection">>;
export const CouponAdjustedLimit: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("1,500P")).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "전액 사용" }));
    await expect(canvas.getByRole("textbox", { name: "사용할 포인트" })).toHaveValue("900");
    await userEvent.click(canvas.getByRole("button", { name: "사용 안 함" }));
    await userEvent.type(canvas.getByRole("textbox", { name: "사용할 포인트" }), "1.5");
    await expect(canvas.getByText("포인트는 0 이상의 정수로 입력해 주세요.")).toBeVisible();
  },
};
export const ZeroBalance: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/me/points", () => HttpResponse.json({ availablePointsKrw: 0, recoveryPendingKrw: 0, currency: "KRW", expiring: [], expiringHasMore: false }))] } },
  play: async ({ canvas }) => { await expect(await canvas.findByText("0P")).toBeVisible(); await expect(canvas.getByRole("button", { name: "전액 사용" })).toBeDisabled(); },
};
