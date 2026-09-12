import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { StorefrontImageEditorView } from "./StorefrontImageEditorView";
const loadImage = fn(async () => ({}));
const meta = { title: "Patterns/Commerce/Shared image editor", component: StorefrontImageEditorView, tags: ["autodocs"], args: { label: "대표 이미지", loadImage, changeImage: fn(async () => {}) }, parameters: { a11y: { test: "error" } } } satisfies Meta<typeof StorefrontImageEditorView>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Empty: Story = { play: async ({ canvas }) => { await expect(await canvas.findByText("등록된 이미지가 없습니다.")).toBeVisible(); await userEvent.click(canvas.getByRole("button", { name: "현재 이미지 조회" })); await expect(loadImage).toHaveBeenCalledWith("STORE_MEDIA_REVIEW"); } };
