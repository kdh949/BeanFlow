import type { Meta, StoryObj } from "@storybook/react-vite";
import { Button } from "../../components/core/Button";
import { PageHeading } from "./PageHeading";

const meta = {
  title: "Patterns/Layout/PageHeading",
  component: PageHeading,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" } },
} satisfies Meta<typeof PageHeading>;

export default meta;
type Story = StoryObj<typeof meta>;
export const Default: Story = { args: { title: "주문 보드", description: "실시간 주문을 상태별로 확인하고 처리합니다." } };
export const WithAction: Story = { args: { title: "정산 내역", description: "서버가 확정한 정산만 표시합니다.", action: <Button variant="secondary">새로고침</Button> } };
export const LongKoreanContent: Story = { args: { title: "고객과 매장의 거래 상태를 정확하게 확인하고 복구하기", description: "긴 한국어 설명도 행동을 밀어내거나 제목을 잘라내지 않고 자연스럽게 줄바꿈되어야 합니다." } };
