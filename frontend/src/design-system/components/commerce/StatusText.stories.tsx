import type { Meta, StoryObj } from "@storybook/react-vite";
import { StatusText } from "./StatusText";

const meta = {
  title: "Components/Commerce/StatusText",
  component: StatusText,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" }, docs: { description: { component: "채워진 배지 없이 전달받은 문구를 시각 tone으로 표현합니다. 도메인 상태 해석은 presentation 계층이 담당합니다." } } },
} satisfies Meta<typeof StatusText>;

export default meta;
type Story = StoryObj<typeof meta>;
export const Neutral: Story = { args: { children: "준비 완료", tone: "neutral" } };
export const Uncertain: Story = { args: { children: "확인 필요", tone: "uncertain" } };
export const Danger: Story = { args: { children: "실패", tone: "danger" } };
