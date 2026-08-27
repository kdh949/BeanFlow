import type { Meta, StoryObj } from "@storybook/react-vite";
import { StatusText } from "./StatusText";

const meta = {
  title: "Components/Commerce/StatusText",
  component: StatusText,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" }, docs: { description: { component: "채워진 배지 대신 거래 상태를 텍스트로 직접 표현합니다." } } },
} satisfies Meta<typeof StatusText>;

export default meta;
type Story = StoryObj<typeof meta>;
export const Ready: Story = { args: { state: "READY" } };
export const Unknown: Story = { args: { state: "UNKNOWN" } };
export const ManualReview: Story = { args: { state: "MANUAL_REVIEW" } };
export const Failed: Story = { args: { state: "FAILED" } };
export const UnknownCodePreserved: Story = { args: { state: "PROVIDER_HOLD" } };
