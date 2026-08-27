import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError } from "../../api/client";
import { merchantApi } from "../../api/merchantClient";
import { StoreRegionPage } from "./StoreRegionPage";

const storeId = "10000000-0000-4000-8000-000000000001";

function response(data: unknown, status = 200) {
  return { data, response: new Response(null, { status }) } as never;
}

function mockOwnerAndRegions() {
  vi.spyOn(merchantApi, "GET").mockImplementation((async (path: string) => {
    if (path === "/merchant/me/stores") {
      return response([{ storeId, storeName: "시청점", membershipRole: "OWNER" }]);
    }
    if (path === "/regions") {
      return response({
        items: [
          {
            code: "1168010100",
            sido: "서울특별시",
            sigungu: "강남구",
            eupmyeondong: "역삼동",
            ri: "",
            fullName: "서울특별시 강남구 역삼동",
          },
        ],
        page: { nextCursor: null },
      });
    }
    throw new Error(`unexpected GET ${path}`);
  }) as never);
}

beforeEach(() => {
  sessionStorage.clear();
  document.cookie = "BEANFLOW_MERCHANT_XSRF=test-merchant-csrf; path=/";
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("StoreRegionPage", () => {
  it("sends only the selected server region code with the reason", async () => {
    mockOwnerAndRegions();
    const put = vi.spyOn(merchantApi, "PUT").mockResolvedValue(response({
      storeId,
      regionCode: "1168010100",
      regionFullName: "서울특별시 강남구 역삼동",
    }));

    render(<MemoryRouter><StoreRegionPage /></MemoryRouter>);
    await userEvent.type(await screen.findByLabelText("지역 검색"), "역삼동");
    await userEvent.click(screen.getByRole("button", { name: "검색" }));
    await userEvent.click(await screen.findByRole("radio", { name: /서울특별시 강남구 역삼동/ }));
    await userEvent.type(screen.getByLabelText("지정 사유"), "사업자등록증 소재지 확인");
    await userEvent.click(screen.getByRole("button", { name: "지역 지정" }));

    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    const [path, options] = put.mock.calls[0] as unknown as [string, {
      params: { path: { storeId: string }; header: Record<string, string> };
      body: Record<string, unknown>;
    }];
    expect(path).toBe("/stores/{storeId}/region");
    expect(options.params.path.storeId).toBe(storeId);
    expect(options.params.header["X-BEANFLOW-CSRF"]).toBe("test-merchant-csrf");
    expect(options.params.header["Idempotency-Key"]).toBeTruthy();
    expect(options.body).toEqual({
      regionCode: "1168010100",
      reason: "사업자등록증 소재지 확인",
    });
    expect(JSON.stringify(options.body)).not.toContain("역삼동");
    expect(await screen.findByText("지역을 지정했습니다")).toBeVisible();
  });

  it("keeps a 409 conflict explicit instead of showing success", async () => {
    mockOwnerAndRegions();
    vi.spyOn(merchantApi, "PUT").mockRejectedValue(
      new ApiRequestError(409, "IDEMPOTENCY_KEY_REUSED", "같은 요청 키가 다른 지역 지정에 사용되었습니다."),
    );

    render(<MemoryRouter><StoreRegionPage /></MemoryRouter>);
    await userEvent.type(await screen.findByLabelText("지역 검색"), "역삼동");
    await userEvent.click(screen.getByRole("button", { name: "검색" }));
    await userEvent.click(await screen.findByRole("radio", { name: /서울특별시 강남구 역삼동/ }));
    await userEvent.type(screen.getByLabelText("지정 사유"), "소재지 정정");
    await userEvent.click(screen.getByRole("button", { name: "지역 지정" }));

    expect(await screen.findByText("요청 정보가 변경되었습니다")).toBeVisible();
    expect(screen.queryByText("지역을 지정했습니다")).not.toBeInTheDocument();
  });
});
