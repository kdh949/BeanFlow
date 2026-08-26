import type { Meta, StoryObj } from "@storybook/react-vite";
import { HttpResponse, http } from "msw";
import { expect } from "storybook/test";
import { ConsoleShell, CustomerShell, RootRedirect } from "./Shells";

const meta = {
  title: "Pages/Shared/RoleChoice",
  component: RootRedirect,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: { component: "BeanFlow의 고객·매장·운영 작업 공간 진입점을 선택하는 root page입니다." },
      story: { inline: false, height: "720px" },
    },
  },
} satisfies Meta<typeof RootRedirect>;

export default meta;
type Story = StoryObj<typeof meta>;

export const RoleChoice: Story = {};

export const CustomerChrome: Story = {
  render: () => <CustomerShell />,
  parameters: {
    routing: { path: "/app", initialEntry: "/app" },
    msw: { handlers: [http.get("/api/v1/me/notification-summary", () => HttpResponse.json({ hasUnread: false }))] },
    docs: { description: { story: "고객 app의 header, auth 상태와 bottom navigation chrome입니다." } },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("link", { name: "알림함 열기" })).toBeVisible();
  },
};

export const CustomerChromeUnread: Story = {
  render: () => <CustomerShell />,
  parameters: {
    routing: { path: "/app", initialEntry: "/app" },
    msw: { handlers: [http.get("/api/v1/me/notification-summary", () => HttpResponse.json({ hasUnread: true }))] },
    docs: { description: { story: "서버가 unread 존재를 확인한 경우에만 bell indicator를 표시합니다." } },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("link", { name: /읽지 않은 알림 있음/ })).toBeVisible();
  },
};

export const CustomerChromeNotificationUnavailable: Story = {
  render: () => <CustomerShell />,
  parameters: {
    routing: { path: "/app", initialEntry: "/app" },
    msw: {
      handlers: [
        http.get("/api/v1/me/notification-summary", () => HttpResponse.json({
          code: "DEPENDENCY_UNAVAILABLE", message: "알림 저장소에 연결할 수 없습니다.",
        }, { status: 503 })),
      ],
    },
    docs: { description: { story: "summary 실패를 unread 없음으로 위장하지 않고 별도 실패 indicator로 표시합니다." } },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("link", { name: /알림 상태를 확인하지 못했습니다/ })).toBeVisible();
  },
};

export const StoreChrome: Story = {
  render: () => <ConsoleShell kind="store" />,
  parameters: {
    routing: { path: "/store", initialEntry: "/store" },
    docs: { description: { story: "매장 작업 공간의 navigation과 credential 상태 chrome입니다." } },
  },
};

export const OperationsChrome: Story = {
  render: () => <ConsoleShell kind="ops" />,
  parameters: {
    routing: { path: "/ops", initialEntry: "/ops" },
    docs: { description: { story: "운영 작업 공간의 navigation과 credential 상태 chrome입니다." } },
  },
};
