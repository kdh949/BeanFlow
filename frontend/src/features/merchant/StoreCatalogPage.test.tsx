import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError } from "../../api/client";
import { merchantApi } from "../../api/merchantClient";
import { StoreCatalogPage } from "./StoreCatalogPage";

const storeId = "10000000-0000-4000-8000-000000000001";
const secondStoreId = "10000000-0000-4000-8000-000000000002";
const policy = { storeId, acceptingOrders: true, pickupEnabled: true, version: 2, updatedAt: "2026-08-27T00:00:00Z" };
const menuId = "30000000-0000-4000-8000-000000000001";
const menuSummary = {
  menuId,
  name: "카페 라테",
  basePriceKrw: 4_500,
  available: true,
  lifecycle: "ACTIVE",
  optionCount: 0,
  configurationCount: 1,
  version: 2,
  updatedAt: "2026-08-27T00:10:00Z",
};
const menuContent = {
  menuId,
  name: "카페 라테",
  basePriceKrw: 4_500,
  available: true,
  lifecycle: "ACTIVE",
  options: [],
  configurations: [{
    configurationId: "30000000-0000-4000-8000-000000000002",
    selectedOptionIds: [],
    available: true,
  }],
  version: 2,
  updatedAt: "2026-08-27T00:10:00Z",
};

function response(data: unknown, status = 200) {
  return { data, response: new Response(null, { status }) } as never;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => { resolve = next; });
  return { promise, resolve };
}

beforeEach(() => {
  sessionStorage.clear();
  document.cookie = "BEANFLOW_MERCHANT_XSRF=test-merchant-csrf; path=/";
  vi.spyOn(merchantApi, "GET").mockImplementation((async (path: string) => {
    if (path === "/merchant/me/stores") return response([{ storeId, storeName: "시청점", membershipRole: "STAFF" }]);
    if (path === "/stores/{storeId}/ordering-policy") return response(policy);
    if (path === "/stores/{storeId}/menu-catalog") return response({ items: [menuSummary] });
    if (path === "/stores/{storeId}/menus/{menuId}/trade-content") return response(menuContent);
    throw new Error(`unexpected GET ${path}`);
  }) as never);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("StoreCatalogPage", () => {
  it("keeps the selected menu when detail responses arrive in reverse order", async () => {
    const first = deferred<never>();
    const second = deferred<never>();
    const secondMenuId = "30000000-0000-4000-8000-000000000099";
    const original = vi.mocked(merchantApi.GET).getMockImplementation() as (path: string, options: unknown) => Promise<never>;
    vi.mocked(merchantApi.GET).mockImplementation(((path: string, options: { params: { path: { menuId: string } } }) => {
      if (path === "/stores/{storeId}/menu-catalog") return Promise.resolve(response({ items: [menuSummary, { ...menuSummary, menuId: secondMenuId, name: "콜드브루" }] }));
      if (path === "/stores/{storeId}/menus/{menuId}/trade-content") return options.params.path.menuId === menuId ? first.promise : second.promise;
      return original(path, options);
    }) as never);
    const put = vi.spyOn(merchantApi, "PUT").mockResolvedValue(response({ ...menuContent, menuId: secondMenuId, name: "콜드브루", version: 8 }));
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);
    await userEvent.click(await screen.findByRole("button", { name: "카페 라테 편집" }));
    await userEvent.click(screen.getByRole("button", { name: "콜드브루 편집" }));
    await act(async () => { second.resolve(response({ ...menuContent, menuId: secondMenuId, name: "콜드브루", version: 7 })); });
    expect(await screen.findByLabelText("메뉴 이름")).toHaveValue("콜드브루");
    await act(async () => { first.resolve(response(menuContent)); });
    expect(screen.getByLabelText("메뉴 이름")).toHaveValue("콜드브루");
    await userEvent.click(screen.getByRole("button", { name: "거래 내용 저장" }));
    await waitFor(() => expect(put).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({
      params: expect.objectContaining({ path: { storeId, menuId: secondMenuId } }),
      body: expect.objectContaining({ menuId: secondMenuId, expectedVersion: 7 }),
    })));
  });

  it.each(["create", "close", "archive filter"])("ignores a late menu detail after %s", async (action) => {
    const pending = deferred<never>();
    const original = vi.mocked(merchantApi.GET).getMockImplementation() as (path: string, options: unknown) => Promise<never>;
    let reads = 0;
    vi.mocked(merchantApi.GET).mockImplementation(((path: string, options: never) => {
      if (path === "/stores/{storeId}/menus/{menuId}/trade-content" && ++reads > 1) return pending.promise;
      return original(path, options);
    }) as never);
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);
    await userEvent.click(await screen.findByRole("button", { name: "카페 라테 편집" }));
    await screen.findByLabelText("메뉴 이름");
    await userEvent.click(screen.getByRole("button", { name: "카페 라테 편집" }));
    const label = action === "create" ? /새 메뉴/ : action === "close" ? "편집 닫기" : "보관된 메뉴";
    await userEvent.click(screen.getByRole("button", { name: label }));
    await act(async () => { pending.resolve(response(menuContent)); });
    if (action === "create") expect(screen.getByLabelText("메뉴 이름")).toHaveValue("");
    else expect(screen.queryByLabelText("메뉴 이름")).not.toBeInTheDocument();
  });

  it("locks the draft during save and allows another save while list refresh is pending", async () => {
    const pendingSave = deferred<never>();
    const pendingList = deferred<never>();
    const original = vi.mocked(merchantApi.GET).getMockImplementation() as (path: string, options: unknown) => Promise<never>;
    let lists = 0;
    vi.mocked(merchantApi.GET).mockImplementation(((path: string, options: never) => {
      if (path === "/stores/{storeId}/menu-catalog" && ++lists > 1) return pendingList.promise;
      return original(path, options);
    }) as never);
    const put = vi.spyOn(merchantApi, "PUT").mockReturnValueOnce(pendingSave.promise)
      .mockResolvedValueOnce(response({ ...menuContent, name: "다음 이름", version: 4 }));
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);
    await userEvent.click(await screen.findByRole("button", { name: "카페 라테 편집" }));
    const name = await screen.findByLabelText("메뉴 이름");
    await userEvent.clear(name);
    await userEvent.type(name, "저장할 이름");
    await userEvent.click(screen.getByRole("button", { name: "거래 내용 저장" }));
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    expect(name).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: /고객에게 판매 가능/ })).toBeDisabled();
    for (const label of ["새 메뉴", "편집 닫기", "카페 라테 편집", "보관된 메뉴", "옵션 추가", "판매 구성 추가"]) {
      expect(screen.getByRole("button", { name: label })).toBeDisabled();
    }
    await userEvent.type(name, "덮어쓰면 안 됨");
    expect(name).toHaveValue("저장할 이름");
    await act(async () => { pendingSave.resolve(response({ ...menuContent, name: "저장할 이름", version: 3 })); });
    expect(await screen.findByText("3번째 저장")).toBeVisible();
    expect(screen.getByText("메뉴를 불러오는 중")).toBeVisible();
    expect(screen.getByRole("button", { name: "거래 내용 저장" })).toBeEnabled();
    await userEvent.clear(name);
    await userEvent.type(name, "다음 이름");
    await userEvent.click(screen.getByRole("button", { name: "거래 내용 저장" }));
    expect(await screen.findByText("4번째 저장")).toBeVisible();
    const [, options] = put.mock.calls[1] as unknown as [string, { body: Record<string, unknown> }];
    expect(options.body).toMatchObject({ name: "다음 이름", expectedVersion: 3 });
  });

  it("lets the owner mark a menu sold out and resume sales with successive versions", async () => {
    const put = vi.spyOn(merchantApi, "PUT")
      .mockResolvedValueOnce(response({ ...menuContent, available: false, version: 3 }))
      .mockResolvedValueOnce(response({ ...menuContent, available: true, version: 4 }));
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);

    await userEvent.click(await screen.findByRole("button", { name: /카페 라테/ }));
    await userEvent.click(await screen.findByRole("checkbox", { name: /고객에게 판매 가능/ }));
    await userEvent.click(screen.getByRole("button", { name: "거래 내용 저장" }));
    expect(await screen.findByText("3번째 저장")).toBeVisible();
    await userEvent.click(screen.getByRole("checkbox", { name: /고객에게 판매 가능/ }));
    await userEvent.click(screen.getByRole("button", { name: "거래 내용 저장" }));
    expect(await screen.findByText("4번째 저장")).toBeVisible();

    const calls = put.mock.calls as unknown as Array<[string, { body: Record<string, unknown> }]>;
    expect(calls.map(([, options]) => options.body)).toEqual([
      expect.objectContaining({ available: false, expectedVersion: 2, configurations: menuContent.configurations }),
      expect.objectContaining({ available: true, expectedVersion: 3, configurations: menuContent.configurations }),
    ]);
  });

  it("replaces both flags with the server trade version and CSRF/idempotency headers", async () => {
    const put = vi.spyOn(merchantApi, "PUT").mockResolvedValue(response({ ...policy, acceptingOrders: false, version: 3 }));
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);

    await userEvent.click(await screen.findByRole("checkbox", { name: /새 주문 접수/ }));
    await userEvent.click(screen.getByRole("button", { name: "정책 저장" }));

    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    const [path, options] = put.mock.calls[0] as unknown as [string, {
      params: { path: { storeId: string }; header: Record<string, string> };
      body: Record<string, unknown>;
    }];
    expect(path).toBe("/stores/{storeId}/ordering-policy");
    expect(options.params.path.storeId).toBe(storeId);
    expect(options.params.header["X-BEANFLOW-CSRF"]).toBe("test-merchant-csrf");
    expect(options.params.header["Idempotency-Key"]).toBeTruthy();
    expect(options.body).toEqual({ acceptingOrders: false, pickupEnabled: true, expectedVersion: 2 });
    expect(await screen.findByText("주문 정책을 저장했습니다.")).toBeVisible();
  });

  it("requires an explicit reload instead of overwriting a stale server version", async () => {
    vi.spyOn(merchantApi, "PUT").mockRejectedValue(
      new ApiRequestError(409, "MERCHANT_CONTENT_STALE", "주문 정책 버전이 변경되었습니다."),
    );
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);

    await userEvent.click(await screen.findByRole("checkbox", { name: /매장 픽업/ }));
    await userEvent.click(screen.getByRole("button", { name: "정책 저장" }));

    expect(await screen.findByText("다른 변경이 먼저 저장되었습니다")).toBeVisible();
    expect(screen.getByRole("button", { name: "최신 내용 불러오기" })).toBeVisible();
    expect(screen.queryByText("주문 정책을 저장했습니다.")).not.toBeInTheDocument();
  });

  it("creates the complete menu definition with CSRF and an idempotency key", async () => {
    const post = vi.spyOn(merchantApi, "POST").mockImplementation((async (path: string, options: { body: Record<string, unknown> }) => {
      if (path === "/stores/{storeId}/menus") return response({ ...options.body, lifecycle: "ACTIVE", version: 0, updatedAt: "2026-08-27T01:00:00Z" }, 201);
      throw new Error(`unexpected POST ${path}`);
    }) as never);
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);

    await userEvent.click(await screen.findByRole("button", { name: /새 메뉴/ }));
    await userEvent.type(screen.getByLabelText("메뉴 이름"), "디카페인 아메리카노");
    await userEvent.click(screen.getByRole("button", { name: "메뉴 생성" }));

    await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
    const [path, options] = post.mock.calls[0] as unknown as [string, {
      params: { path: { storeId: string }; header: Record<string, string> };
      body: Record<string, unknown>;
    }];
    expect(path).toBe("/stores/{storeId}/menus");
    expect(options.params.path.storeId).toBe(storeId);
    expect(options.params.header["X-BEANFLOW-CSRF"]).toBe("test-merchant-csrf");
    expect(options.params.header["Idempotency-Key"]).toBeTruthy();
    expect(options.body).toMatchObject({ name: "디카페인 아메리카노", basePriceKrw: 0, available: false, options: [], configurations: [] });
    expect(options.body.menuId).toEqual(expect.any(String));
    expect(await screen.findByText("메뉴 거래 내용을 저장했습니다.")).toBeVisible();
  });

  it("keeps a stale menu draft for explicit reload", async () => {
    vi.spyOn(merchantApi, "PUT").mockRejectedValue(
      new ApiRequestError(409, "MERCHANT_CONTENT_STALE", "메뉴 거래 버전이 변경되었습니다."),
    );
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);

    await userEvent.click(await screen.findByRole("button", { name: /카페 라테/ }));
    const name = await screen.findByLabelText("메뉴 이름");
    await userEvent.clear(name);
    await userEvent.type(name, "새 라테");
    await userEvent.click(screen.getByRole("button", { name: "거래 내용 저장" }));

    expect(await screen.findByText("다른 변경이 먼저 저장되었습니다")).toBeVisible();
    expect(screen.getByRole("button", { name: "최신 내용 불러오기" })).toBeVisible();
    expect(name).toHaveValue("새 라테");
  });

  it("keeps archived Menu rows summary-only without opening a writable editor", async () => {
    const activeMenuList = deferred<never>();
    const archivedMenuList = deferred<never>();
    const get = vi.mocked(merchantApi.GET);
    get.mockImplementation(((path: string, options: {
      params?: { query?: { lifecycle?: string } };
    }) => {
      if (path === "/merchant/me/stores") return Promise.resolve(response([{ storeId, storeName: "시청점", membershipRole: "STAFF" }]));
      if (path === "/stores/{storeId}/ordering-policy") return Promise.resolve(response(policy));
      if (path === "/stores/{storeId}/menu-catalog") {
        const archived = options.params?.query?.lifecycle === "ARCHIVED";
        return archived ? archivedMenuList.promise : activeMenuList.promise;
      }
      if (path === "/stores/{storeId}/menus/{menuId}/trade-content") return Promise.resolve(response(menuContent));
      throw new Error(`unexpected GET ${path}`);
    }) as never);
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);

    const requestedLifecycle = (value: string) =>
      (get.mock.calls as unknown as Array<[string, { params?: { query?: { lifecycle?: string } } } | undefined]>).some(
        ([path, options]) => path === "/stores/{storeId}/menu-catalog" && options?.params?.query?.lifecycle === value,
      );
    await waitFor(() => expect(requestedLifecycle("ACTIVE")).toBe(true));
    await userEvent.click(await screen.findByRole("button", { name: "보관된 메뉴" }));
    await waitFor(() => expect(requestedLifecycle("ARCHIVED")).toBe(true));
    archivedMenuList.resolve(response({ items: [{ ...menuSummary, lifecycle: "ARCHIVED", available: false }] }));
    expect(await screen.findByLabelText("카페 라테 보관 요약")).toBeVisible();

    activeMenuList.resolve(response({ items: [{ ...menuSummary, lifecycle: "ACTIVE", available: true }] }));
    await waitFor(() => expect(screen.getByLabelText("카페 라테 보관 요약")).toBeVisible());
    expect(screen.queryByRole("button", { name: /카페 라테/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("메뉴 이름")).not.toBeInTheDocument();
    const getCalls = get.mock.calls as unknown as Array<[string, unknown?]>;
    expect(getCalls.filter(([path]) => path === "/stores/{storeId}/menus/{menuId}/trade-content")).toHaveLength(0);
  });

  it("keeps the reloaded policy when an earlier save finishes after a Store round trip", async () => {
    const pendingSave = deferred<never>();
    let firstStoreReads = 0;
    vi.mocked(merchantApi.GET).mockImplementation(((path: string, options: {
      params?: { path?: { storeId?: string } };
    }) => {
      if (path === "/merchant/me/stores") return Promise.resolve(response([
        { storeId, storeName: "시청점", membershipRole: "STAFF" },
        { storeId: secondStoreId, storeName: "강남점", membershipRole: "OWNER" },
      ]));
      if (path === "/stores/{storeId}/ordering-policy") {
        const requestedStoreId = options.params?.path?.storeId;
        if (requestedStoreId === storeId) {
          firstStoreReads += 1;
          return Promise.resolve(response({ ...policy, version: firstStoreReads === 1 ? 2 : 9 }));
        }
        return Promise.resolve(response({ ...policy, storeId: secondStoreId, version: 4 }));
      }
      if (path === "/stores/{storeId}/menu-catalog") return Promise.resolve(response({ items: [] }));
      throw new Error(`unexpected GET ${path}`);
    }) as never);
    const put = vi.spyOn(merchantApi, "PUT").mockReturnValue(pendingSave.promise);
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);

    await userEvent.click(await screen.findByRole("checkbox", { name: /새 주문 접수/ }));
    await userEvent.click(screen.getByRole("button", { name: "정책 저장" }));
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    const selector = screen.getByRole("combobox", { name: "매장 선택" });
    await userEvent.selectOptions(selector, secondStoreId);
    expect(await screen.findByText("4번째 저장")).toBeVisible();
    await userEvent.selectOptions(selector, storeId);
    expect(await screen.findByText("9번째 저장")).toBeVisible();
    await userEvent.click(screen.getByRole("checkbox", { name: /매장 픽업/ }));

    await act(async () => {
      pendingSave.resolve(response({ ...policy, acceptingOrders: false, version: 3 }));
      await pendingSave.promise;
    });
    expect(screen.getByText("9번째 저장")).toBeVisible();
    expect(screen.getByRole("checkbox", { name: /새 주문 접수/ })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /매장 픽업/ })).not.toBeChecked();
    expect(screen.queryByText("주문 정책을 저장했습니다.")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "정책 저장" })).toBeEnabled();
  });

  it("ignores a late ordering policy response from the previously selected Store", async () => {
    const firstPolicy = deferred<never>();
    const secondPolicy = deferred<never>();
    vi.mocked(merchantApi.GET).mockImplementation(((path: string, options: {
      params?: { path?: { storeId?: string } };
    }) => {
      if (path === "/merchant/me/stores") {
        return Promise.resolve(response([
          { storeId, storeName: "시청점", membershipRole: "STAFF" },
          { storeId: secondStoreId, storeName: "강남점", membershipRole: "OWNER" },
        ]));
      }
      if (path === "/stores/{storeId}/ordering-policy") {
        return options.params?.path?.storeId === storeId ? firstPolicy.promise : secondPolicy.promise;
      }
      if (path === "/stores/{storeId}/menu-catalog") return Promise.resolve(response({ items: [] }));
      throw new Error(`unexpected GET ${path}`);
    }) as never);
    const put = vi.spyOn(merchantApi, "PUT").mockResolvedValue(response({
      ...policy,
      storeId: secondStoreId,
      acceptingOrders: false,
      version: 4,
    }));
    render(<MemoryRouter><StoreCatalogPage /></MemoryRouter>);

    const requestedPolicyFor = (requestedStoreId: string) =>
      (vi.mocked(merchantApi.GET).mock.calls as unknown as Array<[
        string,
        { params?: { path?: { storeId?: string } } } | undefined,
      ]>).some(([path, options]) =>
        path === "/stores/{storeId}/ordering-policy" && options?.params?.path?.storeId === requestedStoreId,
      );
    const selector = await screen.findByRole("combobox", { name: "매장 선택" });
    await waitFor(() => expect(requestedPolicyFor(storeId)).toBe(true));
    await userEvent.selectOptions(selector, secondStoreId);
    await waitFor(() => expect(requestedPolicyFor(secondStoreId)).toBe(true));
    secondPolicy.resolve(response({ ...policy, storeId: secondStoreId, version: 3 }));
    expect(await screen.findByText("3번째 저장")).toBeVisible();

    firstPolicy.resolve(response({ ...policy, acceptingOrders: false, version: 7 }));
    await waitFor(() => expect(screen.getByText("3번째 저장")).toBeVisible());
    expect(screen.getByRole("checkbox", { name: /새 주문 접수/ })).toBeChecked();

    await userEvent.click(screen.getByRole("checkbox", { name: /새 주문 접수/ }));
    await userEvent.click(screen.getByRole("button", { name: "정책 저장" }));
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    const [, options] = put.mock.calls[0] as unknown as [string, {
      params: { path: { storeId: string } };
      body: { expectedVersion: number; acceptingOrders: boolean };
    }];
    expect(options.params.path.storeId).toBe(secondStoreId);
    expect(options.body).toMatchObject({ expectedVersion: 3, acceptingOrders: false });
  });
});
