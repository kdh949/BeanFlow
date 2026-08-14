import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { NotFoundPage } from "./router";

const meta = {
  title: "Pages/Shared/NotFound",
  component: NotFoundPage,
  tags: ["autodocs"],
  parameters: {
    docs: { description: { component: "등록되지 않은 route에서 안전한 root 진입점으로 돌아가게 하는 shared page입니다." } },
    routing: { path: "*", initialEntry: "/missing-route" },
  },
} satisfies Meta<typeof NotFoundPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const UnknownRoute: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("heading", { name: "화면을 찾을 수 없습니다" })).toBeVisible();
  },
};
