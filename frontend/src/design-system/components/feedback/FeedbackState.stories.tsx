import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { Button } from "../core/Button";
import { FeedbackState } from "./FeedbackState";

const meta = {
  title: "Components/Feedback/FeedbackState",
  component: FeedbackState,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component: "목록과 page section의 loading, empty, recoverable error를 같은 구조와 의미로 표현합니다.",
      },
    },
  },
} satisfies Meta<typeof FeedbackState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Loading: Story = {
  args: { kind: "loading", title: "주문을 불러오는 중", description: "잠시만 기다려 주세요." },
};

export const Empty: Story = {
  args: { kind: "empty", title: "아직 주문이 없어요", description: "가까운 매장에서 첫 주문을 시작해 보세요." },
};

const retry = fn();

export const RecoverableError: Story = {
  args: {
    kind: "error",
    title: "주문을 불러오지 못했습니다",
    description: "네트워크 연결을 확인한 뒤 다시 시도해 주세요.",
    reference: "REQ-DEMO-42",
    action: <Button variant="secondary" onClick={retry}>다시 시도</Button>,
  },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole("button", { name: "다시 시도" }));
    await expect(retry).toHaveBeenCalledOnce();
  },
};
