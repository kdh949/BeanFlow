import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { Tab, TabList, TabPanel, Tabs } from "./Tabs";

const meta = { title: "Components/Navigation/Tabs", component: Tabs, tags: ["autodocs"], parameters: { a11y: { test: "error" } }, args: { value: "ACTIVE", onValueChange: fn(), children: null }, render: (args) => { const [value, setValue] = useState(args.value); return <Tabs {...args} value={value} onValueChange={(next) => { args.onValueChange(next); setValue(next); }}><TabList label="주문 상태"><Tab value="ACTIVE">진행 중</Tab><Tab value="PAST">지난 주문</Tab><Tab value="DISABLED" disabled>사용 불가</Tab></TabList><TabPanel value="ACTIVE">진행 중인 주문</TabPanel><TabPanel value="PAST">지난 주문 내역</TabPanel><TabPanel value="DISABLED">표시하지 않음</TabPanel></Tabs>; } } satisfies Meta<typeof Tabs>;
export default meta;
type Story = StoryObj<typeof meta>;
export const ManualActivation: Story = { play: async ({ args, canvas }) => { const active = canvas.getByRole("tab", { name: "진행 중" }); const target = active.getBoundingClientRect(); await expect(target.width).toBeGreaterThanOrEqual(44); await expect(target.height).toBeGreaterThanOrEqual(44); active.focus(); await userEvent.keyboard("{ArrowRight}"); const past = canvas.getByRole("tab", { name: "지난 주문" }); await expect(past).toHaveFocus(); await expect(past).toHaveAttribute("aria-selected", "false"); await userEvent.keyboard(" "); await expect(past).toHaveAttribute("aria-selected", "true"); await expect(args.onValueChange).toHaveBeenLastCalledWith("PAST"); } };
export const AutomaticActivation: Story = { args: { activationMode: "automatic" }, play: async ({ canvas }) => { const active = canvas.getByRole("tab", { name: "진행 중" }); active.focus(); await userEvent.keyboard("{End}"); await expect(canvas.getByRole("tab", { name: "지난 주문" })).toHaveAttribute("aria-selected", "true"); } };
