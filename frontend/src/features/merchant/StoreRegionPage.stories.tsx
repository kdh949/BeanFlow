import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { HttpResponse, delay, http } from "msw";
import { ids, merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { StoreRegionPage } from "./StoreRegionPage";

const ownerStores = http.get("/api/v1/merchant/me/stores", () =>
  HttpResponse.json([{ storeId: ids.store, storeName: "시청점", membershipRole: "OWNER" }]));

const staffStores = http.get("/api/v1/merchant/me/stores", () =>
  HttpResponse.json([{ storeId: ids.store, storeName: "시청점", membershipRole: "STAFF" }]));

const regions = [
  {
    code: "1168010100",
    sido: "서울특별시",
    sigungu: "강남구",
    eupmyeondong: "역삼동",
    ri: "",
    fullName: "서울특별시 강남구 역삼동",
  },
  {
    code: "1168010500",
    sido: "서울특별시",
    sigungu: "강남구",
    eupmyeondong: "삼성동",
    ri: "",
    fullName: "서울특별시 강남구 삼성동",
  },
];

const regionSearch = http.get("/api/v1/regions", ({ request }) => {
  const query = new URL(request.url).searchParams.get("query") ?? "";
  return HttpResponse.json({
    items: regions.filter((region) => region.fullName.includes(query)),
    page: { nextCursor: null },
  });
});

const assignSuccess = http.put("/api/v1/stores/:storeId/region", async ({ request, params }) => {
  const body = await request.json() as { regionCode: string };
  const region = regions.find((candidate) => candidate.code === body.regionCode);
  return HttpResponse.json({
    storeId: params.storeId,
    regionCode: body.regionCode,
    regionFullName: region?.fullName ?? "알 수 없는 지역",
  });
});

const meta = {
  title: "Pages/Store/Region",
  component: StoreRegionPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "점주가 서버의 활성 법정동 폐쇄 어휘를 검색해 매장 지역을 지정합니다. 검색어를 저장값으로 재사용하지 않고, 저장 응답만 현재 화면에 표시합니다.",
      },
      story: { inline: false, height: "820px" },
    },
    routing: { path: "/store/region", initialEntry: "/store/region" },
    msw: { handlers: [...merchantSignedInHandlers, ownerStores, regionSearch, assignSuccess] },
  },
} satisfies Meta<typeof StoreRegionPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SearchResults: Story = {
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("지역 검색"), "강남구");
    await userEvent.click(canvas.getByRole("button", { name: "검색" }));
    await expect(await canvas.findByRole("radio", { name: /역삼동/ })).toBeVisible();
    await expect(canvas.getByRole("radio", { name: /삼성동/ })).toBeVisible();
  },
};

export const NoResults: Story = {
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("지역 검색"), "없는 지역");
    await userEvent.click(canvas.getByRole("button", { name: "검색" }));
    await expect(await canvas.findByText("검색 결과가 없습니다")).toBeVisible();
  },
};

export const AssignmentSucceeded: Story = {
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("지역 검색"), "역삼동");
    await userEvent.click(canvas.getByRole("button", { name: "검색" }));
    await userEvent.click(await canvas.findByRole("radio", { name: /역삼동/ }));
    await userEvent.type(canvas.getByLabelText("지정 사유"), "사업자등록증 소재지 확인");
    await userEvent.click(canvas.getByRole("button", { name: "지역 지정" }));
    await expect(await canvas.findByText("지역을 지정했습니다")).toBeVisible();
  },
};

export const AssignmentConflict: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        ownerStores,
        regionSearch,
        http.put("/api/v1/stores/:storeId/region", () => HttpResponse.json({
          code: "IDEMPOTENCY_KEY_REUSED",
          message: "요청 키가 다른 지역 지정에 사용되었습니다. 내용을 확인해 다시 제출해 주세요.",
          correlationId: "REQ-REGION-409",
        }, { status: 409 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("지역 검색"), "역삼동");
    await userEvent.click(canvas.getByRole("button", { name: "검색" }));
    await userEvent.click(await canvas.findByRole("radio", { name: /역삼동/ }));
    await userEvent.type(canvas.getByLabelText("지정 사유"), "소재지 정정");
    await userEvent.click(canvas.getByRole("button", { name: "지역 지정" }));
    await expect(await canvas.findByText("요청 정보가 변경되었습니다")).toBeVisible();
    await expect(canvas.queryByText("지역을 지정했습니다")).not.toBeInTheDocument();
  },
};

export const StaffHasNoOwnedStore: Story = {
  parameters: { msw: { handlers: [...merchantSignedInHandlers, staffStores] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("지역을 설정할 수 있는 매장이 없습니다")).toBeVisible();
  },
};

export const SearchUnavailable: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        ownerStores,
        http.get("/api/v1/regions", async () => {
          await delay(50);
          return HttpResponse.json({
            code: "DEPENDENCY_UNAVAILABLE",
            message: "지역 기준정보를 조회하지 못했습니다.",
            correlationId: "REQ-REGION-503",
          }, { status: 503 });
        }),
      ],
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.type(await canvas.findByLabelText("지역 검색"), "역삼동");
    await userEvent.click(canvas.getByRole("button", { name: "검색" }));
    await expect(await canvas.findByText("서비스 연결을 확인하고 있습니다")).toBeVisible();
  },
};
