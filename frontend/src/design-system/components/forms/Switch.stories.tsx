import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { Switch } from "./Selection";

const meta = { title: "Components/Forms/Switch", component: Switch, tags: ["autodocs"], parameters: { a11y: { test: "error" } }, args: { label: "마케팅 알림", description: "혜택과 이벤트 알림을 받습니다.", checked: false, onCheckedChange: fn() }, render: (args) => { const [checked, setChecked] = useState(args.checked); return <Switch {...args} checked={checked} onCheckedChange={(next) => { args.onCheckedChange(next); setChecked(next); }} />; } } satisfies Meta<typeof Switch>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Off: Story = { play: async ({ args, canvas }) => { const control = canvas.getByRole("switch", { name: /마케팅 알림/ }); const target = control.getBoundingClientRect(); await expect(target.width).toBeGreaterThanOrEqual(44); await expect(target.height).toBeGreaterThanOrEqual(44); control.focus(); await userEvent.keyboard(" "); await expect(control).toBeChecked(); await expect(args.onCheckedChange).toHaveBeenCalledWith(true); } };
export const On: Story = {
  args: { checked: true },
  play: async ({ canvas }) => {
    const control = canvas.getByRole("switch", { name: /마케팅 알림/ });
    const track = control.nextElementSibling as HTMLElement;
    const knob = track.firstElementChild as HTMLElement;
    const trackBox = track.getBoundingClientRect();
    const knobBox = knob.getBoundingClientRect();
    const trackStyle = getComputedStyle(track);
    const expectedEndGap = Number.parseFloat(trackStyle.borderInlineEndWidth) + Number.parseFloat(trackStyle.paddingInlineEnd);

    await expect(Math.abs((trackBox.top + trackBox.height / 2) - (knobBox.top + knobBox.height / 2))).toBeLessThanOrEqual(0.5);
    await expect(Math.abs((trackBox.right - knobBox.right) - expectedEndGap)).toBeLessThanOrEqual(0.5);
  },
};
export const Disabled: Story = { args: { disabled: true } };
