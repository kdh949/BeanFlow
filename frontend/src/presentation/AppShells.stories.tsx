import type { Meta, StoryObj } from "@storybook/react-vite";
import { HttpResponse, http } from "msw";
import { expect } from "storybook/test";
import { ids, merchantSignedInHandlers } from "../../.storybook/fixtures";
import { merchantSession } from "../features/auth/merchant/merchantSession";
import { ConsoleShell, CustomerShell, NotificationAction, RootRedirect } from "./AppShells";

const meta = {
  title: "Patterns/Navigation/App shells",
  component: RootRedirect,
  subcomponents: { CustomerShell, ConsoleShell, NotificationAction },
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" }, docs: { story: { inline: false, height: "720px" } } },
} satisfies Meta<typeof RootRedirect>;

export default meta;
type Story = StoryObj<typeof meta>;

export const RoleChoice: Story = {};

export const CustomerChrome: Story = {
  render: () => <CustomerShell />,
  parameters: {
    routing: { path: "/app", initialEntry: "/app" },
    msw: { handlers: [http.get("/api/v1/me/notification-summary", () => HttpResponse.json({ hasUnread: false }))] },
  },
  play: async ({ canvas }) => { await expect(await canvas.findByRole("link", { name: "알림함 열기" })).toBeVisible(); },
};

export const CustomerChromeUnread: Story = {
  render: () => <CustomerShell />,
  parameters: {
    routing: { path: "/app", initialEntry: "/app" },
    msw: { handlers: [http.get("/api/v1/me/notification-summary", () => HttpResponse.json({ hasUnread: true }))] },
  },
  play: async ({ canvas }) => { await expect(await canvas.findByRole("link", { name: /읽지 않은 알림 있음/ })).toBeVisible(); },
};

export const NotificationSummaryFailure: Story = {
  render: () => <div className="bfr-shell-state-story"><NotificationAction /></div>,
  parameters: {
    routing: { path: "/app", initialEntry: "/app" },
    msw: { handlers: [http.get("/api/v1/me/notification-summary", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", message: "알림 상태를 확인하지 못했습니다." }, { status: 503 }))] },
  },
  play: async ({ canvas }) => { await expect(await canvas.findByRole("link", { name: "알림 상태를 확인하지 못했습니다. 알림함 열기" })).toBeVisible(); },
};

export const StoreChrome: Story = {
  render: () => <ConsoleShell kind="store" />,
  parameters: {
    layout: "fullscreen",
    routing: { path: "/store/*", initialEntry: "/store/disputes" },
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([
          { storeId: ids.store, storeName: "시청점", membershipRole: "OWNER" },
        ])),
      ],
    },
  },
  beforeEach: async () => {
    merchantSession.reset();
    await merchantSession.refresh();
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("link", { name: "이의제기" })).toHaveAttribute("aria-current", "page");
    await expect(await canvas.findByLabelText("현재 매장 시청점")).toBeVisible();
  },
};

export const OperationsChrome: Story = {
  render: () => <ConsoleShell kind="ops" />,
  parameters: { routing: { path: "/ops", initialEntry: "/ops" } },
};

export const SupportChrome: Story = {
  render: () => <ConsoleShell kind="support" />,
  parameters: { routing: { path: "/support/*", initialEntry: "/support/queue" } },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("link", { name: "대기열" })).toHaveAttribute("aria-current", "page");
    await expect(canvas.getByText("로그인 필요")).toBeVisible();
  },
};
