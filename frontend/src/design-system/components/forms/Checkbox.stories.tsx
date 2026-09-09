import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { Checkbox } from "./Selection";

const meta = { title: "Components/Forms/Checkbox", component: Checkbox, tags: ["autodocs"], parameters: { a11y: { test: "error" } }, args: { label: "샷 추가", description: "에스프레소 샷을 한 번 추가합니다.", checked: false, onCheckedChange: fn() }, render: (args) => { const [checked, setChecked] = useState(args.checked); return <Checkbox {...args} checked={checked} onCheckedChange={(next) => { args.onCheckedChange(next); setChecked(next); }} />; } } satisfies Meta<typeof Checkbox>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Standard: Story = { play: async ({ args, canvas }) => { const input = canvas.getByRole("checkbox", { name: /샷 추가/ }); const target = input.closest("label")!.getBoundingClientRect(); await expect(target.width).toBeGreaterThanOrEqual(44); await expect(target.height).toBeGreaterThanOrEqual(44); input.focus(); await userEvent.keyboard(" "); await expect(input).toBeChecked(); await expect(args.onCheckedChange).toHaveBeenCalledWith(true); } };
export const CardWithPrice: Story = { args: { variant: "card", trailing: "+500원" } };
export const Disabled: Story = { args: { disabled: true } };
