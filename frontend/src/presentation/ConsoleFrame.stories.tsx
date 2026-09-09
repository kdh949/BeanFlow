import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { PageHeading } from "../design-system";
import { ConsoleFrame } from "./ConsoleFrame";

const meta = {
  title: "Patterns/Navigation/Console frame",
  component: ConsoleFrame,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" }, layout: "fullscreen", routing: { initialEntry: "/store" }, docs: { story: { inline: false, height: "720px" } } },
  args: { kind: "store", access: "authenticated", actorLabel: "시청점 주문·정산 담당 김민수", ownsAnyStore: true, onLogOut: fn().mockResolvedValue(undefined), children: <PageHeading title="주문 보드" /> },
} satisfies Meta<typeof ConsoleFrame>;
export default meta;
type Story = StoryObj<typeof ConsoleFrame>;

export const SignedIn: Story = {
  play: async ({ canvas, args }) => {
    await expect(canvas.getByRole("button", { name: "로그아웃" })).toBeVisible();
    await expect(canvas.getByText("시청점 주문·정산 담당 김민수")).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "로그아웃" }));
    await expect(args.onLogOut).toHaveBeenCalledTimes(1);
  },
};
export const SignInRequired: Story = {
  args: { access: "unauthenticated", actorLabel: "로그인 필요", children: <PageHeading title="매장 로그인" /> },
  play: async ({ canvas }) => {
    await expect(canvas.queryByRole("button", { name: "로그아웃" })).not.toBeInTheDocument();
    await expect(canvas.queryByRole("navigation")).not.toBeInTheDocument();
  },
};
export const InitialPassword: Story = {
  args: { access: "initial-password", children: <PageHeading title="비밀번호 변경" /> },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("button", { name: "로그아웃" })).toBeVisible();
    await expect(canvas.queryByRole("navigation")).not.toBeInTheDocument();
  },
};
export const MembershipUnavailable: Story = {
  args: { ownsAnyStore: false, membershipState: "failed", onRetryMembership: fn() },
  play: async ({ canvas, args }) => {
    const menu = canvas.queryByRole("button", { name: /업무 메뉴/ });
    if (menu && menu.getClientRects().length > 0) await userEvent.click(menu);
    await expect(canvas.getByText("매장 권한을 확인하지 못했습니다")).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "매장 권한 다시 확인" }));
    await expect(args.onRetryMembership).toHaveBeenCalledTimes(1);
  },
};
export const LogoutFailure: Story = {
  args: { onLogOut: async () => { throw new Error("unavailable"); } },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("button", { name: "로그아웃" }));
    await expect(await canvas.findByRole("alert")).toHaveTextContent("로그아웃하지 못했습니다");
    await expect(canvas.getByRole("button", { name: "로그아웃" })).toBeEnabled();
  },
};
export const Operations: Story = { args: { kind: "ops", actorLabel: "operations@example.test", children: <PageHeading title="운영 현황" /> } };
export const Support: Story = { args: { kind: "support", actorLabel: "support@example.test", children: <PageHeading title="고객지원" /> } };

export const PlannedOperations: Story = {
  args: { kind: "ops", children: <PageHeading title="플랫폼 운영" /> },
  play: async ({ canvas }) => {
    const menu = canvas.queryByRole("button", { name: /업무 메뉴/ });
    if (menu && menu.getClientRects().length > 0) await userEvent.click(menu);
    await expect(canvas.getByRole("link", { name: "문제 확인 및 복구 준비 중" })).toHaveAttribute("aria-disabled", "true");
    await expect(canvas.getByRole("link", { name: "쿠폰 캠페인" })).toHaveAttribute("href", "/ops/campaigns");
  },
};
