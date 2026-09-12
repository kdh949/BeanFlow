import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor } from "storybook/test";
import { delay, http, HttpResponse } from "msw";
import { CustomerPointTargetPicker, type PointCustomerSelection } from "./CustomerPointTargetPicker";

const customer: PointCustomerSelection = { customerId: "97000000-0000-4000-8000-000000000001", maskedLoginId: "m***", maskedDisplayName: "김*수" };
const searchPath = "/api/v1/operations/customer-searches";
const meta = {
  title: "Patterns/Operations/Point customer selection",
  component: CustomerPointTargetPicker,
  tags: ["autodocs"],
  args: { value: null, onValueChange: fn() },
  render: function Render(args) {
    const [value, setValue] = useState(args.value);
    return <CustomerPointTargetPicker {...args} value={value} onValueChange={next => { setValue(next); args.onValueChange(next); }} />;
  },
  parameters: {
    a11y: { test: "error" },
    msw: { handlers: [http.post(searchPath, async ({ request }) => {
      expect(await request.json()).toEqual({ loginId: "minsu01", reasonCode: "POINT_ACCOUNT_INVESTIGATION" });
      return HttpResponse.json({ items: [customer] });
    })] },
    docs: { description: { component: "가입 고객의 로그인 아이디를 정확 검색하여 가린 결과를 선택합니다. 선택은 화면 메모리에만 두고 검색 변경 시 이전 대상을 비웁니다." }, story: { inline: false, height: "650px" } },
  },
} satisfies Meta<typeof CustomerPointTargetPicker>;
export default meta;
type Story = StoryObj<typeof meta>;
type Canvas = Parameters<NonNullable<Story["play"]>>[0]["canvas"];
async function search(canvas: Canvas) {
  await userEvent.type(canvas.getByRole("searchbox", { name: "고객 로그인 아이디" }), "minsu01");
  await userEvent.click(canvas.getByRole("button", { name: "고객 찾기" }));
}

export const SelectCustomer: Story = { play: async ({ canvas, args }) => {
  await search(canvas);
  await userEvent.click(await canvas.findByRole("button", { name: "이 고객 선택" }));
  await expect(canvas.getByText("선택한 고객")).toBeVisible();
  await expect(args.onValueChange).toHaveBeenLastCalledWith(customer);
  await expect(canvas.queryByText(customer.customerId)).not.toBeInTheDocument();
} };
export const ChangeCustomer: Story = { play: async ({ canvas, args }) => {
  await search(canvas);
  await userEvent.click(await canvas.findByRole("button", { name: "이 고객 선택" }));
  await userEvent.click(canvas.getByRole("button", { name: "다른 고객 찾기" }));
  await expect(canvas.getByRole("searchbox")).toHaveValue("");
  await expect(canvas.queryByText("김*수")).not.toBeInTheDocument();
  await expect(args.onValueChange).toHaveBeenLastCalledWith(null);
} };
export const Empty: Story = { parameters: { msw: { handlers: [http.post(searchPath, () => HttpResponse.json({ items: [] }))] } }, play: async ({ canvas }) => {
  await search(canvas);
  await expect(await canvas.findByText("일치하는 고객이 없습니다")).toBeVisible();
  await expect(canvas.queryByRole("button", { name: "이 고객 선택" })).not.toBeInTheDocument();
} };
export const Unavailable: Story = { parameters: { msw: { handlers: [http.post(searchPath, () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE" }, { status: 503 }))] } }, play: async ({ canvas }) => {
  await search(canvas);
  await expect(await canvas.findByRole("alert")).toBeVisible();
  await expect(canvas.queryByText("일치하는 고객이 없습니다")).not.toBeInTheDocument();
} };
export const PermissionDenied: Story = { parameters: { msw: { handlers: [http.post(searchPath, () => HttpResponse.json({ code: "ACCESS_DENIED" }, { status: 403 }))] } }, play: async ({ canvas }) => {
  await search(canvas);
  await expect(await canvas.findByRole("alert")).toBeVisible();
  await expect(canvas.queryByRole("button", { name: "이 고객 선택" })).not.toBeInTheDocument();
} };
export const Loading: Story = { parameters: { msw: { handlers: [http.post(searchPath, async () => { await delay("infinite"); return HttpResponse.json({ items: [] }); })] } }, play: async ({ canvas }) => {
  await search(canvas);
  await expect(await canvas.findByText("고객을 찾는 중")).toBeVisible();
} };
export const LockedDuringAdjustment: Story = { args: { value: customer, disabled: true }, play: async ({ canvas }) => {
  await expect(canvas.getByRole("button", { name: "다른 고객 찾기" })).toBeDisabled();
} };
export const EditedSearchDiscardsResults: Story = { play: async ({ canvas }) => {
  await search(canvas);
  await canvas.findByRole("button", { name: "이 고객 선택" });
  await userEvent.type(canvas.getByRole("searchbox"), "2");
  await expect(canvas.queryByRole("button", { name: "이 고객 선택" })).not.toBeInTheDocument();
} };
export const LongCustomerLabel: Story = { args: { value: { ...customer, maskedDisplayName: `긴${"*".repeat(98)}름` } } };

let releaseFirst: (() => void) | undefined;
const firstReturned = fn();
export const LateSearchIgnored: Story = {
  beforeEach: () => { releaseFirst = undefined; firstReturned.mockClear(); },
  parameters: { msw: { handlers: [http.post(searchPath, async ({ request }) => {
    const body = await request.json() as { loginId: string };
    if (body.loginId === "minsu01") {
      await new Promise<void>(resolve => { releaseFirst = resolve; });
      firstReturned();
      return HttpResponse.json({ items: [customer] });
    }
    return HttpResponse.json({ items: [] });
  })] } },
  play: async ({ canvas }) => {
    await search(canvas);
    await waitFor(() => expect(typeof releaseFirst).toBe("function"));
    await userEvent.type(canvas.getByRole("searchbox"), "2");
    await userEvent.click(canvas.getByRole("button", { name: "고객 찾기" }));
    await canvas.findByText("일치하는 고객이 없습니다");
    releaseFirst!();
    await waitFor(() => expect(firstReturned).toHaveBeenCalled());
    await expect(canvas.queryByRole("button", { name: "이 고객 선택" })).not.toBeInTheDocument();
  },
};
