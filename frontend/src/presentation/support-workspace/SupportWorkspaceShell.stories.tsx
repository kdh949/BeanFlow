import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { SupportSidebar, SupportTopbar, SupportWorkspaceShell } from "./SupportWorkspaceShell";

const actor = { displayName: "김사랑님", teamLabel: "운영팀 · 상담" };

const meta = {
  title: "Patterns/Support/Support workspace shell",
  component: SupportWorkspaceShell,
  subcomponents: { SupportSidebar, SupportTopbar },
  tags: ["autodocs"],
  args: { actor, hasUnreadNotification: true, onLogout: fn() },
  parameters: {
    layout: "fullscreen",
    a11y: { test: "error" },
    docs: {
      description: {
        component: "모든 `/support` 화면이 공유하는 유일한 고객지원 chrome입니다. Store chrome과 geometry token만 공유하고 메뉴와 actor 의미는 독립적으로 소유합니다.",
      },
      story: { inline: false, height: "990px" },
    },
    routing: { path: "/support/*", initialEntry: "/support/queue" },
  },
  render: (args) => (
    <SupportWorkspaceShell {...args}>
      <section className="bf-support-story-content"><h1>고객지원 대기열</h1><p>고객지원 화면은 이 콘텐츠 영역만 제공합니다.</p></section>
    </SupportWorkspaceShell>
  ),
} satisfies Meta<typeof SupportWorkspaceShell>;

export default meta;
type Story = StoryObj<typeof meta>;

export const QueueWorkspace: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("navigation", { name: "고객지원 메뉴" })).toBeVisible();
    await expect(canvas.getByRole("link", { name: "대기열" })).toHaveAttribute("aria-current", "page");
    await expect(canvas.getByRole("link", { name: "문의 접수" })).toHaveAttribute("href", "/support/cases/new");
    await expect(canvas.getByLabelText("읽지 않은 고객지원 알림 있음")).toBeVisible();
  },
};

export const SidebarReference: Story = {
  render: () => <div className="bf-support-story-sidebar-frame"><SupportSidebar actor={actor} onLogout={fn()} /></div>,
};

export const TopbarReference: Story = {
  render: () => <SupportTopbar contextLabel="고객지원" hasUnreadNotification />,
  play: async ({ canvas }) => {
    await expect(canvas.getByText("고객지원")).toBeVisible();
    await expect(canvas.getByLabelText("읽지 않은 고객지원 알림 있음")).toBeVisible();
  },
};

export const AccountMenuAndLogoutFailure: Story = {
  args: { logoutFailed: true },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("button", { name: /김사랑님/ }));
    await expect(canvas.getByRole("button", { name: "로그아웃" })).toBeVisible();
    await expect(canvas.getByRole("alert")).toHaveTextContent("로그아웃에 실패했습니다");
  },
};
