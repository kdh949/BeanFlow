import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { DemoEntryView } from "./DemoEntryView";
const meta = {
  title: "Pages/Demo/Start", component: DemoEntryView, tags: ["autodocs"],
  parameters: { layout: "fullscreen", a11y: { test: "error" } },
  args: { available: true, onStart: fn(), onResume: fn() },
} satisfies Meta<typeof DemoEntryView>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Start: Story = { play: async ({ canvas, args }) => {
  await userEvent.click(canvas.getByRole("button", { name: "주문 처리 체험 시작" }));
  await expect(args.onStart).toHaveBeenCalledWith("GUIDED");
} };
export const DirectOrder: Story = { play: async ({ canvas, args }) => {
  await userEvent.click(canvas.getByRole("button", { name: "직접 메뉴를 골라 주문하기" }));
  await expect(args.onStart).toHaveBeenCalledWith("DIRECT");
} };
export const Preparing: Story = { args: { busy: true } };
export const Resume: Story = { args: { resumable: true }, play: async ({ canvas, args }) => {
  await userEvent.click(canvas.getByRole("button", { name: "진행 중인 체험 이어하기" }));
  await expect(args.onResume).toHaveBeenCalled();
} };
export const Unavailable: Story = { args: { available: false } };
export const StartFailed: Story = { args: { error: "체험 공간을 준비하지 못했어요. 잠시 후 같은 요청으로 다시 시도해 주세요." } };
