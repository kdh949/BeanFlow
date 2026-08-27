import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, delay, http } from "msw";
import { ids, merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { StoreCatalogPage } from "./StoreCatalogPage";

const stores = http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([
  { storeId: ids.store, storeName: "시청점", membershipRole: "STAFF" },
]));
const policy = {
  storeId: ids.store,
  acceptingOrders: true,
  pickupEnabled: true,
  version: 3,
  updatedAt: "2026-08-27T03:00:00Z",
};
const currentPolicy = http.get("/api/v1/stores/:storeId/ordering-policy", () => HttpResponse.json(policy));
const savePolicy = http.put("/api/v1/stores/:storeId/ordering-policy", async ({ request }) => {
  const body = await request.json() as { acceptingOrders: boolean; pickupEnabled: boolean; expectedVersion: number };
  return HttpResponse.json({
    ...policy,
    acceptingOrders: body.acceptingOrders,
    pickupEnabled: body.pickupEnabled,
    version: body.expectedVersion + 1,
    updatedAt: "2026-08-27T04:00:00Z",
  });
});
const menuId = "30000000-0000-4000-8000-000000000001";
const optionId = "30000000-0000-4000-8000-000000000002";
const configurationId = "30000000-0000-4000-8000-000000000003";
const sellableUnitId = "30000000-0000-4000-8000-000000000004";
const menuSummary = {
  menuId,
  name: "카페 라테",
  basePriceKrw: 4_500,
  available: true,
  lifecycle: "ACTIVE" as const,
  optionCount: 1,
  configurationCount: 1,
  version: 2,
  updatedAt: "2026-08-27T03:10:00Z",
};
const menuContent = {
  menuId,
  name: "카페 라테",
  basePriceKrw: 4_500,
  available: true,
  lifecycle: "ACTIVE" as const,
  options: [{ optionId, name: "샷 추가", additionalPriceKrw: 500, available: true }],
  configurations: [{
    configurationId,
    selectedOptionIds: [optionId],
    available: true,
    requirements: [{ sellableUnitId, quantityPerLineUnit: 1 }],
  }],
  version: 2,
  updatedAt: "2026-08-27T03:10:00Z",
};
const listMenus = http.get("/api/v1/stores/:storeId/menu-catalog", ({ request }) => {
  const archived = new URL(request.url).searchParams.get("lifecycle") === "ARCHIVED";
  return HttpResponse.json({
    items: archived ? [{ ...menuSummary, lifecycle: "ARCHIVED", available: false }] : [menuSummary],
  });
});
const getMenu = http.get("/api/v1/stores/:storeId/menus/:menuId/trade-content", () => HttpResponse.json(menuContent));
const createMenu = http.post("/api/v1/stores/:storeId/menus", async ({ request }) => {
  const body = await request.json() as typeof menuContent;
  return HttpResponse.json({ ...body, lifecycle: "ACTIVE", version: 0, updatedAt: "2026-08-27T04:00:00Z" });
});
const replaceMenu = http.put("/api/v1/stores/:storeId/menus/:menuId/trade-content", async ({ request }) => {
  const body = await request.json() as typeof menuContent & { expectedVersion: number };
  return HttpResponse.json({ ...body, lifecycle: "ACTIVE", version: body.expectedVersion + 1, updatedAt: "2026-08-27T04:00:00Z" });
});
const archiveMenu = http.post("/api/v1/stores/:storeId/menus/:menuId/archive", () => HttpResponse.json({
  ...menuContent, lifecycle: "ARCHIVED", available: false, version: 3,
}));
const menuHandlers = [listMenus, getMenu, createMenu, replaceMenu, archiveMenu];

const meta = {
  title: "Pages/Store/Catalog",
  component: StoreCatalogPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component: "기존 Store console composition과 FeedbackState/Button을 조합해 주문 정책과 Menu Aggregate 전체 교체·보관을 관리합니다. stale 응답은 자동 덮어쓰기 대신 명시적 reload를 요구합니다.",
      },
      story: { inline: false, height: "760px" },
    },
    a11y: { test: "error" },
    routing: { path: "/store/catalog", initialEntry: "/store/catalog" },
    msw: { handlers: [...merchantSignedInHandlers, stores, currentPolicy, savePolicy, ...menuHandlers] },
  },
} satisfies Meta<typeof StoreCatalogPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const OrdersAndPickupEnabled: Story = {};

export const OrdersPaused: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        stores,
        ...menuHandlers,
        http.get("/api/v1/stores/:storeId/ordering-policy", () => HttpResponse.json({
          ...policy,
          acceptingOrders: false,
        })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("checkbox", { name: /새 주문 접수/ })).not.toBeChecked();
    await expect(canvas.getByRole("checkbox", { name: /매장 픽업/ })).toBeChecked();
  },
};

export const PickupDisabled: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        stores,
        ...menuHandlers,
        http.get("/api/v1/stores/:storeId/ordering-policy", () => HttpResponse.json({
          ...policy,
          pickupEnabled: false,
        })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("checkbox", { name: /새 주문 접수/ })).toBeChecked();
    await expect(canvas.getByRole("checkbox", { name: /매장 픽업/ })).not.toBeChecked();
  },
};

export const PolicySaved: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("checkbox", { name: /새 주문 접수/ }));
    await userEvent.click(canvas.getByRole("button", { name: "정책 저장" }));
    await expect(await canvas.findByText("주문 정책을 저장했습니다.")).toBeVisible();
    await expect(canvas.getByText("VERSION 4")).toBeVisible();
  },
};

export const VersionConflict: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        stores,
        ...menuHandlers,
        currentPolicy,
        http.put("/api/v1/stores/:storeId/ordering-policy", () => HttpResponse.json({
          code: "MERCHANT_CONTENT_STALE",
          message: "주문 정책 버전이 변경되었습니다.",
          correlationId: "REQ-POLICY-409",
        }, { status: 409 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("checkbox", { name: /매장 픽업/ }));
    await userEvent.click(canvas.getByRole("button", { name: "정책 저장" }));
    await expect(await canvas.findByText("다른 변경이 먼저 저장되었습니다")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "서버 값 다시 불러오기" })).toBeVisible();
  },
};

export const IdempotencyKeyReused: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        stores,
        ...menuHandlers,
        currentPolicy,
        http.put("/api/v1/stores/:storeId/ordering-policy", () => HttpResponse.json({
          code: "IDEMPOTENCY_KEY_REUSED",
          message: "요청 키가 다른 주문 정책에 사용되었습니다. 내용을 확인해 다시 저장해 주세요.",
          correlationId: "REQ-POLICY-IDEMPOTENCY-409",
        }, { status: 409 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("checkbox", { name: /새 주문 접수/ }));
    await userEvent.click(canvas.getByRole("button", { name: "정책 저장" }));
    await expect(await canvas.findByText(/요청 키가 다른 주문 정책/)).toBeVisible();
  },
};

export const SavingPolicy: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        stores,
        ...menuHandlers,
        currentPolicy,
        http.put("/api/v1/stores/:storeId/ordering-policy", async () => {
          await delay("infinite");
          return HttpResponse.json(policy);
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("checkbox", { name: /새 주문 접수/ }));
    await userEvent.click(canvas.getByRole("button", { name: "정책 저장" }));
    await expect(canvas.getByRole("button", { name: "저장 중" })).toBeDisabled();
  },
};

export const PermissionLost: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        stores,
        ...menuHandlers,
        http.get("/api/v1/stores/:storeId/ordering-policy", () => HttpResponse.json({
          code: "ACCESS_DENIED",
          message: "매장 카탈로그 권한이 회수되었습니다.",
          correlationId: "REQ-POLICY-403",
        }, { status: 403 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("매장 카탈로그 권한이 회수되었습니다.")).toBeVisible();
  },
};

export const PolicyDependencyUnavailable: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        stores,
        ...menuHandlers,
        http.get("/api/v1/stores/:storeId/ordering-policy", () => HttpResponse.json({
          code: "DEPENDENCY_UNAVAILABLE",
          message: "주문 정책을 조회하지 못했습니다.",
          correlationId: "REQ-POLICY-503",
        }, { status: 503 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("주문 정책을 조회하지 못했습니다.")).toBeVisible();
  },
};

export const LoadingPolicy: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        stores,
        ...menuHandlers,
        http.get("/api/v1/stores/:storeId/ordering-policy", async () => {
          await delay("infinite");
          return HttpResponse.json(policy);
        }),
      ],
    },
  },
};

export const ActiveMenuList: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "메뉴 거래 내용" })).toBeVisible();
    await expect(await canvas.findByText("카페 라테")).toBeVisible();
    await expect(canvas.getByText("4,500원 · 옵션 1 · 구성 1")).toBeVisible();
  },
};

export const EmptyMenuCatalog: Story = {
  parameters: {
    msw: { handlers: [
      ...merchantSignedInHandlers, stores, currentPolicy, savePolicy,
      http.get("/api/v1/stores/:storeId/menu-catalog", () => HttpResponse.json({ items: [] })),
    ] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("등록된 메뉴가 없습니다")).toBeVisible();
  },
};

export const ArchivedMenuList: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "보관된 메뉴" }));
    await expect(await canvas.findByText("카페 라테")).toBeVisible();
    await expect(canvas.getByLabelText("카페 라테 보관 요약")).toBeVisible();
    await expect(canvas.queryByRole("button", { name: /카페 라테/ })).not.toBeInTheDocument();
    await expect(canvas.queryByLabelText("메뉴 이름")).not.toBeInTheDocument();
    await expect(canvas.queryByRole("button", { name: "거래 내용 저장" })).not.toBeInTheDocument();
    await waitFor(() => expect(canvas.queryByRole("button", { name: /보관$/ })).not.toBeInTheDocument());
  },
};

export const PaginatedMenuCatalog: Story = {
  parameters: {
    msw: { handlers: [
      ...merchantSignedInHandlers, stores, currentPolicy, savePolicy,
      http.get("/api/v1/stores/:storeId/menu-catalog", ({ request }) => {
        const cursor = new URL(request.url).searchParams.get("cursor");
        return HttpResponse.json(cursor
          ? { items: [{ ...menuSummary, menuId: "30000000-0000-4000-8000-000000000088", name: "두 번째 페이지" }] }
          : { items: [menuSummary], nextCursor: "signed-cursor-page-2" });
      }),
    ] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("카페 라테")).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "메뉴 더 보기" }));
    await expect(await canvas.findByText("두 번째 페이지")).toBeVisible();
    await expect(canvas.queryByRole("button", { name: "메뉴 더 보기" })).not.toBeInTheDocument();
  },
};

export const CreateDraftValidation: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: /새 메뉴/ }));
    const name = canvas.getByLabelText("메뉴 이름");
    await userEvent.click(canvas.getByRole("button", { name: "메뉴 생성" }));
    await expect(name).toBeInvalid();
    await expect(name).toHaveFocus();
  },
};

export const MenuSaved: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: /새 메뉴/ }));
    await userEvent.type(canvas.getByLabelText("메뉴 이름"), "디카페인 아메리카노");
    await userEvent.click(canvas.getByRole("button", { name: "메뉴 생성" }));
    await expect(await canvas.findByText("메뉴 거래 내용을 저장했습니다.")).toBeVisible();
    await expect(canvas.getByText("VERSION 0")).toBeVisible();
  },
};

export const MenuVersionConflict: Story = {
  parameters: {
    msw: { handlers: [
      ...merchantSignedInHandlers, stores, currentPolicy, savePolicy,
      http.put("/api/v1/stores/:storeId/menus/:menuId/trade-content", () => HttpResponse.json({
        code: "MERCHANT_CONTENT_STALE",
        message: "메뉴 거래 버전이 변경되었습니다.",
        correlationId: "REQ-MENU-409",
      }, { status: 409 })),
      ...menuHandlers,
    ] },
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: /카페 라테/ }));
    const name = await canvas.findByLabelText("메뉴 이름");
    await userEvent.clear(name);
    await userEvent.type(name, "새 라테");
    await userEvent.click(canvas.getByRole("button", { name: "거래 내용 저장" }));
    await expect(await canvas.findByText("다른 변경이 먼저 저장되었습니다")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "서버 값 다시 불러오기" })).toBeVisible();
  },
};

export const SavingMenu: Story = {
  parameters: {
    msw: { handlers: [
      ...merchantSignedInHandlers, stores, currentPolicy, savePolicy,
      http.put("/api/v1/stores/:storeId/menus/:menuId/trade-content", async () => {
        await delay("infinite");
        return HttpResponse.json(menuContent);
      }),
      ...menuHandlers,
    ] },
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: /카페 라테/ }));
    await userEvent.type(await canvas.findByLabelText("메뉴 이름"), " 수정");
    await userEvent.click(canvas.getByRole("button", { name: "거래 내용 저장" }));
    await expect(canvas.getByRole("button", { name: "저장 중" })).toBeDisabled();
  },
};

export const MenuDependencyUnavailable: Story = {
  parameters: {
    msw: { handlers: [
      ...merchantSignedInHandlers, stores, currentPolicy, savePolicy,
      http.get("/api/v1/stores/:storeId/menu-catalog", () => HttpResponse.json({
        code: "DEPENDENCY_UNAVAILABLE",
        message: "메뉴 카탈로그 저장소를 사용할 수 없습니다.",
        correlationId: "REQ-MENU-503",
      }, { status: 503 })),
    ] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("메뉴를 불러오지 못했습니다")).toBeVisible();
    await expect(canvas.getByText("메뉴 카탈로그 저장소를 사용할 수 없습니다.")).toBeVisible();
  },
};

export const LoadingMenuCatalog: Story = {
  parameters: {
    msw: { handlers: [
      ...merchantSignedInHandlers, stores, currentPolicy, savePolicy,
      http.get("/api/v1/stores/:storeId/menu-catalog", async () => {
        await delay("infinite");
        return HttpResponse.json({ items: [] });
      }),
    ] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("메뉴를 불러오는 중")).toBeVisible();
  },
};

export const MenuPermissionLost: Story = {
  parameters: {
    msw: { handlers: [
      ...merchantSignedInHandlers, stores, currentPolicy, savePolicy,
      http.get("/api/v1/stores/:storeId/menu-catalog", () => HttpResponse.json({
        code: "ACCESS_DENIED",
        message: "메뉴 카탈로그 권한이 회수되었습니다.",
        correlationId: "REQ-MENU-403",
      }, { status: 403 })),
    ] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("메뉴 카탈로그 권한이 회수되었습니다.")).toBeVisible();
  },
};

export const MenuIdempotencyKeyReused: Story = {
  parameters: {
    msw: { handlers: [
      ...merchantSignedInHandlers, stores, currentPolicy, savePolicy,
      http.put("/api/v1/stores/:storeId/menus/:menuId/trade-content", () => HttpResponse.json({
        code: "IDEMPOTENCY_KEY_REUSED",
        message: "요청 키가 다른 메뉴 변경에 사용되었습니다.",
        correlationId: "REQ-MENU-IDEMPOTENCY-409",
      }, { status: 409 })),
      ...menuHandlers,
    ] },
  },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: /카페 라테/ }));
    await userEvent.type(await canvas.findByLabelText("메뉴 이름"), " 수정");
    await userEvent.click(canvas.getByRole("button", { name: "거래 내용 저장" }));
    await expect(await canvas.findByText("요청 키가 다른 메뉴 변경에 사용되었습니다.")).toBeVisible();
  },
};

export const ArchiveConfirmationFocus: Story = {
  play: async ({ canvas }) => {
    const archive = await canvas.findByRole("button", { name: "보관" });
    await userEvent.click(archive);
    const cancel = await canvas.findByRole("button", { name: "취소" });
    await expect(cancel).toHaveFocus();
    await userEvent.click(cancel);
    await expect(archive).toHaveFocus();
  },
};

export const BoundarySummaryAndLongKoreanName: Story = {
  parameters: {
    msw: { handlers: [
      ...merchantSignedInHandlers, stores, currentPolicy, savePolicy,
      http.get("/api/v1/stores/:storeId/menu-catalog", () => HttpResponse.json({ items: [{
        ...menuSummary,
        name: "제주 유기농 말차 크림과 흑임자 콜드폼을 올린 아주 긴 이름의 시그니처 라테",
        basePriceKrw: 999_999_999,
        optionCount: 100,
        configurationCount: 500,
      }, { ...menuSummary, menuId: "30000000-0000-4000-8000-000000000099", name: "오늘의 0원 시음 메뉴", basePriceKrw: 0 }] })),
    ] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(/제주 유기농 말차/)).toBeVisible();
    await expect(canvas.getByText("999,999,999원 · 옵션 100 · 구성 500")).toBeVisible();
    await expect(canvas.getByText("0원 · 옵션 1 · 구성 1")).toBeVisible();
  },
};

export const MobileMenuCatalog: Story = {
  parameters: { viewport: { defaultViewport: "mobile1" } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: "메뉴 거래 내용" })).toBeVisible();
    await expect(canvas.getByRole("button", { name: /새 메뉴/ })).toBeVisible();
  },
};

export const NoStoreMembership: Story = {
  parameters: {
    msw: { handlers: [...merchantSignedInHandlers, http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([]))] },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("관리할 수 있는 매장이 없습니다")).toBeVisible();
  },
};
