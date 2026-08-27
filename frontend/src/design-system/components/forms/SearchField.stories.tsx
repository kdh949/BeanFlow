import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { SearchField } from "./SearchField";

const meta = {
  title: "Components/Forms/SearchField",
  component: SearchField,
  tags: ["autodocs"],
  args: { label: "매장 또는 메뉴 검색", placeholder: "매장 또는 메뉴를 검색해 보세요", onChange: fn() },
  parameters: { a11y: { test: "error" } },
} satisfies Meta<typeof SearchField>;

export default meta;
type Story = StoryObj<typeof meta>;
export const Empty: Story = {};
export const WithValue: Story = {
  args: { value: "아메리카노", onClear: fn() },
  play: async ({ args, canvas }) => {
    await userEvent.click(canvas.getByRole("button", { name: "검색어 지우기" }));
    await expect(args.onClear).toHaveBeenCalledOnce();
  },
};
