import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { customerSession } from "../auth/customer/customerSession";
import { CustomerHomePage } from "./HomePage";
import { StoreSearchPage } from "./StoreSearchPage";

const actor = { actorType: "CUSTOMER" as const, customerId: "customer-id", displayName: "김도현" };

function ok<T>(data: T) {
  return { data, response: new Response(null, { status: 200 }) };
}

function failed(status: number, code: string, message: string) {
  return { error: { code, message }, response: new Response(null, { status }) };
}

type Route200 = Record<string, unknown>;

function routeGet(routes: Record<string, Route200 | (() => Route200)>) {
  return vi.spyOn(customerApi, "GET").mockImplementation(async (path: string) => {
    const answer = routes[path];
    if (!answer) throw new Error(`unexpected GET ${path}`);
    return (typeof answer === "function" ? answer() : answer) as never;
  });
}

function renderHome() {
  return render(
    <MemoryRouter initialEntries={["/app"]}>
      <Routes>
        <Route path="/app" element={<CustomerHomePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

function renderSearch(path = "/app/stores") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/app/stores" element={<StoreSearchPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  customerSession.reset();
  vi.spyOn(customerApi, "GET").mockResolvedValue(ok(actor) as never);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("customer home", () => {
  it("shows the active order and its recommendations", async () => {
    routeGet({
      "/me/orders": ok({
        items: [{
          orderReference: "BF-2345-6789",
          pickupNumber: "A12",
          storeName: "성수 로스터리",
          status: "PAID",
          orderedAt: "2026-08-16T00:00:00Z",
          pickupWindowStart: "2026-08-16T01:00:00Z",
          pickupWindowEnd: "2026-08-16T01:10:00Z",
          totalAmountKrw: 4500,
          currency: "KRW",
          itemSummary: "아메리카노 1잔",
          allowedActions: ["CANCEL"],
        }],
        page: {},
      }),
      "/me/store-recommendations": ok({
        items: [{ store: { storeId: "store-1", name: "성수 로스터리", pickupAvailable: true }, reason: "FAVORITE" }],
      }),
    });

    renderHome();

    expect(await screen.findByText("A12")).toBeInTheDocument();
    expect(await screen.findByText("자주 가는 매장")).toBeInTheDocument();
  });

  it("keeps an order list failure visible instead of showing an empty state", async () => {
    routeGet({
      "/me/orders": failed(503, "DEPENDENCY_UNAVAILABLE", "주문을 조회하지 못했습니다."),
      "/me/store-recommendations": ok({ items: [] }),
    });

    renderHome();

    expect(await screen.findByText("주문을 조회하지 못했습니다.")).toBeInTheDocument();
    expect(screen.queryByText("진행 중인 주문이 없어요")).not.toBeInTheDocument();
  });

  it("distinguishes an empty recommendation list from a failure", async () => {
    routeGet({
      "/me/orders": ok({ items: [], page: {} }),
      "/me/store-recommendations": ok({ items: [] }),
    });

    renderHome();

    expect(await screen.findByText("진행 중인 주문이 없어요")).toBeInTheDocument();
    expect(await screen.findByText("추천할 매장이 아직 없어요")).toBeInTheDocument();
  });
});

describe("store search", () => {
  it("asks for a query before calling the server", async () => {
    const get = routeGet({});

    renderSearch();

    expect(await screen.findByText("찾고 싶은 매장을 알려주세요")).toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });

  it("lists matches with the matched menu names", async () => {
    routeGet({
      "/stores/search": ok({
        items: [{
          storeId: "store-1",
          name: "성수 로스터리",
          matchReason: ["MENU_NAME"],
          open: true,
          pickupAvailable: true,
          matchedMenus: [{ menuId: "menu-1", name: "오트 라떼" }],
        }],
        page: {},
        distanceAvailable: false,
      }),
    });

    renderSearch("/app/stores?query=라떼");

    expect(await screen.findByText("성수 로스터리")).toBeInTheDocument();
    expect(screen.getByText("오트 라떼")).toBeInTheDocument();
  });

  it("shows an empty result without claiming a failure", async () => {
    routeGet({ "/stores/search": ok({ items: [], page: {}, distanceAvailable: false }) });

    renderSearch("/app/stores?query=라떼");

    expect(await screen.findByText("'라떼' 검색 결과가 없어요")).toBeInTheDocument();
  });

  it("keeps a search failure visible", async () => {
    routeGet({ "/stores/search": failed(503, "DEPENDENCY_UNAVAILABLE", "검색을 사용할 수 없습니다.") });

    renderSearch("/app/stores?query=라떼");

    expect(await screen.findByText("검색을 사용할 수 없습니다.")).toBeInTheDocument();
  });

  it("explains a denied location permission and still allows search", async () => {
    routeGet({});
    const getCurrentPosition = vi.fn((_success, failure) => failure({ code: 1, PERMISSION_DENIED: 1 }));
    Object.defineProperty(navigator, "geolocation", { configurable: true, value: { getCurrentPosition } });

    renderSearch();
    await userEvent.click(screen.getByRole("button", { name: "현재 위치로 찾기" }));

    expect(await screen.findByText(/위치 권한이 꺼져 있어/)).toBeInTheDocument();
    expect(screen.getByLabelText("검색어")).toBeEnabled();
  });

  it("loads the next page with the server's nextCursor instead of stopping at the first 20", async () => {
    const get = vi.spyOn(customerApi, "GET").mockImplementation(async (path: string, options?: { params?: { query?: { cursor?: string } } }) => {
      if (path !== "/stores/search") throw new Error(`unexpected GET ${path}`);
      const cursor = options?.params?.query?.cursor;
      if (cursor === undefined) {
        return ok({
          items: [{ storeId: "store-1", name: "성수 로스터리", matchReason: ["MENU_NAME"], open: true, pickupAvailable: true, matchedMenus: [] }],
          page: { nextCursor: "cursor-1" },
          distanceAvailable: false,
        }) as never;
      }
      if (cursor === "cursor-1") {
        return ok({
          items: [{ storeId: "store-2", name: "합정 로스터리", matchReason: ["MENU_NAME"], open: true, pickupAvailable: true, matchedMenus: [] }],
          page: {},
          distanceAvailable: false,
        }) as never;
      }
      throw new Error(`unexpected cursor ${cursor}`);
    });

    renderSearch("/app/stores?query=라떼");

    expect(await screen.findByText("성수 로스터리")).toBeInTheDocument();
    expect(screen.queryByText("합정 로스터리")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "매장 더 보기" }));

    expect(await screen.findByText("합정 로스터리")).toBeInTheDocument();
    expect(screen.getByText("성수 로스터리")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "매장 더 보기" })).not.toBeInTheDocument();
    expect(get).toHaveBeenCalledWith("/stores/search", expect.objectContaining({
      params: expect.objectContaining({ query: expect.objectContaining({ cursor: "cursor-1" }) }),
    }));
  });

  it("loads the next page of nearby stores with the server's nextCursor", async () => {
    const getCurrentPosition = vi.fn((success) => success({ coords: { latitude: 37.5, longitude: 127 } }));
    Object.defineProperty(navigator, "geolocation", { configurable: true, value: { getCurrentPosition } });
    const get = vi.spyOn(customerApi, "GET").mockImplementation(async (path: string, options?: { params?: { query?: { cursor?: string } } }) => {
      if (path !== "/stores/nearby") throw new Error(`unexpected GET ${path}`);
      const cursor = options?.params?.query?.cursor;
      if (cursor === undefined) {
        return ok({
          items: [{ storeId: "store-1", name: "성수 로스터리", distanceMeters: 100, open: true, pickupAvailable: true }],
          page: { nextCursor: "cursor-1" },
        }) as never;
      }
      if (cursor === "cursor-1") {
        return ok({
          items: [{ storeId: "store-2", name: "합정 로스터리", distanceMeters: 900, open: true, pickupAvailable: true }],
          page: {},
        }) as never;
      }
      throw new Error(`unexpected cursor ${cursor}`);
    });

    renderSearch();
    await userEvent.click(screen.getByRole("button", { name: "현재 위치로 찾기" }));

    expect(await screen.findByText("성수 로스터리")).toBeInTheDocument();
    expect(screen.queryByText("합정 로스터리")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "매장 더 보기" }));

    expect(await screen.findByText("합정 로스터리")).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith("/stores/nearby", expect.objectContaining({
      params: expect.objectContaining({ query: expect.objectContaining({ cursor: "cursor-1" }) }),
    }));
  });
});
