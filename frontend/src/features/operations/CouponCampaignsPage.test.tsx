import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { operationsApi } from "../../api/consoleClient";
import { CouponCampaignsPage } from "./CouponCampaignsPage";

function response(data: unknown, status = 200) {
  return { data, response: new Response(null, { status }) } as never;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("CouponCampaignsPage", () => {
  it("creates a complete draft with a fixed coupon expiry", async () => {
    vi.spyOn(operationsApi, "GET").mockImplementation((async (path: string) => {
      if (path.endsWith("/store-options")) return response({ items: [{ storeId: "5273704d-f924-59e0-8883-827535fb86ad", name: "빈플로우 성수" }], page: { nextCursor: null } });
      if (path.endsWith("/menus")) return response([]);
      return response({ items: [], page: { nextCursor: null } });
    }) as never);
    const post = vi.spyOn(operationsApi, "POST").mockResolvedValue(response({
      campaignId: "8a8999bf-3432-4a5d-b599-43bbc3ddc2e9",
      store: { storeId: "5273704d-f924-59e0-8883-827535fb86ad", name: "빈플로우 성수" },
      state: "DRAFT", title: "가을 라떼 쿠폰", summary: "선착순 100명 할인", bannerAltText: "가을 라떼 배너",
      banner: null,
      discount: { discountType: "FIXED_KRW", fixedAmountKrw: 1000, rateBps: null, maximumDiscountKrw: null },
      minimumOrderKrw: 5000, allMenusEligible: true, eligibleMenuIds: [], cost: { costBearer: "PLATFORM", platformShareBps: 10000, storeShareBps: 0 },
      totalQuota: 100, issuedCount: 0, claimStartsAt: "2026-10-01T00:00:00+09:00", claimEndsAt: "2026-10-10T23:59:59+09:00", couponExpiresAt: "2026-10-31T23:59:59+09:00",
      createdAt: "2026-09-02T20:00:00+09:00", updatedAt: "2026-09-02T20:00:00+09:00", version: 0,
    }, 201));

    render(<MemoryRouter><CouponCampaignsPage /></MemoryRouter>);
    await userEvent.click(await screen.findByRole("button", { name: "첫 캠페인 만들기" }));
    await userEvent.selectOptions(screen.getByLabelText("매장"), "5273704d-f924-59e0-8883-827535fb86ad");
    await userEvent.type(screen.getByLabelText("캠페인 제목"), "가을 라떼 쿠폰");
    await userEvent.type(screen.getByLabelText("한 줄 혜택 설명"), "선착순 100명 할인");
    await userEvent.type(screen.getByLabelText("배너 대체 텍스트"), "가을 라떼 배너");
    await userEvent.type(screen.getByLabelText("다운로드 시작 시각"), "2026-10-01T00:00");
    await userEvent.type(screen.getByLabelText("다운로드 종료 시각"), "2026-10-10T23:59");
    await userEvent.type(screen.getByLabelText("쿠폰 만료 시각"), "2026-10-31T23:59");
    await userEvent.type(screen.getByLabelText("초안 생성 사유"), "가을 프로모션 승인");
    await userEvent.click(screen.getByRole("button", { name: "초안 생성" }));

    expect(await screen.findByText("가을 라떼 쿠폰")).toBeVisible();
    const [, options] = post.mock.calls[0] as unknown as [string, { body: Record<string, unknown>; params: { header: Record<string, string> } }];
    expect(options.body).toMatchObject({
      totalQuota: 100,
      claimStartsAt: "2026-10-01T00:00:00+09:00",
      claimEndsAt: "2026-10-10T23:59:00+09:00",
      couponExpiresAt: "2026-10-31T23:59:00+09:00",
    });
    expect(options.params.header["Idempotency-Key"]).toBeTruthy();
  });

  it("loads every campaign and store option page from the returned cursors", async () => {
    const campaign = {
      campaignId: "8a8999bf-3432-4a5d-b599-43bbc3ddc2e9",
      store: { storeId: "5273704d-f924-59e0-8883-827535fb86ad", name: "빈플로우 성수" },
      state: "DRAFT", title: "첫 캠페인", summary: "첫 혜택", bannerAltText: "첫 배너", banner: null,
      discount: { discountType: "FIXED_KRW", fixedAmountKrw: 1000, rateBps: null, maximumDiscountKrw: null },
      minimumOrderKrw: 5000, allMenusEligible: true, eligibleMenuIds: [], cost: { costBearer: "PLATFORM", platformShareBps: 10000, storeShareBps: 0 },
      totalQuota: 100, issuedCount: 0, claimStartsAt: "2026-10-01T00:00:00+09:00", claimEndsAt: "2026-10-10T23:59:59+09:00", couponExpiresAt: "2026-10-31T23:59:59+09:00",
      createdAt: "2026-09-02T20:00:00+09:00", updatedAt: "2026-09-02T20:00:00+09:00", version: 0,
    };
    const secondCampaign = { ...campaign, campaignId: "8a8999bf-3432-4a5d-b599-43bbc3ddc2f0", title: "두 번째 캠페인" };
    const get = vi.spyOn(operationsApi, "GET").mockImplementation((async (path: string, options?: { params?: { query?: { cursor?: string } } }) => {
      const cursor = options?.params?.query?.cursor;
      if (path.endsWith("/store-options")) {
        return cursor
          ? response({ items: [{ storeId: "5273704d-f924-59e0-8883-827535fb86ae", name: "빈플로우 잠실" }], page: { nextCursor: null } })
          : response({ items: [campaign.store], page: { nextCursor: "stores-next" } });
      }
      return cursor
        ? response({ items: [secondCampaign], page: { nextCursor: null } })
        : response({ items: [campaign], page: { nextCursor: "campaigns-next" } });
    }) as never);

    render(<MemoryRouter><CouponCampaignsPage /></MemoryRouter>);
    await userEvent.click(await screen.findByRole("button", { name: "캠페인 더 보기" }));
    expect(await screen.findByText("두 번째 캠페인")).toBeVisible();
    await userEvent.click(screen.getByRole("button", { name: "새 캠페인" }));
    await userEvent.click(await screen.findByRole("button", { name: "매장 더 보기" }));
    expect(await screen.findByRole("option", { name: "빈플로우 잠실" })).toBeInTheDocument();
    const calls = get.mock.calls as unknown as Array<[string, { params?: { query?: { cursor?: string } } }]>;
    expect(calls.some(([, options]) => options?.params?.query?.cursor === "campaigns-next")).toBe(true);
    expect(calls.some(([, options]) => options?.params?.query?.cursor === "stores-next")).toBe(true);
  });

  it("ignores a late menu response from a previously selected store", async () => {
    const storeA = "5273704d-f924-59e0-8883-827535fb86ad";
    const storeB = "5273704d-f924-59e0-8883-827535fb86ae";
    let resolveA: ((value: never) => void) | undefined;
    let resolveB: ((value: never) => void) | undefined;
    vi.spyOn(operationsApi, "GET").mockImplementation((async (path: string, options?: { params?: { path?: { storeId?: string } } }) => {
      if (path.endsWith("/store-options")) {
        return response({ items: [{ storeId: storeA, name: "빈플로우 성수" }, { storeId: storeB, name: "빈플로우 잠실" }], page: { nextCursor: null } });
      }
      if (path.endsWith("/menus")) {
        return new Promise((resolve) => {
          if (options?.params?.path?.storeId === storeA) resolveA = resolve;
          else resolveB = resolve;
        });
      }
      return response({ items: [], page: { nextCursor: null } });
    }) as never);

    render(<MemoryRouter><CouponCampaignsPage /></MemoryRouter>);
    await userEvent.click(await screen.findByRole("button", { name: "첫 캠페인 만들기" }));
    await userEvent.click(screen.getByRole("checkbox", { name: /모든 메뉴에 적용/ }));
    await userEvent.selectOptions(screen.getByLabelText("매장"), storeA);
    await userEvent.selectOptions(screen.getByLabelText("매장"), storeB);
    resolveB?.(response([{ menuId: "7419fd51-d17d-43c0-bc16-0d9496c90d98", name: "잠실 라떼", basePriceKrw: 5800 }]));
    expect(await screen.findByText("잠실 라떼")).toBeVisible();
    resolveA?.(response([{ menuId: "7419fd51-d17d-43c0-bc16-0d9496c90d97", name: "성수 라떼", basePriceKrw: 5500 }]));

    expect(await screen.findByText("잠실 라떼")).toBeVisible();
    expect(screen.queryByText("성수 라떼")).not.toBeInTheDocument();
  });
});
