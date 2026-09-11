import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor } from "storybook/test";
import { delay, http, HttpResponse } from "msw";
import { selectionStore, storeSelectionHandler } from "../../../.storybook/storeSelectionFixtures";
import { StoreTargetPicker } from "./StoreTargetPicker";

const path = "/api/v1/operations/stores";
const meta = {
  title: "Patterns/Operations/Store selection", component: StoreTargetPicker, tags: ["autodocs"],
  args: { value: null, onValueChange: fn() },
  render: function Render(args) {
    const [value, setValue] = useState(args.value);
    return <StoreTargetPicker {...args} value={value} onValueChange={next => { setValue(next); args.onValueChange(next); }} />;
  },
  parameters: { a11y: { test: "error" }, msw: { handlers: [storeSelectionHandler] }, docs: { description: { component: "운영 매장을 이름으로 검색하고 서버가 반환한 매장을 선택합니다. 페이지 이동·검색 수정 시 이전 결과를 폐기하며 명령 중에는 대상을 잠급니다." }, story: { inline: false, height: "650px" } } },
} satisfies Meta<typeof StoreTargetPicker>;
export default meta;
type Story = StoryObj<typeof meta>;
type Canvas = Parameters<NonNullable<Story["play"]>>[0]["canvas"];
async function search(canvas: Canvas) {
  await userEvent.type(canvas.getByRole("searchbox", { name: "매장 이름 검색" }), "성수");
  await userEvent.keyboard("{Enter}");
}
export const SelectAndChange: Story = { play: async ({ canvas, args }) => {
  await search(canvas);
  await userEvent.click(await canvas.findByRole("button", { name: "빈플로우 성수점 선택" }));
  await expect(args.onValueChange).toHaveBeenLastCalledWith(selectionStore);
  await expect(canvas.getByText("선택한 매장")).toBeVisible();
  await expect(canvas.queryByText(selectionStore.storeId)).not.toBeInTheDocument();
  await userEvent.click(canvas.getByRole("button", { name: "다른 매장 찾기" }));
  await expect(args.onValueChange).toHaveBeenLastCalledWith(null);
  await expect(canvas.getByRole("searchbox")).toHaveValue("");
} };
export const Pagination: Story = { parameters: { msw: { handlers: [http.get(path, ({ request }) => {
  const query = new URL(request.url).searchParams;
  expect(query.get("query")).toBe("성수");
  return HttpResponse.json(query.has("cursor") ? { items: [{ ...selectionStore, name: "빈플로우 성수역점" }] } : { items: [selectionStore], nextCursor: "next-store" });
})] } }, play: async ({ canvas }) => {
  await search(canvas);
  await canvas.findByText("빈플로우 성수점");
  await userEvent.click(canvas.getByRole("button", { name: "다음 매장 목록" }));
  await expect(await canvas.findByText("빈플로우 성수역점")).toBeVisible();
  await userEvent.click(canvas.getByRole("button", { name: "이전 매장 목록" }));
  await expect(await canvas.findByText("빈플로우 성수점")).toBeVisible();
  await userEvent.type(canvas.getByRole("searchbox"), "역");
  await expect(canvas.queryByRole("button", { name: "빈플로우 성수점 선택" })).not.toBeInTheDocument();
} };
export const Empty: Story = { parameters: { msw: { handlers: [http.get(path, () => HttpResponse.json({ items: [] }))] } }, play: async ({ canvas }) => { await search(canvas); await expect(await canvas.findByText("일치하는 매장이 없습니다")).toBeVisible(); } };
export const Unavailable: Story = { parameters: { msw: { handlers: [http.get(path, () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE" }, { status: 503 }))] } }, play: async ({ canvas }) => { await search(canvas); await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByText("일치하는 매장이 없습니다")).not.toBeInTheDocument(); } };
export const PermissionDenied: Story = { parameters: { msw: { handlers: [http.get(path, () => HttpResponse.json({ code: "ACCESS_DENIED" }, { status: 403 }))] } }, play: async ({ canvas }) => { await search(canvas); await expect(await canvas.findByRole("alert")).toBeVisible(); } };
export const Loading: Story = { parameters: { msw: { handlers: [http.get(path, async () => { await delay("infinite"); return HttpResponse.json({ items: [] }); })] } }, play: async ({ canvas }) => { await search(canvas); await expect(await canvas.findByText("매장을 찾는 중")).toBeVisible(); } };
export const LockedTarget: Story = { args: { value: selectionStore, disabled: true }, play: async ({ canvas }) => { await expect(canvas.getByRole("button", { name: "다른 매장 찾기" })).toBeDisabled(); } };
export const LongName: Story = { args: { value: { ...selectionStore, name: "빈플로우 성수동 서울숲 문화예술센터 별관 2층 테라스점" } } };
let releaseFirst: (() => void) | undefined;
const returned = fn();
export const LateResponseIgnored: Story = { beforeEach: () => { releaseFirst = undefined; returned.mockClear(); }, parameters: { msw: { handlers: [http.get(path, async ({ request }) => {
  if (new URL(request.url).searchParams.get("query") === "성수") {
    await new Promise<void>(resolve => { releaseFirst = resolve; }); returned(); return HttpResponse.json({ items: [selectionStore] });
  }
  return HttpResponse.json({ items: [] });
})] } }, play: async ({ canvas }) => {
  await search(canvas); await waitFor(() => expect(typeof releaseFirst).toBe("function"));
  await userEvent.type(canvas.getByRole("searchbox"), "역"); await userEvent.keyboard("{Enter}");
  await canvas.findByText("일치하는 매장이 없습니다"); releaseFirst!();
  await waitFor(() => expect(returned).toHaveBeenCalled());
  await expect(canvas.queryByText("빈플로우 성수점")).not.toBeInTheDocument();
} };
