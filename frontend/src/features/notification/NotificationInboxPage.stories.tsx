import type { Meta, StoryObj } from "@storybook/react-vite";
import { HttpResponse, delay, http } from "msw";
import { expect, userEvent, waitFor } from "storybook/test";
import type { components } from "../../api/schema";
import { NotificationInboxPage } from "./NotificationInboxPage";

type NotificationItem = components["schemas"]["NotificationItem"];

const unreadOrderTitle = "주문이 접수됐어요";
const unreadOrder: NotificationItem = {
  notificationId: "71000000-0000-4000-8000-000000000001",
  title: unreadOrderTitle,
  body: "시청점에서 주문을 확인했습니다. 준비가 끝나면 다시 알려드릴게요.",
  createdAt: "2026-08-26T02:10:00Z",
  classification: "TRANSACTIONAL",
  target: { type: "ORDER", reference: "BF-7K3M-9Q2P" },
};

const marketingBenefitTitle = "감사 포인트가 도착했어요";
const marketingBenefit: NotificationItem = {
  notificationId: "71000000-0000-4000-8000-000000000002",
  title: marketingBenefitTitle,
  body: "불편을 기다려 주셔서 감사한 마음을 포인트로 전해드렸습니다.",
  createdAt: "2026-08-25T06:30:00Z",
  readAt: "2026-08-25T07:00:00Z",
  classification: "MARKETING",
  target: { type: "NONE" },
};

function notificationHandlers(initialItems: NotificationItem[], initialMarketingOptIn = false) {
  let items = initialItems;
  let marketingOptIn = initialMarketingOptIn;
  return [
    http.get("/api/v1/me/notification-summary", () => HttpResponse.json({ hasUnread: items.some((item) => !item.readAt) })),
    http.get("/api/v1/me/notifications", () => HttpResponse.json({ items, page: {} })),
    http.get("/api/v1/me/notification-preferences", () => HttpResponse.json({ marketingOptIn })),
    http.patch("/api/v1/me/notifications/:notificationId", ({ params }) => {
      items = items.map((item) => item.notificationId === params["notificationId"]
        ? { ...item, readAt: "2026-08-26T02:15:00Z" }
        : item);
      return new HttpResponse(null, { status: 204 });
    }),
    http.put("/api/v1/me/notification-preferences", async ({ request }) => {
      const body = await request.json() as { marketingOptIn: boolean };
      marketingOptIn = body.marketingOptIn;
      return HttpResponse.json({ marketingOptIn });
    }),
  ];
}

const meta = {
  title: "Pages/Customer/Notifications",
  component: NotificationInboxPage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: {
        component:
          "Delivery 진단을 노출하지 않고 고객용 copy, 읽음 상태, 마케팅 opt-in만 다루는 고객 알림함입니다.",
      },
      story: { inline: false, height: "760px" },
    },
    routing: { surface: "customer", path: "/app/notifications", initialEntry: "/app/notifications" },
  },
} satisfies Meta<typeof NotificationInboxPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const UnreadAndPreference: Story = {
  parameters: { msw: { handlers: notificationHandlers([unreadOrder, marketingBenefit]) } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("주문이 접수됐어요")).toBeVisible();
    await expect(canvas.getByRole("link", { name: "주문 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");

    await userEvent.click(canvas.getByRole("button", { name: "읽음으로 표시" }));
    await expect(await canvas.findByRole("link", { name: "알림함 열기" })).toBeVisible();
    await expect(canvas.queryByRole("button", { name: "읽음으로 표시" })).not.toBeInTheDocument();

    const preference = canvas.getByRole("checkbox", { name: /마케팅 알림 받기/ });
    await expect(preference).not.toBeChecked();
    await userEvent.click(preference);
    await waitFor(() => expect(preference).toBeChecked());
  },
};

export const Empty: Story = {
  parameters: { msw: { handlers: notificationHandlers([]) } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("아직 받은 알림이 없어요")).toBeVisible();
  },
};

export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get("/api/v1/me/notification-summary", async () => {
          await delay("infinite");
          return HttpResponse.json({});
        }),
        http.get("/api/v1/me/notifications", async () => {
          await delay("infinite");
          return HttpResponse.json({});
        }),
        http.get("/api/v1/me/notification-preferences", async () => {
          await delay("infinite");
          return HttpResponse.json({});
        }),
      ],
    },
  },
};

export const DependencyUnavailable: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get("/api/v1/me/notification-summary", () => HttpResponse.json({
          code: "DEPENDENCY_UNAVAILABLE", message: "알림 저장소에 연결할 수 없습니다.", correlationId: "REQ-DEMO-42",
        }, { status: 503 })),
        http.get("/api/v1/me/notifications", () => HttpResponse.json({
          code: "DEPENDENCY_UNAVAILABLE", message: "알림 저장소에 연결할 수 없습니다.", correlationId: "REQ-DEMO-42",
        }, { status: 503 })),
        http.get("/api/v1/me/notification-preferences", () => HttpResponse.json({
          code: "DEPENDENCY_UNAVAILABLE", message: "알림 설정 저장소에 연결할 수 없습니다.", correlationId: "REQ-DEMO-43",
        }, { status: 503 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("link", { name: /알림 상태를 확인하지 못했습니다/ })).toBeVisible();
    await expect(await canvas.findByText("마케팅 알림 수신 설정을 확인하거나 저장하지 못했습니다. 현재 설정을 임의로 바꾸지 않았습니다.")).toBeVisible();
    await expect(canvas.queryByText("아직 받은 알림이 없어요")).not.toBeInTheDocument();
  },
};
