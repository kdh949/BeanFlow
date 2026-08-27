import type { Meta, StoryObj } from "@storybook/react-vite";
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
export const UnknownCodePreserved: Story = { args: { state: "PROVIDER_HOLD" } };
