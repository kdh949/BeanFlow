import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { MerchantSidebar, MerchantTopbar, MerchantWorkspaceShell } from "./MerchantWorkspaceShell";

const store = { name: "시청점", reference: "A-142" };
const actor = { displayName: "홍길동", roleLabel: "점장" };

const meta = {
  title: "Patterns/Store/Merchant workspace shell",
  component: MerchantWorkspaceShell,
  subcomponents: { MerchantSidebar, MerchantTopbar },
  tags: ["autodocs"],
  args: { store, actor, hasUnreadNotification: true, onLogout: fn(), canManageOwnerRoutes: true },
  parameters: {
    layout: "fullscreen",
    a11y: { test: "error" },
    docs: {
      description: {
        component: "모든 `/store` 화면이 공유하는 유일한 머천트 chrome입니다. 페이지는 sidebar/topbar를 만들지 않고 content만 제공합니다.",
      },
      story: { inline: false, height: "900px" },
    },
    routing: { path: "/store/*", initialEntry: "/store/disputes" },
  },
  render: (args) => (
    <MerchantWorkspaceShell {...args}>
      <section className="bf-merchant-story-content"><h1>정산 이의제기</h1><p>스토어 화면은 이 콘텐츠 영역만 제공합니다.</p></section>
    </MerchantWorkspaceShell>
  ),
} satisfies Meta<typeof MerchantWorkspaceShell>;

export default meta;
type Story = StoryObj<typeof meta>;

export const DisputeWorkspace: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("navigation", { name: "스토어 메뉴" })).toBeVisible();
    await expect(canvas.getByRole("link", { name: "이의제기" })).toHaveAttribute("aria-current", "page");
    await expect(canvas.queryByRole("link", { name: "주문 내역" })).not.toBeInTheDocument();
    await expect(canvas.getByText("주문 내역").closest("[aria-disabled='true']")).not.toBeNull();
    await expect(canvas.getByLabelText("읽지 않은 알림 있음")).toBeVisible();
  },
};

export const OrderBoardWorkspace: Story = {
  parameters: { routing: { path: "/store/*", initialEntry: "/store" } },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("link", { name: "주문 보드" })).toHaveAttribute("aria-current", "page");
  },
};

export const SidebarReference: Story = {
  render: () => <MerchantSidebar collapsed={false} canManageOwnerRoutes onToggle={fn()} />,
};

export const TopbarReference: Story = {
  render: () => <MerchantTopbar store={store} actor={actor} hasUnreadNotification onLogout={fn()} onStoreMenuClick={fn()} />,
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("button", { name: "시청점 매장 선택" })).toBeVisible();
    await expect(canvas.getByText("A-142")).toBeVisible();
  },
};

export const CollapsedNavigation: Story = {
  play: async ({ canvas }) => {
    const toggle = canvas.getByRole("button", { name: "메뉴 접기" });
    await userEvent.click(toggle);
    await expect(canvas.getByRole("button", { name: "메뉴 펼치기" })).toHaveAttribute("aria-expanded", "false");
    await expect(canvas.getByRole("link", { name: "이의제기" })).toBeVisible();
  },
};

export const AccountMenuAndLogoutFailure: Story = {
  args: { logoutFailed: true },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("button", { name: /홍길동/ }));
    await expect(canvas.getByRole("button", { name: "로그아웃" })).toBeVisible();
    await expect(canvas.getByRole("alert")).toHaveTextContent("로그아웃에 실패했습니다");
  },
};
