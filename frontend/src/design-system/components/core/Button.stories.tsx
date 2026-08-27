import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { Button } from "./Button";

const meta = {
  title: "Components/Core/Button",
  component: Button,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: {
        component: "폼 제출과 즉시 행동에 쓰는 canonical action control입니다. 이동에는 ButtonLink를 사용합니다.",
      },
    },
  },
  args: {
    children: "주문 접수",
    onClick: fn(),
  },
} satisfies Meta<typeof Button>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Brand: Story = {
  play: async ({ args, canvas }) => {
    await userEvent.click(canvas.getByRole("button", { name: "주문 접수" }));
    await expect(args.onClick).toHaveBeenCalledOnce();
  },
};

export const Secondary: Story = {
  args: { variant: "secondary", children: "다시 시도" },
};

export const Loading: Story = {
  args: { loading: true, children: "환불 요청 중" },
};

export const Disabled: Story = {
  args: { disabled: true, children: "준비 완료" },
};

export const LongKoreanLabel: Story = {
  args: { variant: "brand", children: "12,800원 결제하고 픽업 시간 확정하기" },
};

export const SmallTouchTarget: Story = {
  args: { size: "sm", children: "작게" },
  play: async ({ canvas }) => {
    const bounds = canvas.getByRole("button", { name: "작게" }).getBoundingClientRect();
    await expect(bounds.width).toBeGreaterThanOrEqual(44);
    await expect(bounds.height).toBeGreaterThanOrEqual(44);
  },
};
