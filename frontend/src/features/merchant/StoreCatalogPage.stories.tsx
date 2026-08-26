import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
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

const meta = {
  title: "Pages/Store/Catalog",
  component: StoreCatalogPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component: "기존 Store console composition과 FeedbackState/Button을 조합해 versioned 주문 접수 정책을 관리합니다. stale 응답은 자동 덮어쓰기 대신 명시적 reload를 요구합니다.",
      },
      story: { inline: false, height: "760px" },
    },
    routing: { path: "/store/catalog", initialEntry: "/store/catalog" },
    msw: { handlers: [...merchantSignedInHandlers, stores, currentPolicy, savePolicy] },
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
        http.get("/api/v1/stores/:storeId/ordering-policy", async () => {
          await delay("infinite");
          return HttpResponse.json(policy);
        }),
      ],
    },
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
