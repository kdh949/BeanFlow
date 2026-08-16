import { HttpResponse, delay, http } from "msw";
import type { components } from "../src/api/schema";

type CompensationSummary = components["schemas"]["CompensationSummary"];
type Refund = components["schemas"]["Refund"];

export const ids = {
  store: "10000000-0000-4000-8000-000000000001",
  menu: "20000000-0000-4000-8000-000000000001",
  slot: "30000000-0000-4000-8000-000000000001",
  order: "40000000-0000-4000-8000-000000000001",
  payment: "50000000-0000-4000-8000-000000000001",
} as const;

export const orderSummary = {
  orderReference: "BF-7K3M-9Q2P",
  pickupNumber: "A-142",
  storeName: "시청점",
  status: "READY",
  orderedAt: "2026-08-15T02:50:00Z",
  pickupWindowStart: "2026-08-15T03:20:00Z",
  pickupWindowEnd: "2026-08-15T03:30:00Z",
  totalAmountKrw: 12_800,
  currency: "KRW",
  itemSummary: "아이스 아메리카노 외 1건",
  allowedActions: [],
};

export const orderDetail = {
  ...orderSummary,
  storeId: ids.store,
  allowedActions: ["CANCEL"],
  lines: [
    { lineSequence: 0, menuName: "아이스 아메리카노", optionNames: ["ICE", "샷 추가"], quantity: 2, lineTotalKrw: 9_000 },
    { lineSequence: 1, menuName: "오트 라떼", optionNames: ["HOT"], quantity: 1, lineTotalKrw: 3_800 },
  ],
};

export const checkoutOrder = {
  orderId: ids.order,
  publicReference: orderSummary.orderReference,
  storeId: ids.store,
  customerId: "60000000-0000-4000-8000-000000000001",
  state: "PENDING_PAYMENT",
  lines: [
    {
      orderLineId: "70000000-0000-4000-8000-000000000001",
      menuId: ids.menu,
      menuName: "오트 라떼",
      optionIds: [],
      optionNames: ["ICE"],
      quantity: 2,
      unitPriceKrw: 6_400,
      subtotalKrw: 12_800,
      couponDiscountAllocatedKrw: 0,
      pointsAllocatedKrw: 0,
      cashPaidKrw: 12_800,
    },
  ],
  subtotalKrw: 12_800,
  couponDiscountKrw: 0,
  pointsAppliedKrw: 0,
  payableKrw: 12_800,
  currency: "KRW",
  reservationExpiresAt: "2026-08-15T03:10:00Z",
};

export function payment(approvalState: string) {
  return {
    paymentId: ids.payment,
    orderId: ids.order,
    type: "EXTERNAL",
    approvalState,
    approvedAmountKrw: approvalState === "APPROVED" ? 12_800 : undefined,
    currency: "KRW",
    updatedAt: "2026-08-15T03:00:00Z",
    correlationId: "REQ-DEMO-42",
  };
}

export function refund(state: Refund["state"]): Refund {
  const succeeded = state === "SUCCEEDED";
  return {
    refundId: "90000000-0000-4000-8000-000000000001",
    paymentId: ids.payment,
    state,
    cashRefundRequestedKrw: 12_800,
    pointsRestorationRequestedKrw: 2_000,
    pointsRestorationState: succeeded ? "SUCCEEDED" : "REQUESTED",
    cashRefundedKrw: succeeded ? 12_800 : undefined,
    pointsRestoredKrw: succeeded ? 2_000 : undefined,
    currency: "KRW",
    createdAt: "2026-08-15T03:00:00Z",
    updatedAt: "2026-08-15T03:02:00Z",
    correlationId: "REQ-DEMO-42",
  };
}

export const compensationManualReview: CompensationSummary = {
  caseId: "80000000-0000-4000-8000-000000000001",
  trigger: "STORE_REJECTION",
  benefitPolicies: [{ benefitType: "POINTS", policyVersionId: 12 }],
  state: "MANUAL_REVIEW",
  updatedAt: "2026-08-15T03:02:00Z",
  steps: [
    { type: "PAYMENT", state: "UNKNOWN", attemptCount: 2, lastErrorCode: "PROVIDER_TIMEOUT" },
    { type: "POINTS", state: "SUCCEEDED", attemptCount: 1 },
  ],
};

export const compensationSucceeded: CompensationSummary = {
  ...compensationManualReview,
  state: "SUCCEEDED",
  steps: compensationManualReview.steps.map((step) => ({ ...step, state: "SUCCEEDED", lastErrorCode: undefined })),
};

export const boardOrder = {
  orderReference: orderSummary.orderReference,
  pickupNumber: orderSummary.pickupNumber,
  pickupBusinessDate: "2026-08-15",
  lane: "PENDING_ACCEPTANCE",
  status: "PAID",
  pickupWindowStart: "2026-08-15T03:20:00Z",
  pickupWindowEnd: "2026-08-15T03:30:00Z",
  itemSummary: orderSummary.itemSummary,
  acceptanceDeadlineAt: "2026-08-15T03:03:00Z",
  acceptancePhase: "WARNING",
  allowedActions: ["ACCEPT", "REJECT"],
};

export const nearbyHandlers = [
  http.get("/api/v1/stores/nearby", () => HttpResponse.json({
    items: [
      { storeId: ids.store, name: "시청점", distanceMeters: 320, open: true, pickupAvailable: true },
      { storeId: "10000000-0000-4000-8000-000000000002", name: "광화문점", distanceMeters: 860, open: false, pickupAvailable: false },
    ],
  })),
];

export const catalogHandlers = [
  http.get("/api/v1/stores/:storeId/menus", () => HttpResponse.json({ items: [
    { menuId: ids.menu, name: "오트 라떼", basePriceKrw: 6_400, currency: "KRW", available: true, options: [] },
    { menuId: "20000000-0000-4000-8000-000000000002", name: "오늘의 필터 커피", basePriceKrw: 5_500, currency: "KRW", available: false, options: [] },
  ] })),
  http.get("/api/v1/stores/:storeId/pickup-slots", () => HttpResponse.json({ items: [
    { pickupSlotId: ids.slot, startsAt: "2026-08-15T03:20:00Z", endsAt: "2026-08-15T03:30:00Z", remainingCapacity: 7 },
    { pickupSlotId: "30000000-0000-4000-8000-000000000002", startsAt: "2026-08-15T03:30:00Z", endsAt: "2026-08-15T03:40:00Z", remainingCapacity: 3 },
  ] })),
];

export const checkoutHandlers = [
  http.get("/api/v1/orders/:orderId", () => HttpResponse.json(checkoutOrder)),
];

export function paymentHandlers(state: string) {
  return [http.get("/api/v1/payments/:paymentId", () => HttpResponse.json(payment(state)))];
}

export function orderListHandlers(items = [orderSummary]) {
  return [http.get("/api/v1/me/orders", () => HttpResponse.json({ items, page: { nextCursor: null } }))];
}

export function orderDetailHandlers(overrides: Record<string, unknown> = {}) {
  return [http.get("/api/v1/me/orders/:orderReference", () => HttpResponse.json({ ...orderDetail, ...overrides }))];
}

export function storeBoardHandlers(board = { groups: [{ pickupBusinessDate: "2026-08-15", items: [boardOrder] }], overflow: [] }) {
  return [
    http.get("/api/v1/merchant/me/stores", () => HttpResponse.json([{ storeId: ids.store, storeName: "시청점", membershipRole: "OWNER" }])),
    http.get("/api/v1/stores/:storeId/orders", () => HttpResponse.json(board, { headers: { ETag: '"storybook-board-v1"' } })),
  ];
}

export function apiError(path: string, status = 503, code = "DEPENDENCY_UNAVAILABLE", message = "서비스 연결을 확인하고 있습니다.") {
  return http.get(path, () => HttpResponse.json({ code, message, correlationId: "REQ-DEMO-42" }, { status }));
}

export function pending(path: string) {
  return http.get(path, async () => {
    await delay("infinite");
    return HttpResponse.json({});
  });
}

export const customerIdentity = {
  actorType: "CUSTOMER",
  actorId: "60000000-0000-4000-8000-000000000001",
  loginId: "demo.customer",
  displayName: "김빈플로우",
  accountState: "ACTIVE",
};

/** `GET /me` is what every guarded customer route waits for before it renders. */
export const signedInHandlers = [
  http.get("/api/v1/me", () => HttpResponse.json(customerIdentity)),
  http.get("/api/v1/auth/customer/csrf", () => new HttpResponse(null, { status: 204 })),
];

export const merchantIdentity = {
  actorType: "MERCHANT",
  merchantId: "80000000-0000-4000-8000-000000000001",
  displayName: "시청점 점주",
  accountState: "ACTIVE",
};

/**
 * `GET /merchant/me` is what every guarded store route waits for before it
 * renders, and the CSRF endpoint issues the JS-readable cookie that every
 * unsafe merchant request copies into its header.
 */
export const merchantSignedInHandlers = [
  http.get("/api/v1/merchant/me", () => HttpResponse.json(merchantIdentity)),
  http.get(
    "/api/v1/auth/merchant/csrf",
    () =>
      new HttpResponse(null, {
        status: 204,
        headers: { "Set-Cookie": "BEANFLOW_MERCHANT_XSRF=storybook-merchant-csrf; path=/" },
      }),
  ),
];

export const customerStore = { storeId: ids.store, name: "시청점", pickupAvailable: true };

export const storeIdentityHandlers = [
  http.get("/api/v1/stores/:storeId", () => HttpResponse.json(customerStore)),
];

export const homeHandlers = [
  ...signedInHandlers,
  http.get("/api/v1/me/orders", () => HttpResponse.json({ items: [orderSummary], page: { nextCursor: null } })),
  http.get("/api/v1/me/store-recommendations", () => HttpResponse.json({
    items: [
      { store: customerStore, reason: "RECENT" },
      { store: { storeId: "10000000-0000-4000-8000-000000000002", name: "광화문점", pickupAvailable: false }, reason: "NEARBY" },
    ],
  })),
];

export const searchHandlers = [
  ...signedInHandlers,
  http.get("/api/v1/stores/search", () => HttpResponse.json({
    items: [
      { ...customerStore, open: true, matchedMenus: [{ menuId: ids.menu, name: "오트 라떼" }], brandName: "빈플로우", regionName: "중구" },
      {
        storeId: "10000000-0000-4000-8000-000000000002",
        name: "광화문점",
        pickupAvailable: false,
        open: false,
        matchedMenus: [],
        brandName: "빈플로우",
        regionName: "종로구",
      },
    ],
    page: {},
    distanceAvailable: false,
  })),
];

export const pointsHandlers = [
  ...signedInHandlers,
  http.get("/api/v1/me/points", () => HttpResponse.json({
    availablePointsKrw: 1_500,
    recoveryPendingKrw: 300,
    currency: "KRW",
    expiring: [{ expiresAt: "2026-09-01T00:00:00Z", amountKrw: 1_000 }],
  })),
  http.get("/api/v1/me/point-transactions", () => HttpResponse.json({
    items: [
      { transactionId: "a1", type: "ACCRUAL", amountKrw: 200, occurredAt: "2026-08-14T00:00:00Z", sourceReference: "order:1" },
      { transactionId: "a2", type: "USE", amountKrw: -100, occurredAt: "2026-08-13T00:00:00Z", sourceReference: "order:2" },
    ],
    page: {},
  })),
];
