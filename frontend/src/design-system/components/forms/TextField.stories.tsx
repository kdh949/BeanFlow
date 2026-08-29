import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { Eye, UserRound } from "lucide-react";
import { TextField } from "./Field";
import { IconButton } from "../core/ActionControls";

const meta = {
  title: "Components/Forms/TextField",
  component: TextField,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" } },
  args: { label: "로그인 ID", value: "", onValueChange: fn(), autoComplete: "username" },
  render: (args) => {
    const [value, setValue] = useState(args.value);
    return <TextField {...args} value={value} onValueChange={(next) => { args.onValueChange(next); setValue(next); }} />;
  },
} satisfies Meta<typeof TextField>;

export default meta;
type Story = StoryObj<typeof meta>;
export const Default: Story = { play: async ({ args, canvas }) => { const input = canvas.getByRole("textbox", { name: "로그인 ID" }); await expect(input.getBoundingClientRect().height).toBeGreaterThanOrEqual(44); await userEvent.type(input, "beanflow"); await expect(input).toHaveValue("beanflow"); await expect(args.onValueChange).toHaveBeenCalled(); } };
export const Invalid: Story = { args: { value: "bf", error: "로그인 ID는 5자 이상 입력해 주세요." }, play: async ({ canvas }) => { const input = canvas.getByLabelText("로그인 ID"); await expect(input).toHaveAttribute("aria-invalid", "true"); await expect(input).toHaveAccessibleDescription("로그인 ID는 5자 이상 입력해 주세요."); } };
export const ReadOnly: Story = { args: { value: "customer-1042", readOnly: true, description: "서버가 확정한 식별자입니다." } };
export const Disabled: Story = { args: { value: "사용할 수 없음", disabled: true } };
export const LargeCustomerControl: Story = { args: { size: "lg", value: "", placeholder: "휴대전화 번호" } };
export const WithAdornments: Story = {
  args: {
    label: "아이디",
    labelVisibility: "sr-only",
    placeholder: "아이디",
    leading: <UserRound size={18} />,
    trailing: <IconButton label="입력 내용 확인" variant="ghost"><Eye size={18} /></IconButton>,
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("textbox", { name: "아이디" })).toBeVisible();
    await expect(canvas.getByRole("button", { name: "입력 내용 확인" })).toBeVisible();
  },
};
