import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, http } from "msw";
import { apiError, customerDisplay, customerStore, pending } from "../../../.storybook/fixtures";
import { RecentStoresPage } from "./RecentStoresPage";
const stores = [customerStore, { ...customerStore, storeId: "10000000-0000-4000-8000-000000000002", name: "광화문점", orderingAvailable: false }];
const handlers = [http.get("/api/v1/me/recent-stores", ({ request }) => { expect(new URL(request.url).searchParams.get("limit")).toBe("20"); return HttpResponse.json({ items: stores }); })];
const meta = {
  title: "Pages/Customer/Recent stores", component: RecentStoresPage, tags: ["autodocs"],
  parameters: { a11y: { test: "error" }, docs: { story: { inline: false, height: "844px" } }, routing: { path: "/app/recent-stores", initialEntry: "/app/recent-stores", surface: "refresh-customer" }, msw: { handlers } },
} satisfies Meta<typeof RecentStoresPage>;
export default meta;
type Story = StoryObj<typeof meta>;
export const RecentOrder: Story = { play: async ({ canvas }) => { const list = await canvas.findByRole("region", { name: "최근 주문 매장" }); await expect(list.querySelectorAll("a")[0]).toHaveAttribute("href", `/app/stores/${customerStore.storeId}`); await expect(canvas.getByText("주문 불가")).toBeVisible(); } };
export const Empty: Story = { parameters: { msw: { handlers: [http.get("/api/v1/me/recent-stores", () => HttpResponse.json({ items: [] }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("최근 주문한 매장이 없어요")).toBeVisible(); await expect(canvas.getByRole("link", { name: "매장 찾기" })).toHaveAttribute("href", "/app/stores"); } };
export const Loading: Story = { parameters: { msw: { handlers: [pending("/api/v1/me/recent-stores")] } } };
export const Unavailable: Story = { parameters: { msw: { handlers: [apiError("/api/v1/me/recent-stores")] } }, play: async ({ canvas }) => { await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByText("최근 주문한 매장이 없어요")).not.toBeInTheDocument(); } };
export const FailedRefresh: Story = { play: async ({ canvas, msw }) => { await canvas.findByRole("region", { name: "최근 주문 매장" }); msw.use(apiError("/api/v1/me/recent-stores")); await userEvent.click(canvas.getByRole("button", { name: "최근 매장 새로고침" })); await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByRole("region", { name: "최근 주문 매장" })).not.toBeInTheDocument(); } };
export const CurrentRefresh: Story = { play: async ({ canvas, msw }) => { await canvas.findByText("광화문점"); msw.use(http.get("/api/v1/me/recent-stores", () => HttpResponse.json({ items: [{ ...customerStore, orderingAvailable: false }] }))); await userEvent.click(canvas.getByRole("button", { name: "최근 매장 새로고침" })); await waitFor(() => expect(canvas.queryByText("광화문점")).not.toBeInTheDocument()); await expect(await canvas.findByText("주문 불가")).toBeVisible(); } };
export const LongName: Story = { parameters: { msw: { handlers: [http.get("/api/v1/me/recent-stores", () => HttpResponse.json({ items: [{ ...customerStore, name: "시청 광장 북쪽 출입구점", customerDisplay: { ...customerDisplay, addressLine: "서울 중구 세종대로 110 북쪽 출입구 옆 1층" } }] }))] } }, play: async ({ canvas }) => { const name = await canvas.findByText("시청 광장 북쪽 출입구점"); await expect(name.getBoundingClientRect().width).toBeGreaterThan(150); } };
