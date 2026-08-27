import { LocateFixed } from "lucide-react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { ChipButton } from "./ActionControls";

const meta = { title: "Components/Core/ChipButton", component: ChipButton, tags: ["autodocs"], parameters: { a11y: { test: "error" } }, args: { children: "아메리카노", onClick: fn() } } satisfies Meta<typeof ChipButton>;
export default meta;
type Story = StoryObj<typeof meta>;
export const QueryHelper: Story = { play: async ({ args, canvas }) => { const action = canvas.getByRole("button", { name: "아메리카노" }); const target = action.getBoundingClientRect(); await expect(target.width).toBeGreaterThanOrEqual(44); await expect(target.height).toBeGreaterThanOrEqual(44); await userEvent.click(action); await expect(args.onClick).toHaveBeenCalledOnce(); } };
export const WithIcon: Story = { args: { children: <><LocateFixed size={14} aria-hidden="true" />현재 위치</> } };
export const SelectedFilter: Story = { args: { children: "진행 중", "aria-pressed": true } };
export const Disabled: Story = { args: { disabled: true } };
