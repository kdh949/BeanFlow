import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { RadioCard, RadioGroup, RadioOption } from "./Selection";

const meta = { title: "Components/Forms/RadioGroup", component: RadioGroup, tags: ["autodocs"], parameters: { a11y: { test: "error" } }, args: { label: "픽업 시간", value: "10:10", onValueChange: fn(), children: null }, render: (args) => { const [value, setValue] = useState(args.value); return <RadioGroup {...args} value={value} onValueChange={(next) => { args.onValueChange(next); setValue(next); }}><RadioCard value="10:10" label="오전 10:10" description="12잔 가능" /><RadioCard value="10:20" label="오전 10:20" description="8잔 가능" /><RadioCard value="10:30" label="오전 10:30" description="마감" disabled /></RadioGroup>; } } satisfies Meta<typeof RadioGroup>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Cards: Story = { play: async ({ args, canvas }) => { const second = canvas.getByRole("radio", { name: /오전 10:20/ }); const target = second.closest("label")!.getBoundingClientRect(); await expect(target.width).toBeGreaterThanOrEqual(44); await expect(target.height).toBeGreaterThanOrEqual(44); await userEvent.click(second); await expect(second).toBeChecked(); await expect(args.onValueChange).toHaveBeenLastCalledWith("10:20"); second.focus(); await userEvent.keyboard("{ArrowLeft}"); await expect(canvas.getByRole("radio", { name: /오전 10:10/ })).toBeChecked(); } };
export const CompactOptions: Story = { render: (args) => { const [value, setValue] = useState("SEOUL"); return <RadioGroup {...args} label="지역" value={value} onValueChange={setValue}><RadioOption value="SEOUL" label="서울" /><RadioOption value="BUSAN" label="부산" /></RadioGroup>; } };
export const Invalid: Story = { args: { value: "", error: "픽업 시간을 선택해 주세요." } };
