import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { http, HttpResponse } from "msw";
import { ids } from "../../../.storybook/fixtures";
import { StoreMediaWorkspace } from "./StoreMediaWorkspace";
const menuId = "41000000-0000-4000-8000-000000000001";
const meta = {
  title: "Patterns/Operations/Store media", component: StoreMediaWorkspace, tags: ["autodocs"], args: { storeId: ids.store },
  parameters: { a11y: { test: "error" }, docs: { story: { inline: false, height: "900px" } }, msw: { handlers: [
    http.get("/api/v1/operations/stores/:storeId/image", () => HttpResponse.json({})),
    http.get("/api/v1/operations/stores/:storeId/media-menus", ({ request }) => { expect(request.headers.get("X-Access-Reason")).toBe("STORE_MEDIA_REVIEW"); return HttpResponse.json({ items: [{ menuId, name: "카페 라떼", lifecycle: new URL(request.url).searchParams.get("lifecycle") || "ACTIVE" }], nextCursor: null }); }),
    http.get("/api/v1/operations/stores/:storeId/menus/:menuId/image", () => HttpResponse.json({})),
  ] } },
} satisfies Meta<typeof StoreMediaWorkspace>;
export default meta;
type Story = StoryObj<typeof meta>;
export const SelectMenu: Story = { play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("button", { name: `카페 라떼 · ${menuId} 이미지 관리` })); await expect(await canvas.findByRole("heading", { name: `카페 라떼 · ${menuId} 이미지` })).toBeVisible(); } };
export const ArchivedMenus: Story = { play: async ({ canvas }) => { await userEvent.selectOptions(await canvas.findByLabelText("메뉴 범위"), "ARCHIVED"); await expect(await canvas.findByText("보관됨")).toBeVisible(); await userEvent.click(canvas.getByRole("button", { name: `카페 라떼 · ${menuId} 이미지 관리` })); await expect(await canvas.findByRole("heading", { name: `카페 라떼 · ${menuId} 이미지` })).toBeVisible(); } };
export const FailedDirectory: Story = { parameters: { msw: { handlers: [http.get("/api/v1/operations/stores/:storeId/image", () => HttpResponse.json({})), http.get("/api/v1/operations/stores/:storeId/media-menus", () => HttpResponse.json({ code: "ACCESS_DENIED" }, { status: 403 }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByRole("button", { name: `카페 라떼 · ${menuId} 이미지 관리` })).not.toBeInTheDocument(); } };
export const PagedDirectory: Story = { parameters: { msw: { handlers: [http.get("/api/v1/operations/stores/:storeId/image", () => HttpResponse.json({})), http.get("/api/v1/operations/stores/:storeId/media-menus", ({ request }) => new URL(request.url).searchParams.get("cursor") ? HttpResponse.json({ items: [{ menuId, name: "두 번째 메뉴", lifecycle: "ACTIVE" }], nextCursor: null }) : HttpResponse.json({ items: [{ menuId, name: "첫 번째 메뉴", lifecycle: "ACTIVE" }], nextCursor: "next-media-page" }))] } }, play: async ({ canvas }) => { await userEvent.click(await canvas.findByRole("button", { name: "다음 메뉴" })); await expect(await canvas.findByText("두 번째 메뉴")).toBeVisible(); await expect(canvas.queryByText("첫 번째 메뉴")).not.toBeInTheDocument(); await userEvent.click(canvas.getByRole("button", { name: "이전 메뉴" })); await expect(await canvas.findByText("첫 번째 메뉴")).toBeVisible(); } };

/** Identical names and lifecycles must still identify the exact image target. */
export const DuplicateMenuNames: Story = { play: async ({ canvas, msw }) => {
  const otherId = "42000000-0000-4000-8000-000000000001";
  msw.use(http.get("/api/v1/operations/stores/:storeId/media-menus", () => HttpResponse.json({ items: [
    { menuId, name: "카페 라떼", lifecycle: "ACTIVE" },
    { menuId: otherId, name: "카페 라떼", lifecycle: "ACTIVE" },
  ], nextCursor: null })), http.get("/api/v1/operations/stores/:storeId/menus/:menuId/image", ({ params }) => {
    expect(params.menuId).toBe(otherId); return HttpResponse.json({});
  }));
  await userEvent.click(await canvas.findByRole("button", { name: "메뉴 목록 새로고침" }));
  await expect(await canvas.findByText(`구분 코드 ${menuId}`)).toBeVisible();
  await expect(canvas.getByText(`구분 코드 ${otherId}`)).toBeVisible();
  await expect(canvas.getByRole("button", { name: `카페 라떼 · ${menuId} 이미지 관리` })).toBeVisible();
  await userEvent.click(canvas.getByRole("button", { name: `카페 라떼 · ${otherId} 이미지 관리` }));
  await expect(await canvas.findByRole("heading", { name: `카페 라떼 · ${otherId} 이미지` })).toBeVisible();
} };
