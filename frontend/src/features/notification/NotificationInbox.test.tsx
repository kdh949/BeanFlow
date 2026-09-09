import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { NotificationBell } from "./NotificationBell";
import { NotificationInboxPage } from "./NotificationInboxPage";

const unread = {
  notificationId: "71000000-0000-4000-8000-000000000001",
  title: "주문이 접수됐어요",
  body: "시청점에서 주문을 확인했습니다.",
  createdAt: "2026-08-26T02:10:00Z",
  classification: "TRANSACTIONAL" as const,
  target: { type: "ORDER" as const, reference: "BF-7K3M-9Q2P" },
};

function ok<T>(data: T) {
  return { data, response: new Response(null, { status: 200 }) };
}

function failure(message: string) {
  return {
    error: { code: "DEPENDENCY_UNAVAILABLE", message, correlationId: "REQ-TEST-42" },
    response: new Response(null, { status: 503 }),
  };
}

function renderInbox(withBell = false) {
  return render(
    <MemoryRouter initialEntries={["/app/notifications"]}>
      {withBell ? <NotificationBell /> : null}
      <NotificationInboxPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  document.cookie = "BEANFLOW_CUSTOMER_XSRF=customer-csrf-token; path=/";
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  document.cookie = "BEANFLOW_CUSTOMER_XSRF=; Max-Age=0; path=/";
});

describe("customer notification inbox", () => {
  it("marks one item read and re-reads the server summary without exposing delivery diagnostics", async () => {
    let hasUnread = true;
    const get = vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
      if (path === "/me/notification-summary") return ok({ hasUnread }) as never;
      if (path === "/me/notifications") return ok({ items: [unread], page: {} }) as never;
      if (path === "/me/notification-preferences") return ok({ marketingOptIn: false }) as never;
      throw new Error(`unexpected GET ${path}`);
    });
    const patch = vi.spyOn(customerApi, "PATCH").mockImplementation(async () => {
      hasUnread = false;
      return { response: new Response(null, { status: 204 }) } as never;
    });

    renderInbox(true);

    expect(await screen.findByRole("link", { name: /읽지 않은 알림 있음/ })).toBeInTheDocument();
    expect(await screen.findByText("주문이 접수됐어요")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "주문 보기" })).toHaveAttribute("href", "/app/orders/BF-7K3M-9Q2P");
    expect(screen.queryByText(/Delivery|provider|재시도/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "읽음으로 표시" }));

    await waitFor(() => expect(patch).toHaveBeenCalledWith("/me/notifications/{notificationId}", {
      params: {
        path: { notificationId: unread.notificationId },
        header: { "X-BEANFLOW-CSRF": "customer-csrf-token" },
      },
      body: { read: true },
    }));
    expect(await screen.findByRole("link", { name: "알림함 열기" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "읽음으로 표시" })).not.toBeInTheDocument();
    const getCalls = get.mock.calls as unknown as Array<[string]>;
    expect(getCalls.filter(([path]) => path === "/me/notification-summary")).toHaveLength(2);
  });

  it("keeps marketing opt-out as the default and sends an exact CSRF-protected replacement", async () => {
    vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
      if (path === "/me/notifications") return ok({ items: [], page: {} }) as never;
      if (path === "/me/notification-preferences") return ok({ marketingOptIn: false }) as never;
      throw new Error(`unexpected GET ${path}`);
    });
    const put = vi.spyOn(customerApi, "PUT").mockResolvedValue(ok({ marketingOptIn: true }) as never);

    renderInbox();
    const preference = await screen.findByRole("switch", { name: /마케팅 알림 받기/ });
    expect(preference).not.toBeChecked();

    await userEvent.click(preference);

    await waitFor(() => expect(put).toHaveBeenCalledWith("/me/notification-preferences", {
      params: { header: { "X-BEANFLOW-CSRF": "customer-csrf-token" } },
      body: { marketingOptIn: true },
    }));
    await waitFor(() => expect(preference).toBeChecked());
  });

  it("does not render a dependency failure as an empty inbox or an all-read summary", async () => {
    vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => failure(`failed ${path}`) as never);

    renderInbox(true);

    expect(await screen.findByRole("link", { name: /알림 상태를 확인하지 못했습니다/ })).toBeInTheDocument();
    expect(await screen.findByText("알림 설정을 확인하지 못했어요. 다시 불러와 현재 설정을 확인해 주세요.")).toBeInTheDocument();
    expect(screen.queryByText("아직 받은 알림이 없어요")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "알림함 열기" })).not.toBeInTheDocument();
  });

  it("uses the opaque next cursor unchanged when appending notifications", async () => {
    const older = { ...unread, notificationId: "71000000-0000-4000-8000-000000000002", title: "준비가 끝났어요" };
    const get = vi.spyOn(customerApi, "GET").mockImplementation(async (path: string, options?: {
      params?: { query?: { cursor?: string } };
    }) => {
      if (path === "/me/notification-preferences") return ok({ marketingOptIn: false }) as never;
      if (path === "/me/notifications" && options?.params?.query?.cursor === "signed-cursor") {
        return ok({ items: [older], page: {} }) as never;
      }
      if (path === "/me/notifications") return ok({ items: [unread], page: { nextCursor: "signed-cursor" } }) as never;
      throw new Error(`unexpected GET ${path}`);
    });

    renderInbox();
    await userEvent.click(await screen.findByRole("button", { name: "알림 더 보기" }));

    expect(await screen.findByText("준비가 끝났어요")).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith("/me/notifications", {
      params: { query: { cursor: "signed-cursor", limit: 20 } },
    });
  });
});
