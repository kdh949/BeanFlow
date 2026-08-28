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
    const clear = canvas.getByRole("button", { name: "검색어 지우기" });
    const bounds = clear.getBoundingClientRect();
    await expect(bounds.width).toBeGreaterThanOrEqual(44);
    await expect(bounds.height).toBeGreaterThanOrEqual(44);
    await userEvent.click(clear);
    await expect(args.onClear).toHaveBeenCalledOnce();
  },
};
export const Invalid: Story = {
  args: { value: "", error: "검색어를 두 글자 이상 입력해 주세요." },
  play: async ({ canvas }) => {
    const field = canvas.getByRole("searchbox", { name: "매장 또는 메뉴 검색" });
    await expect(field).toHaveAttribute("aria-invalid", "true");
    await expect(field).toHaveAccessibleDescription("검색어를 두 글자 이상 입력해 주세요.");
  },
};
