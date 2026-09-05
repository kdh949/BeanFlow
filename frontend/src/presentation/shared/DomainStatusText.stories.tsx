import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { DomainStatusText } from "./DomainStatusText";

const meta = {
  title: "Patterns/Shared/DomainStatusText",
  component: DomainStatusText,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: {
        component: "BeanFlow 도메인 상태를 사용자 문구와 디자인 시스템 tone으로 변환하는 application presentation pattern입니다.",
      },
    },
  },
} satisfies Meta<typeof DomainStatusText>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Ready: Story = { args: { state: "READY" } };
export const ManualReview: Story = { args: { state: "MANUAL_REVIEW" } };
export const Failed: Story = { args: { state: "FAILED" } };
export const StoreStatus: Story = { args: { state: "SOLD_OUT" }, play: async ({ canvas }) => { await expect(canvas.getByText("품절")).toBeVisible(); } };
export const OperationsStatus: Story = { args: { state: "ACTION_REQUIRED" }, play: async ({ canvas }) => { await expect(canvas.getByText("조치 필요")).toBeVisible(); } };
export const SupportStatus: Story = { args: { state: "READY_FOR_EXECUTION" }, play: async ({ canvas }) => { await expect(canvas.getByText("실행 준비")).toBeVisible(); } };
export const UnknownCodePreserved: Story = { args: { state: "NEW_STATE" }, play: async ({ canvas }) => { await expect(canvas.getByText("NEW_STATE")).toBeVisible(); } };
