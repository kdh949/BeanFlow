import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { customerApi } from "../../api/customerClient";
import { RefreshStoreSearchPage } from "../../presentation/beanflow-refresh/CustomerDiscoveryPages";
import { RefreshStoreDetailPage } from "../../presentation/beanflow-refresh/CustomerCommercePages";
import { runCustomerLogoutHandlers } from "../shared/customerLogout";
import { immediateOrderDisplay } from "./storeDisplay";
import { newSearchVisit, searchOriginState, searchReturnTarget, searchVisitForEntry } from "./storeSearchNavigation";

const store = { storeId: "store-1", name: "첫 번째 매장", orderingAvailable: true, pickupAvailable: true, customerDisplay: { operatingStatus: "OPEN" as const }, matchedMenus: [] };
let coordinates = { latitude: 37, longitude: 127 };
let requests: Array<Record<string, unknown>>;
let failSearch = false;
function ok(data: unknown) { return { data, response: new Response(null, { status: 200 }) }; }

beforeEach(() => {
  runCustomerLogoutHandlers(); requests = []; failSearch = false; coordinates = { latitude: 37, longitude: 127 };
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  Object.defineProperty(window, "scrollY", { configurable: true, value: 0 });
  Object.defineProperty(navigator, "geolocation", { configurable: true, value: { getCurrentPosition: (success: PositionCallback) => success({ coords: coordinates } as GeolocationPosition) } });
  vi.spyOn(customerApi, "GET").mockImplementation(async (path, options) => {
    const params = (options as unknown as { params?: { query?: Record<string, unknown>; path?: { storeId: string } } })?.params;
    if (path === "/stores/search" || path === "/stores/nearby") {
      const query = params?.query as Record<string, unknown>; requests.push({ ...query });
      if (failSearch) return { error: { code: "DEPENDENCY_UNAVAILABLE", message: "unavailable" }, response: new Response(null, { status: 503 }) } as never;
      return ok({ items: [query.cursor ? { ...store, storeId: "store-2", name: "두 번째 매장" } : store], page: query.cursor ? {} : { nextCursor: "fresh-next" }, distanceAvailable: query.latitude !== undefined }) as never;
    }
    if (path === "/stores/{storeId}") return ok({ ...store, storeId: params?.path?.storeId, name: "상세 매장" }) as never;
    if (path === "/me/notifications/unread-count") return ok({ unreadCount: 0 }) as never;
    return ok({ items: [] }) as never;
  });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function open(path = "/app/stores?query=시청") {
  const router = createMemoryRouter([
    { path: "/app/stores", element: <RefreshStoreSearchPage /> },
    { path: "/app/stores/:storeId", element: <RefreshStoreDetailPage /> },
  ], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

describe("search visit navigation", () => {
  it("restores query, sort, filter, fresh pages and scroll after the detail back link", async () => {
    const user = userEvent.setup(); const router = open();
    await screen.findByText("첫 번째 매장");
    await user.click(screen.getByRole("button", { name: "현재 위치로 가까운 매장 찾기" }));
    await waitFor(() => expect(requests.at(-1)).toMatchObject(coordinates));
    await user.selectOptions(screen.getByRole("combobox", { name: "검색 정렬" }), "distance");
    await user.click(screen.getByRole("button", { name: "주문 가능한 매장만" }));
    await user.click(await screen.findByRole("button", { name: "매장 더 보기" }));
    await screen.findByText("두 번째 매장");
    Object.defineProperty(window, "scrollY", { configurable: true, value: 320 }); fireEvent.scroll(window);
    await user.click(screen.getByRole("link", { name: /두 번째 매장/ }));
    await screen.findByRole("heading", { name: "상세 매장" });
    const before = requests.length;
    await user.click(screen.getByRole("link", { name: "뒤로" }));
    await screen.findByText("두 번째 매장");
    expect(screen.getByRole("searchbox")).toHaveValue("시청");
    expect(screen.getByRole("combobox")).toHaveValue("distance");
    expect(screen.getByRole("button", { name: "주문 가능한 매장만" })).toHaveAttribute("aria-pressed", "true");
    expect(requests.slice(before).map(request => request.cursor)).toEqual([undefined, "fresh-next"]);
    await waitFor(() => expect(window.scrollTo).toHaveBeenLastCalledWith(0, 320));
    expect(JSON.stringify(router.state.location.state)).not.toContain("latitude");
    expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0);
  });

  it("restores each entry's coordinates on back and forward instead of the most recent location", async () => {
    const user = userEvent.setup(); const router = open();
    await screen.findByText("첫 번째 매장");
    await user.click(screen.getByRole("button", { name: "현재 위치로 가까운 매장 찾기" }));
    await waitFor(() => expect(requests.at(-1)?.latitude).toBe(37));
    await user.clear(screen.getByRole("searchbox")); await user.type(screen.getByRole("searchbox"), "라떼");
    await user.click(screen.getByRole("button", { name: "검색" }));
    coordinates = { latitude: 35, longitude: 129 };
    await user.click(screen.getByRole("button", { name: "현재 위치로 가까운 매장 찾기" }));
    await waitFor(() => expect(requests.at(-1)?.latitude).toBe(35));
    await act(() => router.navigate(-1));
    await waitFor(() => expect(requests.at(-1)).toMatchObject({ query: "시청", latitude: 37, longitude: 127 }));
    await act(() => router.navigate(1));
    await waitFor(() => expect(requests.at(-1)).toMatchObject({ query: "라떼", latitude: 35, longitude: 129 }));
  });

  it("restores query-less nearby pages, coordinates and scroll with browser back", async () => {
    const user = userEvent.setup(); const router = open("/app/stores");
    await user.click(screen.getByRole("button", { name: "현재 위치로 가까운 매장 찾기" }));
    await user.click(await screen.findByRole("button", { name: "매장 더 보기" }));
    await screen.findByText("두 번째 매장");
    Object.defineProperty(window, "scrollY", { configurable: true, value: 440 }); fireEvent.scroll(window);
    await user.click(screen.getByRole("link", { name: /두 번째 매장/ }));
    await screen.findByRole("heading", { name: "상세 매장" });
    const before = requests.length;
    await act(() => router.navigate(-1));
    await screen.findByText("두 번째 매장");
    expect(router.state.location.pathname + router.state.location.search).toBe("/app/stores");
    expect(requests.slice(before)).toEqual([
      expect.objectContaining({ ...coordinates, cursor: undefined, radiusMeters: 10_000 }),
      expect.objectContaining({ ...coordinates, cursor: "fresh-next", radiusMeters: 10_000 }),
    ]);
    await waitFor(() => expect(window.scrollTo).toHaveBeenLastCalledWith(0, 440));
  });

  it("keeps a topbar return entry independent from its original history location", async () => {
    const user = userEvent.setup(); const router = open();
    await user.click(screen.getByRole("button", { name: "현재 위치로 가까운 매장 찾기" }));
    await waitFor(() => expect(requests.at(-1)?.latitude).toBe(37));
    await user.click(await screen.findByRole("link", { name: /첫 번째 매장/ }));
    await screen.findByRole("heading", { name: "상세 매장" });
    await user.click(screen.getByRole("link", { name: "뒤로" }));
    await screen.findByText("첫 번째 매장");
    coordinates = { latitude: 35, longitude: 129 };
    await user.click(screen.getByRole("button", { name: "현재 위치로 가까운 매장 찾기" }));
    await waitFor(() => expect(requests.at(-1)?.latitude).toBe(35));
    await act(() => router.navigate(-2));
    await waitFor(() => expect(requests.at(-1)?.latitude).toBe(37));
  });

  it("does not send a distance query without location after a reload or shared URL", async () => {
    open("/app/stores?query=시청&sort=distance&openOnly=true");
    await screen.findByText("거리순 검색에 위치가 필요해요");
    expect(requests).toHaveLength(0);
    expect(screen.getByRole("combobox")).toHaveValue("distance");
    expect(screen.getByRole("button", { name: "주문 가능한 매장만" })).toHaveAttribute("aria-pressed", "true");
  });

  it("shows a fresh read failure on return, then retries instead of showing cached stores", async () => {
    const user = userEvent.setup(); open();
    await user.click(await screen.findByRole("link", { name: /첫 번째 매장/ }));
    await screen.findByRole("heading", { name: "상세 매장" }); failSearch = true;
    await user.click(screen.getByRole("link", { name: "뒤로" }));
    await screen.findByRole("alert"); expect(screen.queryByText("첫 번째 매장")).not.toBeInTheDocument();
    failSearch = false; await user.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("첫 번째 매장");
  });

  it("keeps loaded pages when retrying a failed third-page append", async () => {
    let thirdAttempts = 0;
    vi.mocked(customerApi.GET).mockImplementation(async (path, options) => {
      if (path !== "/stores/search") return ok({ unreadCount: 0, items: [] }) as never;
      const query = (options as unknown as { params: { query: { cursor?: string } } }).params.query;
      if (query.cursor === "third" && ++thirdAttempts === 1) {
        return { error: { code: "DEPENDENCY_UNAVAILABLE", message: "unavailable" }, response: new Response(null, { status: 503 }) } as never;
      }
      const page = query.cursor === "third" ? 3 : query.cursor === "second" ? 2 : 1;
      return ok({ items: [{ ...store, storeId: `store-${page}`, name: `${page}페이지 매장` }], page: page === 3 ? {} : { nextCursor: page === 1 ? "second" : "third" }, distanceAvailable: false }) as never;
    });
    const user = userEvent.setup(); open();
    await screen.findByText("1페이지 매장");
    await user.click(screen.getByRole("button", { name: "매장 더 보기" }));
    await screen.findByText("2페이지 매장");
    await user.click(screen.getByRole("button", { name: "매장 더 보기" }));
    await screen.findByRole("alert");
    expect(screen.getByText("1페이지 매장")).toBeVisible();
    expect(screen.getByText("2페이지 매장")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("3페이지 매장");
    expect(screen.getByText("1페이지 매장")).toBeVisible();
    expect(screen.getByText("2페이지 매장")).toBeVisible();
    expect(screen.queryByRole("button", { name: "매장 더 보기" })).not.toBeInTheDocument();
    expect(thirdAttempts).toBe(2);
  });

  it("validates the exact path, supports query-less visits, and clears or evicts metadata", () => {
    const visit = newSearchVisit("/app/stores", coordinates);
    expect(searchReturnTarget(searchOriginState(visit))).toEqual({ to: "/app/stores", state: { searchVisitId: visit.id } });
    for (const path of ["https://other.invalid/app/stores", "/app/stores/other", "/app/stores-invalid", "/app/stores?query=changed"]) {
      expect(searchReturnTarget({ searchOrigin: { path, visitId: visit.id } })).toEqual({ to: "/app/stores" });
    }
    runCustomerLogoutHandlers();
    expect(searchReturnTarget(searchOriginState(visit))).toEqual({ to: "/app/stores" });
    expect(searchVisitForEntry("reload", "/app/stores", { searchVisitId: visit.id }).coordinates).toBeNull();
    const oldest = newSearchVisit("/app/stores?query=old", coordinates);
    for (let index = 0; index < 20; index++) newSearchVisit(`/app/stores?query=${index}`, null);
    expect(searchReturnTarget(searchOriginState(oldest))).toEqual({ to: "/app/stores" });
  });
});

describe("immediate order display", () => {
  it("requires both policy availability and OPEN hours and does not invent pickup slots", () => {
    expect(immediateOrderDisplay(store)).toEqual({ available: true, label: "주문 가능", description: "결제 후 바로 접수" });
    expect(immediateOrderDisplay({ ...store, orderingAvailable: false }).label).toBe("주문 쉬는 중");
    expect(immediateOrderDisplay({ ...store, pickupAvailable: false, customerDisplay: { operatingStatus: "CLOSED" } }).label).toBe("영업시간 아님");
    expect(immediateOrderDisplay({ ...store, pickupAvailable: false, customerDisplay: { operatingStatus: "UNSPECIFIED" } }).label).toBe("운영시간 정보 없음");
  });
});
