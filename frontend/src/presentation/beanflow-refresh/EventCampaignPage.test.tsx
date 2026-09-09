import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { EventCampaignPage } from "./EventCampaignPage";

function response(data: unknown) {
  return { data, response: new Response(null, { status: 200 }) } as never;
}

const event = {
  campaignId: "8a8999bf-3432-4a5d-b599-43bbc3ddc2e1",
  store: { storeId: "5273704d-f924-59e0-8883-827535fb86a1", name: "빈플로우 성수" },
  title: "첫 이벤트",
  summary: "첫 선착순 혜택",
  bannerAltText: "첫 이벤트 배너",
  banner: { url: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg'/%3E", expiresAt: "2026-09-06T12:00:00+09:00" },
  benefit: { discountType: "FIXED_KRW", fixedAmountKrw: 1000, rateBps: null, maximumDiscountKrw: null },
  minimumOrderKrw: 5000,
  remainingCount: 10,
  claimEndsAt: "2026-09-10T23:59:59+09:00",
  couponExpiresAt: "2026-09-30T23:59:59+09:00",
  claimed: false,
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("EventCampaignPage", () => {
  it("appends the next event page from the returned cursor", async () => {
    const second = { ...event, campaignId: "8a8999bf-3432-4a5d-b599-43bbc3ddc2e2", title: "두 번째 이벤트" };
    const get = vi.spyOn(customerApi, "GET").mockImplementation((async (_path: string, options?: { params?: { query?: { cursor?: string } } }) =>
      options?.params?.query?.cursor
        ? response({ items: [second], page: { nextCursor: null } })
        : response({ items: [event], page: { nextCursor: "events-next" } })) as never);

    render(<MemoryRouter><EventCampaignPage /></MemoryRouter>);
    await userEvent.click(await screen.findByRole("button", { name: "이벤트 더 보기" }));

    expect(await screen.findByText("두 번째 이벤트")).toBeVisible();
    expect(screen.getAllByRole("article")).toHaveLength(2);
    const calls = get.mock.calls as unknown as Array<[string, { params?: { query?: { cursor?: string; limit?: number } } }]>;
    expect(calls.some(([, options]) =>
      options?.params?.query?.cursor === "events-next"
      && options?.params?.query?.limit === 20)).toBe(true);
  });
});
