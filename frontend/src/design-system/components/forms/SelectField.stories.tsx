import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { SelectField } from "./Field";

const meta = { title: "Components/Forms/SelectField", component: SelectField, tags: ["autodocs"], parameters: { a11y: { test: "error" } }, args: { label: "취소 사유", value: "CHANGED_MIND", onValueChange: fn(), children: <><option value="CHANGED_MIND">마음이 바뀌었어요</option><option value="ORDER_MISTAKE">주문을 잘못했어요</option></> }, render: (args) => { const [value, setValue] = useState(args.value); return <SelectField {...args} value={value} onValueChange={(next) => { args.onValueChange(next); setValue(next); }} />; } } satisfies Meta<typeof SelectField>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Default: Story = { play: async ({ args, canvas }) => { const control = canvas.getByRole("combobox", { name: "취소 사유" }); await expect(control.getBoundingClientRect().height).toBeGreaterThanOrEqual(44); await userEvent.selectOptions(control, "ORDER_MISTAKE"); await expect(args.onValueChange).toHaveBeenLastCalledWith("ORDER_MISTAKE"); } };
export const Disabled: Story = { args: { disabled: true } };
export const Invalid: Story = { args: { error: "사유를 선택해 주세요." } };
