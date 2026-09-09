import { Search } from "lucide-react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { IconButton } from "./ActionControls";

const meta = { title: "Components/Core/IconButton", component: IconButton, tags: ["autodocs"], parameters: { a11y: { test: "error" } }, args: { label: "검색", children: <Search size={18} aria-hidden="true" />, onClick: fn() } } satisfies Meta<typeof IconButton>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Secondary: Story = { play: async ({ args, canvas }) => { const action = canvas.getByRole("button", { name: "검색" }); const bounds = action.getBoundingClientRect(); await expect(bounds.width).toBeGreaterThanOrEqual(44); await expect(bounds.height).toBeGreaterThanOrEqual(44); await userEvent.click(action); await expect(args.onClick).toHaveBeenCalledOnce(); } };
export const GhostLarge: Story = { args: { variant: "ghost", size: "lg" } };
export const Loading: Story = { args: { loading: true } };
