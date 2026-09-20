import type { Meta, StoryObj } from "@storybook/react-vite";
import { Route, Routes } from "react-router";
import { expect, userEvent, waitFor, within } from "storybook/test";
import { HttpResponse, http } from "msw";
import MockDate from "mockdate";
import { boardOrder, catalogHandlers, checkoutHandlers, favoriteHandlers, ids, orderDetail,
  pointsHandlers, signedInHandlers, storeIdentityHandlers, merchantSignedInHandlers, storeBoardHandlers } from "../../../.storybook/fixtures";
import { cart } from "../ordering/cart";
import { merchantSession } from "../auth/merchant/merchantSession";
import { customerSession } from "../auth/customer/customerSession";
import { CustomerShell, ConsoleShell } from "../../presentation/AppShells";
import { RefreshStoreOrderBoardPage } from "../../presentation/beanflow-refresh/MerchantPages";
import { RefreshStoreDetailPage, RefreshCartPage } from "../../presentation/beanflow-refresh/CustomerCommercePages";
import { RefreshCustomerOrderDetailPage, RefreshCheckoutPage } from "../../presentation/beanflow-refresh/CustomerTransactionPages";
import type { StoreOrderBoardItem } from "../../pages/console/storeOrderBoardModel";
import { DemoRoot } from "./DemoProvider";
import { DemoEntryPage } from "./DemoEntryPage";
import type { DemoSession } from "./demoClient";
import type { DemoOrderState } from "./demoGuideModel";

const initial: DemoSession = { workspaceId: "a0000000-0000-4000-8000-000000000001", status: "ACTIVE", mode: "GUIDED",
  storeId: ids.store, storeName: "BeanFlow 체험점", expiresAt: "2026-08-15T03:30:00Z",
  order: { orderReference: orderDetail.orderReference, status: "PAID", pickupNumber: "A-142", pickupWindowStart: null, acceptanceDeadlineAt: "2026-08-15T03:03:00Z" } };
let current: DemoSession | null = null;
let startKeys: string[] = [];
let trackKeys: string[] = [];
let trackFailuresRemaining = 0;
function boardItem(): StoreOrderBoardItem {
  const status = current?.order?.status ?? "PAID";
  const stages: Partial<Record<DemoOrderState, Pick<StoreOrderBoardItem, "lane" | "allowedActions">>> = {
    PAID: { lane: "PENDING_ACCEPTANCE", allowedActions: ["ACCEPT", "REJECT"] }, ACCEPTED: { lane: "ACCEPTED", allowedActions: ["START_PREPARING"] },
    PREPARING: { lane: "PREPARING", allowedActions: ["MARK_READY"] }, READY: { lane: "READY", allowedActions: ["COMPLETE"] },
  };
  return { ...boardOrder, itemSummary: "아이스 아메리카노 1개", status, acceptancePhase: "OPEN",
    ...stages[status], ...(stages[status] ? {} : { lane: undefined, allowedActions: [] }),
    lifecycle: { paidAt: "2026-08-15T03:00:00Z", ...(status !== "PAID" ? { acceptedAt: "2026-08-15T03:00:10Z" } : {}),
      ...(["PREPARING", "READY", "COMPLETED"].includes(status) ? { preparingAt: "2026-08-15T03:00:20Z" } : {}),
      ...(["READY", "COMPLETED"].includes(status) ? { readyAt: "2026-08-15T03:00:30Z" } : {}),
      ...(status === "COMPLETED" ? { completedAt: "2026-08-15T03:00:40Z" } : {}) } };
}
const handlers = [
  http.get("/api/v1/demo/config", () => HttpResponse.json({ enabled: true, lifetimeSeconds: 1800, testPaymentEnabled: true })),
  http.get("/api/v1/demo/csrf", () => HttpResponse.json({ token: "storybook-demo-csrf" })),
  http.get("/api/v1/demo/session", () => current ? HttpResponse.json(current) : new HttpResponse(null, { status: 204 })),
  http.post("/api/v1/demo/sessions", async ({ request }) => {
    startKeys.push(request.headers.get("Idempotency-Key")!);
    const { mode } = await request.json() as { mode: "GUIDED" | "DIRECT" };
    current ??= { ...structuredClone(initial), mode, order: mode === "DIRECT" ? null : initial.order };
    return HttpResponse.json(current, { status: 201 });
  }),
  http.post("/api/v1/demo/session/resume", () => HttpResponse.json(current)),
  http.post("/api/v1/demo/session/orders", () => { current = structuredClone(initial); return HttpResponse.json(current); }),
  http.post("/api/v1/demo/session/order", ({ request }) => {
    trackKeys.push(request.headers.get("Idempotency-Key")!);
    if (trackFailuresRemaining > 0) { trackFailuresRemaining -= 1; return HttpResponse.error(); }
    current = structuredClone(initial);
    return HttpResponse.json(current);
  }),
  http.delete("/api/v1/demo/session", () => { current = null; return new HttpResponse(null, { status: 204 }); }),
  http.get("/api/v1/stores/:storeId/orders", () => HttpResponse.json({ groups: boardItem().lane ? [{ pickupBusinessDate: "2026-08-15", items: [boardItem()] }] : [], overflow: [] })),
  http.post("/api/v1/stores/:storeId/orders/:orderReference/transitions", async ({ request }) => {
    const { action } = await request.json() as { action: string };
    const next: Record<string, DemoOrderState> = { ACCEPT: "ACCEPTED", START_PREPARING: "PREPARING", MARK_READY: "READY", COMPLETE: "COMPLETED" };
    current = { ...current!, order: { ...current!.order!, status: next[action]! } };
    return HttpResponse.json(boardItem());
  }),
  http.get("/api/v1/me/orders/:orderReference", () => HttpResponse.json({ ...orderDetail, status: current?.order?.status ?? "READY",
    storeName: "BeanFlow 체험점", lifecycle: boardItem().lifecycle, pricing: { subtotalKrw: 4500, couponDiscountKrw: 0, pointsAppliedKrw: 4500, payableKrw: 0, currency: "KRW" },
    lines: [{ lineSequence: 0, menuName: "아이스 아메리카노", optionNames: [], quantity: 1, lineTotalKrw: 4500 }] })),
  ...merchantSignedInHandlers, ...storeBoardHandlers(), ...signedInHandlers, ...storeIdentityHandlers, ...favoriteHandlers, ...catalogHandlers, ...pointsHandlers, ...checkoutHandlers,
];
function Runtime() { return <Routes><Route element={<DemoRoot />}>
  <Route path="/demo" element={<DemoEntryPage />} />
  <Route element={<ConsoleShell kind="store" />}><Route path="/store" element={<RefreshStoreOrderBoardPage now={new Date("2026-08-15T03:00:30Z")} />} /></Route>
  <Route element={<CustomerShell />}>
    <Route path="/app/orders/:orderReference" element={<RefreshCustomerOrderDetailPage />} />
    <Route path="/app/orders/:orderReference/checkout" element={<RefreshCheckoutPage />} />
    <Route path="/app/stores/:storeId" element={<RefreshStoreDetailPage />} />
    <Route path="/app/cart" element={<RefreshCartPage />} />
  </Route>
</Route></Routes>; }
const meta = {
  title: "Pages/Demo/Journey", component: DemoRoot, render: () => <Runtime />, tags: ["autodocs"],
  parameters: { layout: "fullscreen", a11y: { test: "error" }, routing: { path: "*", initialEntry: "/demo" }, msw: { handlers } },
  beforeEach: async () => {
    MockDate.set("2026-08-15T03:00:30Z"); current = null; startKeys = []; trackKeys = []; trackFailuresRemaining = 0; cart.clear();
    localStorage.removeItem("beanflow.demo.active.v1"); sessionStorage.removeItem("beanflow.demo.intent.v1");
    document.cookie = "BEANFLOW_DEMO_XSRF=storybook-demo-csrf; path=/";
    document.cookie = "BEANFLOW_MERCHANT_XSRF=storybook-merchant-csrf; path=/";
    customerSession.reset(); merchantSession.reset();
    await Promise.all([customerSession.refresh(), merchantSession.refresh()]);
    return () => { MockDate.reset(); localStorage.removeItem("beanflow.demo.active.v1"); sessionStorage.removeItem("beanflow.demo.intent.v1"); cart.clear(); };
  },
} satisfies Meta<typeof DemoRoot>;
export default meta;
type Story = StoryObj<typeof meta>;
function stage(status: DemoOrderState, path = "/store"): Story {
  return { parameters: { routing: { path: "*", initialEntry: path } }, beforeEach: () => {
    current = { ...structuredClone(initial), order: { ...initial.order!, status } }; localStorage.setItem("beanflow.demo.active.v1", "true");
  }, play: async ({ canvas }) => { await expect(await canvas.findByText("체험 모드")).toBeVisible(); } };
}
export const Entry: Story = { play: async ({ canvas }) => { await expect(await canvas.findByRole("button", { name: "주문 처리 체험 시작" })).toBeEnabled(); } };
export const AcceptOrder: Story = stage("PAID");
export const StartPreparing: Story = stage("ACCEPTED");
export const Preparing: Story = stage("PREPARING");
export const CustomerReady: Story = stage("READY", `/app/orders/${orderDetail.orderReference}`);
export const PickupReady: Story = { ...stage("READY", `/app/orders/${orderDetail.orderReference}`), play: async ({ canvas }) => {
  await userEvent.click(await canvas.findByRole("button", { name: "점주 화면으로 돌아가기" }));
  await expect(await canvas.findByRole("heading", { name: "픽업을 완료해보세요" })).toBeVisible();
} };
export const Completed: Story = stage("COMPLETED");
export const DirectAfterCompletion: Story = { ...stage("COMPLETED"), play: async ({ canvas }) => {
  await userEvent.click(await canvas.findByRole("button", { name: "직접 메뉴를 골라 주문하기" }));
  await expect(await canvas.findByRole("heading", { name: "원하는 메뉴를 골라보세요" })).toBeVisible();
} };
export const Menu: Story = stage("COMPLETED", `/app/stores/${ids.store}`);
export const Cart: Story = { ...stage("COMPLETED", "/app/cart"), beforeEach: () => {
  current = { ...structuredClone(initial), order: { ...initial.order!, status: "COMPLETED" } }; localStorage.setItem("beanflow.demo.active.v1", "true");
  cart.add({ storeId: ids.store, storeName: "BeanFlow 체험점" }, { menuId: ids.menu, optionIds: [], quantity: 1, display: { menuName: "오트 라떼", optionNames: [], unitPriceKrw: 6400 } });
} };
export const Checkout: Story = stage("PENDING_PAYMENT", `/app/orders/${orderDetail.orderReference}/checkout`);
export const TimedOut: Story = stage("REJECTED");
export const TimeoutRestart: Story = { ...stage("REJECTED"), play: async ({ canvas }) => {
  await userEvent.click(await canvas.findByRole("button", { name: "새 샘플 주문으로 다시 시작" }));
  await expect(await canvas.findByRole("heading", { name: "첫 주문을 접수해보세요" })).toBeVisible();
} };
export const Expired: Story = { parameters: { routing: { path: "*", initialEntry: "/store" } }, beforeEach: () => {
  current = { ...structuredClone(initial), status: "EXPIRED", order: null }; localStorage.setItem("beanflow.demo.active.v1", "true");
}, play: async ({ canvas }) => { await expect(await canvas.findByText("체험 시간이 끝났어요")).toBeVisible(); } };
export const EndWorkspace: Story = { ...stage("PAID"), play: async ({ canvas }) => {
  await userEvent.click(await canvas.findByRole("button", { name: "체험 종료" }));
  await expect(await canvas.findByRole("button", { name: "주문 처리 체험 시작" })).toBeEnabled();
  await expect(localStorage.getItem("beanflow.demo.active.v1")).toBeNull();
} };
export const RetrySameStart: Story = { play: async ({ canvas, msw }) => {
  msw.use(http.post("/api/v1/demo/sessions", ({ request }) => { startKeys.push(request.headers.get("Idempotency-Key")!); return HttpResponse.error(); }, { once: true }));
  await userEvent.click(await canvas.findByRole("button", { name: "주문 처리 체험 시작" }));
  await expect(await canvas.findByRole("alert")).toBeVisible();
  await userEvent.click(canvas.getByRole("button", { name: "주문 처리 체험 시작" }));
  await expect(await canvas.findByRole("heading", { name: "첫 주문을 접수해보세요" })).toBeVisible();
  await expect(startKeys).toHaveLength(2); await expect(startKeys[0]).toBe(startKeys[1]);
} };
export const RetryFailedOrderTracking: Story = {
  parameters: { routing: { path: "*", initialEntry: `/app/orders/${orderDetail.orderReference}` } },
  beforeEach: () => {
    current = { ...structuredClone(initial), mode: "DIRECT", order: null };
    trackFailuresRemaining = 1;
    localStorage.setItem("beanflow.demo.active.v1", "true");
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "상태 다시 확인" }));
    await expect(await canvas.findByRole("heading", { name: "첫 주문을 접수해보세요" })).toBeVisible();
    await expect(trackKeys).toHaveLength(2);
    await expect(trackKeys[0]).toBe(trackKeys[1]);
  },
};
export const FullJourney: Story = { play: async ({ canvas }) => {
  await userEvent.click(await canvas.findByRole("button", { name: "주문 처리 체험 시작" }));
  const step = async (button: string, title: string) => {
    const card = await canvas.findByRole("article", { name: "주문 A-142" });
    await userEvent.click(within(card).getByRole("button", { name: button }));
    window.dispatchEvent(new Event("focus"));
    await waitFor(() => expect(canvas.getByRole("heading", { name: title })).toBeVisible(), { timeout: 6000 });
  };
  await step("주문 접수", "이제 음료 제조를 시작해보세요");
  await step("제조 시작", "음료가 준비되면 알려주세요");
  await step("준비 완료", "고객 화면의 변화를 확인해보세요");
  await userEvent.click(canvas.getByRole("button", { name: "고객 화면 확인하기" }));
  await expect(await canvas.findByRole("heading", { name: "고객에게도 준비 완료가 표시돼요" })).toBeVisible();
  await userEvent.click(canvas.getByRole("button", { name: "점주 화면으로 돌아가기" }));
  await step("픽업 완료", "주문 한 건의 흐름을 모두 확인했어요");
} };
