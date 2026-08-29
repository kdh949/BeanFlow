import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { OperationsSidebar, OperationsTopbar, OperationsWorkspaceShell } from "./OperationsWorkspaceShell";

const actor = { displayName: "김사장님", roleLabel: "운영자" };

const meta = {
  title: "Patterns/Operations/Operations workspace shell",
  component: OperationsWorkspaceShell,
  subcomponents: { OperationsSidebar, OperationsTopbar },
  tags: ["autodocs"],
  args: { actor, hasUnreadNotification: true, onLogout: fn() },
  parameters: {
    layout: "fullscreen",
    a11y: { test: "error" },
    docs: {
      description: {
        component: "모든 `/ops` 화면이 공유하는 유일한 운영 chrome입니다. Store와 Support shell에서 geometry token만 공유하고 navigation과 actor 의미는 독립적으로 소유합니다.",
      },
      story: { inline: false, height: "900px" },
    },
    routing: { path: "/ops/*", initialEntry: "/ops" },
  },
  render: (args) => (
    <OperationsWorkspaceShell {...args}>
      <section className="bf-operations-story-content"><h1>운영 대시보드</h1><p>운영 화면은 이 콘텐츠 영역만 제공합니다.</p></section>
    </OperationsWorkspaceShell>
  ),
} satisfies Meta<typeof OperationsWorkspaceShell>;

export default meta;
type Story = StoryObj<typeof meta>;

export const DashboardWorkspace: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("navigation", { name: "운영 메뉴" })).toBeVisible();
    await expect(canvas.getByRole("link", { name: "운영 대시보드" })).toHaveAttribute("aria-current", "page");
    await expect(canvas.getByLabelText("읽지 않은 알림 있음")).toBeVisible();
  },
};

export const SidebarReference: Story = { render: () => <OperationsSidebar /> };

export const TopbarReference: Story = {
  render: () => <OperationsTopbar actor={actor} hasUnreadNotification onLogout={fn()} />,
};

export const AccountMenuAndLogoutFailure: Story = {
  args: { logoutFailed: true },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("button", { name: /김사장님/ }));
    await expect(canvas.getByRole("button", { name: "로그아웃" })).toBeVisible();
    await expect(canvas.getByRole("alert")).toHaveTextContent("로그아웃에 실패했습니다");
  },
};
