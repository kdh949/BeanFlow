import { cleanup, render, screen, waitFor } from "@testing-library/react";
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
    requirements: [{ sellableUnitId: "30000000-0000-4000-8000-000000000003", quantityPerLineUnit: 1 }],
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
    expect(screen.getByRole("button", { name: "서버 값 다시 불러오기" })).toBeVisible();
    expect(screen.queryByText("주문 정책을 저장했습니다.")).not.toBeInTheDocument();
  });

  it("creates the complete menu definition with CSRF and an idempotency key", async () => {
    const post = vi.spyOn(merchantApi, "POST").mockImplementation((async (path: string, options: { body: Record<string, unknown> }) => {
      if (path === "/stores/{storeId}/menus") return response({ ...options.body, lifecycle: "ACTIVE", version: 0, updatedAt: "2026-08-27T01:00:00Z" });
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
    expect(screen.getByRole("button", { name: "서버 값 다시 불러오기" })).toBeVisible();
    expect(name).toHaveValue("새 라테");
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
    expect(await screen.findByText("VERSION 3")).toBeVisible();

    firstPolicy.resolve(response({ ...policy, acceptingOrders: false, version: 7 }));
    await waitFor(() => expect(screen.getByText("VERSION 3")).toBeVisible());
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
