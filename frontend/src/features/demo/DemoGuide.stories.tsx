import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { DemoGuide } from "./DemoGuide";
import { demoGuideView, type DemoOrderState } from "./demoGuideModel";
const view = (status: DemoOrderState | null, surface: "store" | "customer" = "store", pathname = "/store", customerChecked = false) => demoGuideView({ status, surface, pathname, customerChecked, pickupNumber: "A-142" });
const meta = {
  title: "Patterns/Demo/Journey guide", component: DemoGuide, tags: ["autodocs"],
  parameters: { a11y: { test: "error" } },
  args: { view: view("PAID"), onAction: fn() },
} satisfies Meta<typeof DemoGuide>;
export default meta;
type Story = StoryObj<typeof meta>;
export const AcceptOrder: Story = { play: async ({ canvas }) => {
  const toggle = canvas.getByRole("button", { name: "체험 안내 접기" });
  await userEvent.click(toggle); await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await userEvent.keyboard("{Enter}"); await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await expect(canvas.getByText("첫 주문을 접수해보세요")).toBeVisible();
} };
export const StartPreparing: Story = { args: { view: view("ACCEPTED") } };
export const MarkReady: Story = { args: { view: view("PREPARING") } };
export const CheckCustomer: Story = { args: { view: view("READY") }, play: async ({ canvas, args }) => {
  await userEvent.click(canvas.getByRole("button", { name: "고객 화면 확인하기" }));
  await expect(args.onAction).toHaveBeenCalledWith("customer");
} };
export const CustomerReady: Story = { args: { view: view("READY", "customer", "/app/orders/BF-DEMO-142") } };
export const CompletePickup: Story = { args: { view: view("READY", "store", "/store", true) } };
export const Completed: Story = { args: { view: view("COMPLETED", "customer") } };
export const ChooseMenu: Story = { args: { view: view(null, "customer", "/app/stores/demo") } };
export const Cart: Story = { args: { view: view(null, "customer", "/app/cart") } };
export const Checkout: Story = { args: { view: view("PENDING_PAYMENT", "customer", "/app/orders/BF-DEMO-142/checkout") } };
export const TimedOut: Story = { args: { view: view("REJECTED") } };
export const WorkspaceExpired: Story = { args: { view: demoGuideView({ status: null, surface: "store", pathname: "/store", customerChecked: false, expired: true }) } };
export const PendingAction: Story = { args: { view: view("REJECTED"), busy: true } };
export const LongKoreanText: Story = { args: { view: { ...view("PAID"), body: "주문 처리 중에도 서버에서 확인한 상태를 기준으로 안내합니다. 긴 메뉴 이름과 옵션이 포함된 주문은 품목·옵션 보기를 열어 전체 내용을 확인한 후 접수해 주세요." } } };
